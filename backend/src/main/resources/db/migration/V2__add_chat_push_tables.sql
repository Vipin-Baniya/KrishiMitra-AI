-- ================================================================
--  KrishiMitra — V2 Migration
--  Adds:
--    1. push_token / push_platform / sms_opt_in on farmers
--    2. chat_sessions table
--    3. chat_messages table
--    4. profit_simulations table
--
--  Idempotent: uses IF NOT EXISTS / IF NOT EXISTS guards
-- ================================================================

-- ── 1. Push notification columns on farmers ───────────────────
ALTER TABLE farmers
    ADD COLUMN IF NOT EXISTS push_token    VARCHAR(512),
    ADD COLUMN IF NOT EXISTS push_platform VARCHAR(10),
    ADD COLUMN IF NOT EXISTS sms_opt_in    BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_farmers_push_token
    ON farmers (push_token) WHERE push_token IS NOT NULL;

-- ── 2. Chat sessions ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat_sessions (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    farmer_id   UUID        NOT NULL REFERENCES farmers(id) ON DELETE CASCADE,
    language    VARCHAR(10) NOT NULL DEFAULT 'hi',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_msg_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chat_sessions_farmer_last
    ON chat_sessions (farmer_id, last_msg_at DESC);

-- ── 3. Chat messages ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat_messages (
    id          BIGSERIAL   PRIMARY KEY,
    session_id  UUID        NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role        VARCHAR(15) NOT NULL CHECK (role IN ('user','assistant','system')),
    content     TEXT        NOT NULL,
    model_used  VARCHAR(40),
    tokens_used INTEGER,
    latency_ms  INTEGER,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chat_messages_session_created
    ON chat_messages (session_id, created_at ASC);

-- ── 4. Profit simulations ─────────────────────────────────────
CREATE TABLE IF NOT EXISTS profit_simulations (
    id              UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    farmer_id       UUID           NOT NULL REFERENCES farmers(id) ON DELETE CASCADE,
    commodity       VARCHAR(80)    NOT NULL,
    mandi_name      VARCHAR(120)   NOT NULL,
    quantity_qtl    NUMERIC(10,2)  NOT NULL,
    wait_days       INTEGER        NOT NULL,
    current_price   NUMERIC(10,2)  NOT NULL,
    predicted_price NUMERIC(10,2),
    storage_cost    NUMERIC(10,2),
    transport_cost  NUMERIC(10,2),
    gross_revenue   NUMERIC(12,2),
    net_revenue     NUMERIC(12,2),
    profit_vs_now   NUMERIC(12,2),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_profit_sim_farmer_created
    ON profit_simulations (farmer_id, created_at DESC);

-- ── 5. Back-fill updated_at trigger for new tables ────────────
-- (reuses the function created in V1)

-- ── 6. Add prev_modal_price to mandi_prices (for MandiPriceMapper trend calc) ──
ALTER TABLE mandi_prices
    ADD COLUMN IF NOT EXISTS prev_modal_price NUMERIC(10,2);


-- ── 5. Add updated_at triggers for new tables ─────────────────
-- Uses trigger_set_updated_at() function defined in V1
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_trigger WHERE tgname = 'set_chat_sessions_updated_at'
  ) THEN
    EXECUTE '
      CREATE TRIGGER set_chat_sessions_updated_at
      BEFORE UPDATE ON chat_sessions
      FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at()
    ';
  END IF;
END;
$$;
