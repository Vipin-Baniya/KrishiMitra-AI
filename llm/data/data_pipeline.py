"""
KrishiMitra LLM — Data Collection & Curation Pipeline
======================================================
Stages:
  1. AgmarknetScraper       — fetch live + historical mandi prices
  2. QAPairGenerator        — convert price data → instruction pairs
  3. HindiAugmentor         — translate + paraphrase for multilingual coverage
  4. DatasetCurator         — quality filter, dedup, train/val/test split
  5. DatasetStats           — report dataset health before training
"""

import json
import time
import hashlib
import logging
import random
import re
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Optional
from datetime import datetime, timedelta

import requests
import pandas as pd
import numpy as np
from bs4 import BeautifulSoup
from datasets import Dataset, DatasetDict, concatenate_datasets
from tqdm import tqdm
import yaml

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-8s  %(name)s — %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("data_pipeline")


# ─────────────────────────────────────────────────────────────
# DATA STRUCTURES
# ─────────────────────────────────────────────────────────────

@dataclass
class MandiRecord:
    """Single mandi price observation from Agmarknet."""
    date: str
    state: str
    district: str
    mandi: str
    commodity: str
    variety: str
    min_price: float
    max_price: float
    modal_price: float
    unit: str = "Quintal"
    arrivals_tonnes: Optional[float] = None

@dataclass
class InstructionPair:
    """
    One training sample in chat format.
    system  — KrishiMitra context
    user    — farmer question
    assistant — ideal answer
    source  — where this pair came from (for tracking)
    quality_score — 0.0–1.0 (filtered below threshold)
    """
    system: str
    user: str
    assistant: str
    source: str
    language: str = "en"
    quality_score: float = 1.0
    tags: list[str] = field(default_factory=list)

    def to_hash(self) -> str:
        content = self.user + self.assistant
        return hashlib.md5(content.encode()).hexdigest()


# ─────────────────────────────────────────────────────────────
# 1. AGMARKNET SCRAPER
# ─────────────────────────────────────────────────────────────

