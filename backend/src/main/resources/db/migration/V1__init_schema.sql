-- =============================================================
--  KrishiMitra DB Schema  —  V1 initial migration
--  PostgreSQL 15+
-- =============================================================

-- ── Extensions ───────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";        -- fuzzy text search on mandi names

-- ── FARMERS ──────────────────────────────────────────────────
CREATE TABLE farmers (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    phone           VARCHAR(15)  NOT NULL UNIQUE,
    name            VARCHAR(120) NOT NULL,
    email           VARCHAR(200),
    password_hash   VARCHAR(255) NOT NULL,
    village         VARCHAR(120),
    district        VARCHAR(120),
    state           VARCHAR(80)  NOT NULL DEFAULT 'Madhya Pradesh',
    latitude        DECIMAL(9,6),
    longitude       DECIMAL(9,6),
    preferred_lang  VARCHAR(10)  NOT NULL DEFAULT 'hi',
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_farmers_phone   ON farmers(phone);
CREATE INDEX idx_farmers_state   ON farmers(state);
CREATE INDEX idx_farmers_district ON farmers(district);

-- ── REFRESH TOKENS ───────────────────────────────────────────
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    farmer_id   UUID        NOT NULL REFERENCES farmers(id) ON DELETE CASCADE,
    token       VARCHAR(512) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_token ON refresh_tokens(token);

-- ── FARMER CROPS (what each farmer currently grows) ──────────
CREATE TABLE farmer_crops (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    farmer_id       UUID         NOT NULL REFERENCES farmers(id) ON DELETE CASCADE,
    commodity       VARCHAR(80)  NOT NULL,
    variety         VARCHAR(80),
    quantity_quintal DECIMAL(10,2),
    planted_date    DATE,
    expected_harvest DATE,
    storage_available BOOLEAN    NOT NULL DEFAULT FALSE,
    notes           TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_farmer_crops_farmer ON farmer_crops(farmer_id);
CREATE INDEX idx_farmer_crops_commodity ON farmer_crops(commodity);

-- ── MANDIS ────────────────────────────────────────────────────
CREATE TABLE mandis (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name        VARCHAR(120) NOT NULL,
    district    VARCHAR(120) NOT NULL,
    state       VARCHAR(80)  NOT NULL,
    latitude    DECIMAL(9,6),
    longitude   DECIMAL(9,6),
    apmc_code   VARCHAR(20),
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (name, state)
);

CREATE INDEX idx_mandis_state    ON mandis(state);
CREATE INDEX idx_mandis_name_trgm ON mandis USING gin(name gin_trgm_ops);

-- ── MANDI PRICES (time-series — high-volume) ─────────────────
CREATE TABLE mandi_prices (
    id              BIGSERIAL PRIMARY KEY,
    mandi_id        UUID         NOT NULL REFERENCES mandis(id),
    commodity       VARCHAR(80)  NOT NULL,
    variety         VARCHAR(80)  NOT NULL DEFAULT 'Common',
    price_date      DATE         NOT NULL,
    min_price       DECIMAL(10,2) NOT NULL,
    max_price       DECIMAL(10,2) NOT NULL,
    modal_price     DECIMAL(10,2) NOT NULL,
    arrivals_qtl    DECIMAL(12,2),
    source          VARCHAR(40)  NOT NULL DEFAULT 'agmarknet',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (mandi_id, commodity, variety, price_date)
);

-- Partition by year for performance (PostgreSQL declarative partitioning)
CREATE INDEX idx_mandi_prices_mandi_commodity_date
    ON mandi_prices(mandi_id, commodity, price_date DESC);
CREATE INDEX idx_mandi_prices_commodity_date
    ON mandi_prices(commodity, price_date DESC);

-- ── PRICE PREDICTIONS ─────────────────────────────────────────
CREATE TABLE price_predictions (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    mandi_id        UUID         NOT NULL REFERENCES mandis(id),
    commodity       VARCHAR(80)  NOT NULL,
    generated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    current_price   DECIMAL(10,2) NOT NULL,
    -- JSON arrays: [h1, h3, h7, h14, h21, h30]
    point_forecast  JSONB        NOT NULL,
    lower_80        JSONB        NOT NULL,
    upper_80        JSONB        NOT NULL,
    lower_95        JSONB        NOT NULL,
    upper_95        JSONB        NOT NULL,
    sell_decision   VARCHAR(20)  NOT NULL,   -- SELL_NOW | WAIT_N_DAYS | HOLD
    wait_days       INTEGER      NOT NULL DEFAULT 0,
    peak_day        INTEGER,
    peak_price      DECIMAL(10,2),
    profit_gain     DECIMAL(10,2),
    confidence      DECIMAL(5,4),
    model_weights   JSONB,
    explanation     JSONB,
    expires_at      TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX idx_predictions_mandi_commodity_day
    ON price_predictions(mandi_id, commodity, (generated_at::date));

CREATE INDEX idx_predictions_mandi_commodity ON price_predictions(mandi_id, commodity, generated_at DESC);

-- ── SELL RECOMMENDATIONS ──────────────────────────────────────
CREATE TABLE sell_recommendations (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    farmer_id       UUID        NOT NULL REFERENCES farmers(id),
    farmer_crop_id  UUID        REFERENCES farmer_crops(id),
    commodity       VARCHAR(80) NOT NULL,
    recommended_mandi_id UUID  REFERENCES mandis(id),
    decision        VARCHAR(20) NOT NULL,
    wait_days       INTEGER     NOT NULL DEFAULT 0,
    current_price   DECIMAL(10,2),
    expected_price  DECIMAL(10,2),
    profit_gain_per_qtl DECIMAL(10,2),
    storage_cost    DECIMAL(10,2),
    transport_cost  DECIMAL(10,2),
    net_gain        DECIMAL(10,2),
    reasoning       TEXT,
    is_acted_on     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sell_rec_farmer   ON sell_recommendations(farmer_id, created_at DESC);
CREATE INDEX idx_sell_rec_commodity ON sell_recommendations(commodity, created_at DESC);

-- ── ALERTS ────────────────────────────────────────────────────
CREATE TABLE alerts (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    farmer_id   UUID        REFERENCES farmers(id) ON DELETE CASCADE,  -- NULL = broadcast
    type        VARCHAR(40) NOT NULL,   -- PRICE_SPIKE | PRICE_DROP | SELL_WINDOW | WEATHER | MSP
    severity    VARCHAR(10) NOT NULL,   -- URGENT | WARNING | INFO
    commodity   VARCHAR(80),
    mandi_name  VARCHAR(120),
    title       VARCHAR(255) NOT NULL,
    body        TEXT         NOT NULL,
    metadata    JSONB,
    is_read     BOOLEAN      NOT NULL DEFAULT FALSE,
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_alerts_farmer    ON alerts(farmer_id, is_read, created_at DESC);
CREATE INDEX idx_alerts_commodity ON alerts(commodity, created_at DESC);

-- ── CROP RECOMMENDATIONS ──────────────────────────────────────
CREATE TABLE crop_recommendations (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    farmer_id       UUID        NOT NULL REFERENCES farmers(id),
    season          VARCHAR(20) NOT NULL,   -- RABI | KHARIF | ZAID
    soil_type       VARCHAR(60),
    water_source    VARCHAR(60),
    recommendations JSONB       NOT NULL,   -- [{crop, score, profit_range, risk}]
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_crop_rec_farmer ON crop_recommendations(farmer_id, created_at DESC);

-- ── AI CHAT HISTORY ───────────────────────────────────────────
CREATE TABLE chat_sessions (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    farmer_id   UUID        NOT NULL REFERENCES farmers(id) ON DELETE CASCADE,
    language    VARCHAR(10) NOT NULL DEFAULT 'hi',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_msg_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE chat_messages (
    id          BIGSERIAL PRIMARY KEY,
    session_id  UUID        NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role        VARCHAR(15) NOT NULL,    -- user | assistant
    content     TEXT        NOT NULL,
    model_used  VARCHAR(40),
    tokens_used INTEGER,
    latency_ms  INTEGER,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_session  ON chat_messages(session_id, created_at);

-- ── PROFIT SIMULATIONS (stored for history) ───────────────────
CREATE TABLE profit_simulations (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    farmer_id       UUID        NOT NULL REFERENCES farmers(id),
    commodity       VARCHAR(80) NOT NULL,
    mandi_name      VARCHAR(120) NOT NULL,
    quantity_qtl    DECIMAL(10,2) NOT NULL,
    wait_days       INTEGER     NOT NULL,
    current_price   DECIMAL(10,2) NOT NULL,
    predicted_price DECIMAL(10,2),
    storage_cost    DECIMAL(10,2),
    transport_cost  DECIMAL(10,2),
    gross_revenue   DECIMAL(12,2),
    net_revenue     DECIMAL(12,2),
    profit_vs_now   DECIMAL(12,2),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_profit_sim_farmer ON profit_simulations(farmer_id, created_at DESC);

-- ── AUDIT LOG ─────────────────────────────────────────────────
CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    farmer_id   UUID,
    action      VARCHAR(80)  NOT NULL,
    entity_type VARCHAR(40),
    entity_id   VARCHAR(80),
    old_value   JSONB,
    new_value   JSONB,
    ip_address  VARCHAR(45),
    user_agent  VARCHAR(512),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_farmer ON audit_log(farmer_id, created_at DESC);

-- ── TRIGGER: updated_at auto-update ──────────────────────────
CREATE OR REPLACE FUNCTION trigger_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER set_farmers_updated_at
    BEFORE UPDATE ON farmers
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

CREATE TRIGGER set_farmer_crops_updated_at
    BEFORE UPDATE ON farmer_crops
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();