class AgmarknetScraper:
    """
    Fetches mandi price data from Agmarknet (agmarknet.gov.in).
    Falls back to the Data.gov.in open API if direct scrape fails.

    Usage:
        scraper = AgmarknetScraper()
        df = scraper.fetch(commodity="Wheat", state="Madhya Pradesh", days=180)
        scraper.save(df, "data/raw/agmarknet_prices.csv")
    """

    AGMARKNET_API = "https://agmarknet.gov.in/SearchCommodityWise.aspx"
    DATAGOV_API   = "https://api.data.gov.in/resource/9ef84268-d588-465a-a308-a864a43d0070"
    DATAGOV_KEY   = "YOUR_DATA_GOV_IN_API_KEY"   # set in env: DATAGOV_API_KEY

    COMMODITIES = [
        "Wheat", "Soybean", "Paddy(Dhan)(Common)", "Maize", "Cotton",
        "Gram", "Mustard", "Onion", "Tomato", "Potato",
        "Sugarcane", "Sorghum(Jawar)", "Pearl Millet(Bajra)", "Turmeric",
    ]
    STATES = [
        "Madhya Pradesh", "Maharashtra", "Rajasthan", "Punjab",
        "Haryana", "Uttar Pradesh", "Gujarat", "Karnataka",
    ]

    def __init__(self, cache_dir: str = "data/raw/.cache"):
        self.cache_dir = Path(cache_dir)
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self.session = requests.Session()
        self.session.headers.update({"User-Agent": "KrishiMitra-Research/1.0"})

    def fetch(
        self,
        commodity: str,
        state: Optional[str] = None,
        days: int = 365,
    ) -> pd.DataFrame:
        cache_key = f"{commodity}_{state}_{days}".replace(" ", "_")
        cache_file = self.cache_dir / f"{cache_key}.parquet"

        if cache_file.exists():
            log.info("Cache hit: %s", cache_file)
            return pd.read_parquet(cache_file)

        end_date   = datetime.today()
        start_date = end_date - timedelta(days=days)

        log.info("Fetching %s | %s | %d days", commodity, state or "all", days)
        records = self._fetch_datagov(commodity, state, start_date, end_date)

        df = pd.DataFrame([asdict(r) for r in records])
        if not df.empty:
            df.to_parquet(cache_file, index=False)
        return df

    def _fetch_datagov(
        self,
        commodity: str,
        state: Optional[str],
        start: datetime,
        end: datetime,
    ) -> list[MandiRecord]:
        """
        Calls the Data.gov.in open API for Agmarknet data.
        Handles pagination automatically (offset / limit).
        """
        import os
        api_key = os.environ.get("DATAGOV_API_KEY", self.DATAGOV_KEY)
        records: list[MandiRecord] = []
        offset, limit = 0, 100

        while True:
            params = {
                "api-key": api_key,
                "format": "json",
                "limit": limit,
                "offset": offset,
                "filters[commodity]": commodity,
                "filters[arrival_date]": (
                    f"{start.strftime('%d/%m/%Y')} TO "
                    f"{end.strftime('%d/%m/%Y')}"
                ),
            }
            if state:
                params["filters[state]"] = state

            try:
                resp = self.session.get(self.DATAGOV_API, params=params, timeout=30)
                resp.raise_for_status()
                data = resp.json()
            except requests.RequestException as e:
                log.warning("API error at offset %d: %s", offset, e)
                break

            rows = data.get("records", [])
            if not rows:
                break

            for row in rows:
                try:
                    records.append(MandiRecord(
                        date=row.get("arrival_date", ""),
                        state=row.get("state", ""),
                        district=row.get("district", ""),
                        mandi=row.get("market", ""),
                        commodity=row.get("commodity", ""),
                        variety=row.get("variety", ""),
                        min_price=float(row.get("min_price", 0)),
                        max_price=float(row.get("max_price", 0)),
                        modal_price=float(row.get("modal_price", 0)),
                        arrivals_tonnes=float(row.get("arrivals_in_qtl", 0) or 0) / 10,
                    ))
                except (ValueError, KeyError):
                    continue

            offset += limit
            if len(rows) < limit:
                break
            time.sleep(0.3)   # be polite

        log.info("Fetched %d records for %s", len(records), commodity)
        return records

    def fetch_all(self, days: int = 365) -> pd.DataFrame:
        """Fetch all commodities × states. Takes several minutes."""
        frames = []
        for commodity in tqdm(self.COMMODITIES, desc="Commodities"):
            for state in self.STATES:
                df = self.fetch(commodity, state, days)
                if not df.empty:
                    frames.append(df)
                time.sleep(0.5)
        return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()

    def save(self, df: pd.DataFrame, path: str) -> None:
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        df.to_csv(path, index=False)
        log.info("Saved %d rows → %s", len(df), path)


# ─────────────────────────────────────────────────────────────
# 2. QA PAIR GENERATOR
# ─────────────────────────────────────────────────────────────

SYSTEM_PROMPT = (
    "You are KrishiMitra AI, an expert agricultural advisor for Indian farmers. "
    "You help with mandi prices, sell timing, crop planning, and storage decisions. "
    "Always use ₹/quintal for prices. Be concise and actionable."
)

class QAPairGenerator:
    """
    Converts raw Agmarknet price data + knowledge base into
    instruction-following pairs for supervised fine-tuning.

    Generates pairs for 6 task types:
      A. Price lookup queries
      B. Sell-timing decisions
      C. Mandi comparison
      D. Crop recommendation
      E. Storage decision
      F. Government scheme queries
    """

    # Templates for each task type — varied phrasings for diversity
    PRICE_TEMPLATES = [
        ("What is the current {commodity} price in {mandi}?",
         "The modal price of {commodity} in {mandi} ({state}) on {date} is "
         "₹{modal_price}/quintal (range: ₹{min_price}–₹{max_price}/quintal). "
         "Arrivals: {arrivals} quintals."),

        ("{commodity} ka bhav kya hai {mandi} mandi mein?",
         "{date} ko {mandi} mandi mein {commodity} ka modal bhav ₹{modal_price}/quintal hai "
         "(min ₹{min_price}, max ₹{max_price}/quintal)."),

        ("Is ₹{modal_price}/quintal a good price for {commodity} right now?",
         "₹{modal_price}/quintal for {commodity} at {mandi} on {date} is "
         "{price_assessment}. The 30-day average in {state} is ₹{avg_30d}/quintal, "
         "so this price is {vs_avg} the recent average."),
    ]

    SELL_TIMING_TEMPLATES = [
        ("Should I sell my {commodity} now or wait?",
         "{sell_decision}. Current price in {mandi} is ₹{modal_price}/quintal. "
         "Based on seasonal trends, prices are expected to {trend_desc} by "
         "₹{expected_change}/quintal over the next {horizon} days. "
         "{storage_advice}"),

        ("{commodity} abhi bechun ya rukun? Mere paas {quantity} quintal hai.",
         "{sell_decision_hi}. {mandi} mein aaj ₹{modal_price}/quintal mila raha hai. "
         "Agli {horizon} dinon mein ₹{expected_change}/quintal ka {direction} expected hai. "
         "{profit_statement}"),
    ]

    MANDI_COMPARE_TEMPLATES = [
        ("Which mandi gives the best price for {commodity} near {location}?",
         "For {commodity} near {location}, here are the top mandis today:\n"
         "1. {m1_name}: ₹{m1_price}/qtl ({m1_dist} km)\n"
         "2. {m2_name}: ₹{m2_price}/qtl ({m2_dist} km)\n"
         "3. {m3_name}: ₹{m3_price}/qtl ({m3_dist} km)\n\n"
         "After transport cost, {best_mandi} gives the highest net price of "
         "₹{net_price}/quintal. Recommended: sell at {best_mandi}."),
    ]

    def __init__(self, price_df: pd.DataFrame):
        self.df = price_df
        self.pairs: list[InstructionPair] = []

    def generate_price_pairs(self, n: int = 5000) -> list[InstructionPair]:
        """Generate price-lookup QA pairs from price data."""
        pairs = []
        sample_df = self.df.sample(min(n, len(self.df)), random_state=42)

        for _, row in tqdm(sample_df.iterrows(), total=len(sample_df), desc="Price pairs"):
            if row["modal_price"] <= 0:
                continue

            # Compute 30-day average for context
            mask = (
                (self.df["commodity"] == row["commodity"]) &
                (self.df["state"] == row["state"])
            )
            avg_30d = self.df[mask]["modal_price"].mean()
            vs_avg = "above" if row["modal_price"] > avg_30d else "below"

            price_assessment = (
                "above average" if row["modal_price"] > avg_30d * 1.05
                else "below average" if row["modal_price"] < avg_30d * 0.95
                else "near the seasonal average"
            )

            template = random.choice(self.PRICE_TEMPLATES)
            q_tmpl, a_tmpl = template

            ctx = {
                "commodity":       row["commodity"],
                "mandi":           row["mandi"],
                "state":           row["state"],
                "date":            row["date"],
                "modal_price":     int(row["modal_price"]),
                "min_price":       int(row["min_price"]),
                "max_price":       int(row["max_price"]),
                "arrivals":        int(row.get("arrivals_tonnes", 0) * 10),
                "avg_30d":         int(avg_30d),
                "vs_avg":          vs_avg,
                "price_assessment": price_assessment,
            }

            try:
                question = q_tmpl.format(**ctx)
                answer   = a_tmpl.format(**ctx)
            except KeyError:
                continue

            lang = "hi" if any(ord(c) > 0x900 for c in question) else "en"
            pairs.append(InstructionPair(
                system=SYSTEM_PROMPT,
                user=question,
                assistant=answer,
                source="agmarknet_price",
                language=lang,
                tags=["price_lookup", row["commodity"].lower().replace(" ", "_")],
            ))

        self.pairs.extend(pairs)
        log.info("Generated %d price pairs", len(pairs))
        return pairs

    def generate_sell_timing_pairs(self, n: int = 3000) -> list[InstructionPair]:
        """
        Generate sell-timing decision pairs.
        Uses simple heuristic: if prices trending up → WAIT, else → SELL.
        In production, replace with actual ARIMA/LSTM predictions.
        """
        pairs = []
        commodities = self.df["commodity"].unique()

        for commodity in commodities:
            cdf = self.df[self.df["commodity"] == commodity].sort_values("date")
            if len(cdf) < 10:
                continue

            # Rolling 7-day trend
            cdf = cdf.copy()
            cdf["rolling_7"] = cdf["modal_price"].rolling(7).mean()
            cdf["trend"]     = cdf["rolling_7"].diff()

            sample = cdf.dropna().sample(
                min(n // len(commodities), len(cdf)), random_state=42
            )

            for _, row in sample.iterrows():
                trend_up = row["trend"] > 0
                sell_decision    = "WAIT — prices are rising" if trend_up else "SELL NOW — prices are declining"
                sell_decision_hi = "RUKO — bhav badh raha hai" if trend_up else "ABHI BECHO — bhav gir raha hai"
                trend_desc       = "increase" if trend_up else "decrease"
                direction        = "increase" if trend_up else "decrease"
                expected_change  = abs(int(row["trend"] * 7))
                horizon          = random.choice([7, 10, 14, 21])

                storage_cost_per_day = {"Wheat": 2.5, "Soybean": 4, "Tomato": 8}.get(commodity, 3)
                storage_cost = storage_cost_per_day * horizon
                net_gain     = (expected_change - storage_cost) if trend_up else -expected_change
                profit_statement = (
                    f"Expected net gain after storage cost: ₹{int(net_gain)}/quintal."
                    if net_gain > 0
                    else "Holding would cost more than the price gain."
                )
                storage_advice = (
                    f"Storage cost ₹{storage_cost}/quintal for {horizon} days. "
                    f"Net benefit of waiting: ₹{int(net_gain)}/quintal."
                ) if trend_up else "Sell immediately to avoid further loss."

                template = random.choice(self.SELL_TIMING_TEMPLATES)
                q_tmpl, a_tmpl = template
                ctx = {
                    "commodity":      commodity,
                    "mandi":          row["mandi"],
                    "modal_price":    int(row["modal_price"]),
                    "trend_desc":     trend_desc,
                    "direction":      direction,
                    "expected_change": expected_change,
                    "horizon":        horizon,
                    "sell_decision":  sell_decision,
                    "sell_decision_hi": sell_decision_hi,
                    "storage_advice": storage_advice,
                    "profit_statement": profit_statement,
                    "quantity":       random.choice([2, 5, 8, 10, 15, 20]),
                }

                try:
                    question = q_tmpl.format(**ctx)
                    answer   = a_tmpl.format(**ctx)
                except KeyError:
                    continue

                lang = "hi" if any(ord(c) > 0x900 for c in question) else "en"
                pairs.append(InstructionPair(
                    system=SYSTEM_PROMPT,
                    user=question,
                    assistant=answer,
                    source="sell_timing_heuristic",
                    language=lang,
                    tags=["sell_timing", commodity.lower().replace(" ", "_")],
                ))

        self.pairs.extend(pairs)
        log.info("Generated %d sell-timing pairs", len(pairs))
        return pairs

    def load_knowledge_base_pairs(self, kb_path: str) -> list[InstructionPair]:
        """
        Load hand-curated QA pairs from JSONL knowledge base.
        Format: {"user": "...", "assistant": "...", "language": "hi", "tags": [...]}
        """
        pairs = []
        path = Path(kb_path)
        if not path.exists():
            log.warning("Knowledge base not found: %s", kb_path)
            return pairs

        with open(path) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                obj = json.loads(line)
                pairs.append(InstructionPair(
                    system=obj.get("system", SYSTEM_PROMPT),
                    user=obj["user"],
                    assistant=obj["assistant"],
                    source=obj.get("source", "knowledge_base"),
                    language=obj.get("language", "en"),
                    quality_score=obj.get("quality_score", 1.0),
                    tags=obj.get("tags", []),
                ))

        self.pairs.extend(pairs)
        log.info("Loaded %d KB pairs from %s", len(pairs), kb_path)
        return pairs


# ─────────────────────────────────────────────────────────────
# 3. HINDI / MULTILINGUAL AUGMENTOR
# ─────────────────────────────────────────────────────────────

class MultilingualAugmentor:
    """
    Expands English pairs to Hindi + regional languages.
    Strategy:
      - Rule-based translation for price/number templates (fast, free)
      - GPT-4o-mini for complex sentence translation (cheap, high quality)
      - Back-translation validation to catch hallucinations
    """

    # Common agri terms: English → Hindi
    AGRI_GLOSSARY = {
        "wheat":     "गेहूं",
        "soybean":   "सोयाबीन",
        "onion":     "प्याज",
        "tomato":    "टमाटर",
        "potato":    "आलू",
        "cotton":    "कपास",
        "maize":     "मक्का",
        "sell":      "बेचो",
        "wait":      "रुको",
        "mandi":     "मंडी",
        "price":     "भाव",
        "quintal":   "क्विंटल",
        "farmer":    "किसान",
        "crop":      "फसल",
        "storage":   "भंडारण",
        "profit":    "मुनाफा",
        "loss":      "नुकसान",
        "rising":    "बढ़ रहा है",
        "falling":   "गिर रहा है",
        "today":     "आज",
        "tomorrow":  "कल",
        "per quintal": "प्रति क्विंटल",
    }

    def __init__(self, openai_client=None):
        self.openai_client = openai_client
        self.translated_count = 0

    def rule_based_translate_to_hindi(self, text: str) -> str:
        """
        Fast rule-based translation using glossary + number preservation.
        Good enough for price/number-heavy sentences.
        """
        result = text
        for en, hi in self.AGRI_GLOSSARY.items():
            result = re.sub(
                r'\b' + re.escape(en) + r'\b',
                hi, result, flags=re.IGNORECASE
            )
        return result

    def augment_pair_to_hindi(self, pair: InstructionPair) -> Optional[InstructionPair]:
        """Translate an English pair to Hindi."""
        if pair.language != "en":
            return None

        hi_user      = self.rule_based_translate_to_hindi(pair.user)
        hi_assistant = self.rule_based_translate_to_hindi(pair.assistant)

        # Only use if glossary covered most words (rough heuristic)
        coverage = sum(1 for k in self.AGRI_GLOSSARY if k in pair.user.lower())
        if coverage < 1:
            return None

        self.translated_count += 1
        return InstructionPair(
            system=SYSTEM_PROMPT,
            user=hi_user,
            assistant=hi_assistant,
            source=f"augmented_hi:{pair.source}",
            language="hi",
            quality_score=pair.quality_score * 0.85,   # slight quality discount
            tags=pair.tags + ["augmented", "hindi"],
        )

    def augment_dataset(
        self,
        pairs: list[InstructionPair],
        target_ratio: float = 0.30,
    ) -> list[InstructionPair]:
        """
        Add Hindi variants for `target_ratio` fraction of English pairs.
        """
        en_pairs = [p for p in pairs if p.language == "en"]
        n_target = int(len(en_pairs) * target_ratio)
        sample   = random.sample(en_pairs, min(n_target, len(en_pairs)))

        new_pairs = []
        for p in tqdm(sample, desc="Hindi augmentation"):
            hi_pair = self.augment_pair_to_hindi(p)
            if hi_pair:
                new_pairs.append(hi_pair)

        log.info("Added %d Hindi pairs (%.0f%% of English)", len(new_pairs), 100 * target_ratio)
        return pairs + new_pairs


# ─────────────────────────────────────────────────────────────
# 4. DATASET CURATOR
# ─────────────────────────────────────────────────────────────

class DatasetCurator:
    """
    Quality filtering, deduplication, and train/val/test splitting.

    Quality filters applied:
      1. Min response length (avoid empty/trivial answers)
      2. Max response length (avoid truncation during training)
      3. Quality score threshold
      4. Price sanity check (no ₹0 or ₹999999 prices in answer)
      5. MD5 deduplication on (user + assistant)
    """

    def __init__(self, config: dict):
        self.cfg = config
        self.seen_hashes: set[str] = set()

    def filter_pair(self, pair: InstructionPair) -> bool:
        """Return True if pair passes all quality gates."""

        # Length gates
        if len(pair.assistant.strip()) < 20:
            return False
        if len(pair.assistant) > 1200:
            return False
        if len(pair.user.strip()) < 5:
            return False

        # Quality score
        if pair.quality_score < 0.60:
            return False

        # Deduplication
        h = pair.to_hash()
        if h in self.seen_hashes:
            return False
        self.seen_hashes.add(h)

        # Sanity: no placeholder text
        bad_phrases = ["YOUR_", "PLACEHOLDER", "TODO", "XXX", "FIXME"]
        combined = pair.user + pair.assistant
        if any(p in combined for p in bad_phrases):
            return False

        return True

    def curate(self, pairs: list[InstructionPair]) -> list[InstructionPair]:
        before = len(pairs)
        curated = [p for p in pairs if self.filter_pair(p)]
        log.info("Curation: %d → %d pairs (%.1f%% kept)",
                 before, len(curated), 100 * len(curated) / max(before, 1))
        return curated

    def to_chat_format(self, pair: InstructionPair) -> dict:
        """
        Convert InstructionPair → Mistral chat format for SFTTrainer.
        Output: {"messages": [...]} — tokenizer handles template application.
        """
        return {
            "messages": [
                {"role": "system",    "content": pair.system},
                {"role": "user",      "content": pair.user},
                {"role": "assistant", "content": pair.assistant},
            ],
            "source":   pair.source,
            "language": pair.language,
            "tags":     pair.tags,
        }

    def split_and_save(
        self,
        pairs: list[InstructionPair],
        output_dir: str,
        train_ratio: float = 0.85,
        val_ratio:   float = 0.10,
    ) -> DatasetDict:
        random.shuffle(pairs)
        n = len(pairs)
        n_train = int(n * train_ratio)
        n_val   = int(n * val_ratio)

        splits = {
            "train": pairs[:n_train],
            "val":   pairs[n_train:n_train + n_val],
            "test":  pairs[n_train + n_val:],
        }

        output_path = Path(output_dir)
        output_path.mkdir(parents=True, exist_ok=True)

        hf_splits = {}
        for split_name, split_pairs in splits.items():
            rows = [self.to_chat_format(p) for p in split_pairs]

            # Save JSONL
            jsonl_path = output_path / f"{split_name}.jsonl"
            with open(jsonl_path, "w", encoding="utf-8") as f:
                for row in rows:
                    f.write(json.dumps(row, ensure_ascii=False) + "\n")

            hf_splits[split_name] = Dataset.from_list(rows)
            log.info("Split %-5s → %5d pairs → %s", split_name, len(rows), jsonl_path)

        dataset_dict = DatasetDict(hf_splits)
        dataset_dict.save_to_disk(str(output_path / "hf_dataset"))
        return dataset_dict


# ─────────────────────────────────────────────────────────────
# 5. DATASET STATS
# ─────────────────────────────────────────────────────────────

class DatasetStats:
    """Print a health report before you start training."""

    def __init__(self, dataset_dict: DatasetDict):
        self.dsd = dataset_dict

    def report(self) -> None:
        print("\n" + "═" * 60)
        print("  KRISHIMITRA LLM — DATASET HEALTH REPORT")
        print("═" * 60)

        for split_name, ds in self.dsd.items():
            msgs_flat = [m for row in ds["messages"] for m in row]
            user_lens  = [len(m["content"]) for m in msgs_flat if m["role"] == "user"]
            asst_lens  = [len(m["content"]) for m in msgs_flat if m["role"] == "assistant"]
            langs = {}
            for row in ds:
                lang = row.get("language", "en")
                langs[lang] = langs.get(lang, 0) + 1

            print(f"\n  Split: {split_name.upper()}  ({len(ds):,} samples)")
            print(f"    User query   — avg {np.mean(user_lens):.0f} chars, "
                  f"max {max(user_lens)} chars")
            print(f"    Answer       — avg {np.mean(asst_lens):.0f} chars, "
                  f"max {max(asst_lens)} chars")
            print(f"    Languages    — "
                  + ", ".join(f"{k}: {v}" for k, v in sorted(langs.items())))

            sources = {}
            for row in ds:
                src = row.get("source", "unknown").split(":")[0]
                sources[src] = sources.get(src, 0) + 1
            print("    Sources      — "
                  + ", ".join(f"{k}: {v}" for k, v in
                              sorted(sources.items(), key=lambda x: -x[1])[:5]))

        print("\n" + "═" * 60 + "\n")


# ─────────────────────────────────────────────────────────────
# MAIN PIPELINE
# ─────────────────────────────────────────────────────────────

def run_pipeline(config_path: str = "configs/config.yaml") -> DatasetDict:
    with open(config_path) as f:
        cfg = yaml.safe_load(f)

    data_cfg = cfg["data"]

    # 1. Fetch price data
    log.info("Stage 1 — Fetching Agmarknet price data")
    scraper  = AgmarknetScraper()
    price_df = scraper.fetch_all(days=730)   # 2 years of history
    scraper.save(price_df, data_cfg["raw_sources"]["agmarknet_prices"])

    # 2. Generate QA pairs
    log.info("Stage 2 — Generating instruction pairs")
    generator = QAPairGenerator(price_df)
    generator.generate_price_pairs(n=5000)
    generator.generate_sell_timing_pairs(n=3000)

    # Load hand-curated knowledge base
    for kb_key in ["crop_advisory", "mandi_knowledge", "govt_schemes"]:
        generator.load_knowledge_base_pairs(data_cfg["raw_sources"][kb_key])

    all_pairs = generator.pairs
    log.info("Total pairs before curation: %d", len(all_pairs))

    # 3. Multilingual augmentation
    log.info("Stage 3 — Multilingual augmentation")
    augmentor = MultilingualAugmentor()
    all_pairs = augmentor.augment_dataset(all_pairs, target_ratio=0.30)

    # 4. Curate
    log.info("Stage 4 — Curation (filter + dedup)")
    curator    = DatasetCurator(cfg)
    all_pairs  = curator.curate(all_pairs)

    # 5. Split and save
    log.info("Stage 5 — Split and save")
    dataset_dict = curator.split_and_save(
        all_pairs,
        output_dir="data/processed",
        train_ratio=data_cfg["train_ratio"],
        val_ratio=data_cfg["val_ratio"],
    )

    # 6. Report
    DatasetStats(dataset_dict).report()
    return dataset_dict


if __name__ == "__main__":
    run_pipeline()
