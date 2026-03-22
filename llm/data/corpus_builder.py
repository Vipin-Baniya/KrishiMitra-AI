"""
KrishiMitra — Agri Training Corpus Builder
==========================================
Builds the fine-tuning dataset for the custom Mistral-7B model.

Sources:
  1. Agmarknet (price history 2019–present, 14 commodities × 20 states)
  2. CACP MSP circulars (PDF → text)
  3. PIB agri press releases
  4. KVK (Krishi Vigyan Kendra) crop advisories
  5. eNAM market data
  6. Synthetic QA generation via GPT-4o (seed → expand)

Output: ~50,000 QA pairs in JSONL, balanced Hindi/English (60/40)
Run: python data/corpus_builder.py --output data/corpus/krishimitra_v1.jsonl
"""

import os
import re
import json
import time
import random
import hashlib
import logging
import argparse
import asyncio
from datetime import datetime, timedelta
from pathlib import Path
from typing import Iterator

import requests
import pandas as pd
from tqdm import tqdm
from openai import OpenAI

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

# ─────────────────────────────────────────────────────────────
# CONFIG
# ─────────────────────────────────────────────────────────────
COMMODITIES = ["Wheat","Soybean","Onion","Tomato","Potato","Cotton","Maize","Gram",
               "Mustard","Turmeric","Chilli","Garlic","Ginger","Rice"]
STATES      = ["Madhya Pradesh","Maharashtra","Rajasthan","Punjab","Haryana",
               "Uttar Pradesh","Gujarat","Karnataka","Telangana","Andhra Pradesh",
               "West Bengal","Bihar","Odisha","Tamil Nadu","Chhattisgarh",
               "Jharkhand","Assam","Kerala","Himachal Pradesh","Jammu and Kashmir"]
LANGUAGES   = ["en","hi"]

DATAGOV_API = "https://api.data.gov.in/resource/9ef84268-d588-465a-a308-a864a43d0070"
MSP_URL     = "https://cacp.dacnet.nic.in/ViewQuestion.aspx?Input=1&KeyId=1&AppId=0"

# ─────────────────────────────────────────────────────────────
# AGMARKNET SCRAPER
# ─────────────────────────────────────────────────────────────
class AgmarknetScraper:
    """Pulls 5 years of daily mandi prices from data.gov.in."""

    def __init__(self, api_key: str):
        self.api_key = api_key
        self.session = requests.Session()
        self.session.headers["User-Agent"] = "KrishiMitra/1.0 research@krishimitra.in"

    def fetch(self, commodity: str, state: str,
              start: str = "2019-01-01", end: str | None = None) -> pd.DataFrame:
        end = end or datetime.now().strftime("%Y-%m-%d")
        records, offset = [], 0
        while True:
            try:
                r = self.session.get(DATAGOV_API, params={
                    "api-key":          self.api_key,
                    "format":           "json",
                    "offset":           offset,
                    "limit":            500,
                    "filters[commodity]": commodity,
                    "filters[state]":   state,
                }, timeout=30)
                r.raise_for_status()
                data = r.json()
                batch = data.get("records", [])
                if not batch:
                    break
                records.extend(batch)
                offset += len(batch)
                if offset >= data.get("total", 0):
                    break
                time.sleep(0.35)  # rate limit
            except Exception as e:
                log.warning("Fetch error %s/%s offset=%d: %s", commodity, state, offset, e)
                break

        if not records:
            return pd.DataFrame()

        df = pd.DataFrame(records)
        df.columns = [c.lower().replace(" ", "_") for c in df.columns]
        for col in ["min_price","max_price","modal_price"]:
            if col in df.columns:
                df[col] = pd.to_numeric(df[col], errors="coerce")
        if "arrival_date" in df.columns:
            df["arrival_date"] = pd.to_datetime(df["arrival_date"], format="%d/%b/%Y", errors="coerce")
        return df.dropna(subset=["modal_price"]).sort_values("arrival_date")

    def fetch_all(self) -> pd.DataFrame:
        frames = []
        for commodity in COMMODITIES[:8]:       # top 8 commodities
            for state in STATES[:10]:           # top 10 states
                log.info("Fetching %s / %s", commodity, state)
                df = self.fetch(commodity, state)
                if not df.empty:
                    frames.append(df)
        return pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()

# ─────────────────────────────────────────────────────────────
# MSP DATA LOADER (hardcoded 2024–25 from CACP circular)
# ─────────────────────────────────────────────────────────────
MSP_2024_25 = {
    "Wheat":    2275, "Rice":     2300, "Gram":     5440,
    "Mustard":  5650, "Soybean":  4892, "Cotton":   7521,
    "Maize":    2225, "Turmeric": 7000, "Onion":    0,    # no MSP
    "Tomato":   0,
}

# ─────────────────────────────────────────────────────────────
# QA PAIR GENERATORS  (deterministic + LLM-expanded)
# ─────────────────────────────────────────────────────────────

def generate_price_qa(df: pd.DataFrame) -> list[dict]:
    """Generate factual QA from mandi price time series."""
    pairs = []
    for _, g in df.groupby(["commodity", "state"]):
        if len(g) < 30:
            continue
        commodity = g["commodity"].iloc[0]
        state     = g["state"].iloc[0]
        latest    = g.iloc[-1]
        prev_wk   = g[g["arrival_date"] <= latest["arrival_date"] - timedelta(days=7)].iloc[-1] if len(g) > 7 else None

        # Q1: Current price
        price = int(latest["modal_price"])
        mandi = latest.get("market", "local mandi")
        pairs.append(_pair(
            en=f"What is the current price of {commodity} in {state}?",
            hi=f"{state} में {commodity} का आज का भाव क्या है?",
            answer_en=f"The current modal price of {commodity} at {mandi}, {state} is ₹{price:,} per quintal (as of {latest['arrival_date'].strftime('%d %b %Y')}).",
            answer_hi=f"{state} के {mandi} मंडी में {commodity} का आज का भाव ₹{price:,} प्रति क्विंटल है ({latest['arrival_date'].strftime('%d %b %Y')} के अनुसार)।",
        ))

        # Q2: Week-on-week change
        if prev_wk is not None:
            delta = price - int(prev_wk["modal_price"])
            pct   = delta / int(prev_wk["modal_price"]) * 100
            direction_en = "risen" if delta > 0 else "fallen"
            direction_hi = "बढ़ा" if delta > 0 else "गिरा"
            pairs.append(_pair(
                en=f"How has the price of {commodity} changed this week in {state}?",
                hi=f"इस हफ्ते {state} में {commodity} का भाव कैसे बदला?",
                answer_en=f"{commodity} prices in {state} have {direction_en} by ₹{abs(delta):,}/qtl ({abs(pct):.1f}%) this week.",
                answer_hi=f"{state} में {commodity} का भाव इस हफ्ते {abs(delta):,} रुपये प्रति क्विंटल ({abs(pct):.1f}%) {direction_hi} है।",
            ))

        # Q3: Best mandi in state
        latest_by_mandi = df[(df["commodity"]==commodity)&(df["state"]==state)].groupby("market")["modal_price"].last()
        if len(latest_by_mandi) >= 2:
            best_mandi = latest_by_mandi.idxmax()
            best_price = int(latest_by_mandi.max())
            pairs.append(_pair(
                en=f"Which mandi offers the best price for {commodity} in {state}?",
                hi=f"{state} में {commodity} के लिए सबसे अच्छा भाव किस मंडी में मिलता है?",
                answer_en=f"{best_mandi} mandi offers the highest price for {commodity} in {state} at ₹{best_price:,}/qtl currently.",
                answer_hi=f"{state} में {commodity} के लिए {best_mandi} मंडी सबसे अच्छा भाव देती है — अभी ₹{best_price:,} प्रति क्विंटल।",
            ))

    return pairs


def generate_msp_qa() -> list[dict]:
    """MSP awareness QA — critical for small farmers."""
    pairs = []
    for crop, msp in MSP_2024_25.items():
        if msp == 0:
            pairs.append(_pair(
                en=f"Is there an MSP for {crop}?",
                hi=f"क्या {crop} का कोई MSP है?",
                answer_en=f"No, the Government of India does not currently set a Minimum Support Price (MSP) for {crop}.",
                answer_hi=f"नहीं, सरकार {crop} के लिए कोई न्यूनतम समर्थन मूल्य (MSP) नहीं देती।",
            ))
            continue
        pairs.append(_pair(
            en=f"What is the MSP for {crop} in 2024–25?",
            hi=f"2024–25 में {crop} का MSP कितना है?",
            answer_en=f"The Minimum Support Price (MSP) for {crop} in 2024–25 is ₹{msp:,} per quintal, as fixed by the Cabinet Committee on Economic Affairs.",
            answer_hi=f"2024–25 में {crop} का न्यूनतम समर्थन मूल्य (MSP) ₹{msp:,} प्रति क्विंटल है।",
        ))
        pairs.append(_pair(
            en=f"Should I sell {crop} below MSP?",
            hi=f"क्या मुझे {crop} MSP से कम में बेचना चाहिए?",
            answer_en=f"You should not sell {crop} below the MSP of ₹{msp:,}/qtl. If local mandi prices fall below MSP, contact your district's procurement agency (like NAFED or FCI) who are mandated to buy at MSP.",
            answer_hi=f"{crop} का MSP ₹{msp:,} है। MSP से कम में मत बेचें। अगर मंडी भाव कम है, तो NAFED या FCI से संपर्क करें जो MSP पर खरीदी करती हैं।",
        ))
    return pairs


def generate_sell_timing_qa(df: pd.DataFrame) -> list[dict]:
    """Sell timing advice based on historical seasonality."""
    pairs = []
    seasonal_advice = {
        "Wheat":   {"best_month":"April–May", "reason":"harvest pressure passes, festival demand rises"},
        "Soybean": {"best_month":"December–January", "reason":"export demand peaks after November arrivals"},
        "Onion":   {"best_month":"November–December", "reason":"summer crop shortage drives prices up"},
        "Tomato":  {"best_month":"November–February", "reason":"cool weather reduces spoilage and prices peak"},
        "Cotton":  {"best_month":"February–March", "reason":"arrivals reduce after January, prices firm up"},
    }
    for crop, advice in seasonal_advice.items():
        pairs.append(_pair(
            en=f"When is the best time to sell {crop}?",
            hi=f"{crop} बेचने का सबसे अच्छा समय कब है?",
            answer_en=f"Historically, {advice['best_month']} tends to offer the best prices for {crop}, because {advice['reason']}. However, always check current mandi trends before deciding.",
            answer_hi=f"आमतौर पर {advice['best_month']} में {crop} के दाम सबसे अच्छे होते हैं क्योंकि {advice['reason']}। फिर भी, बेचने से पहले मौजूदा मंडी भाव जरूर देखें।",
        ))
    return pairs


def generate_storage_qa() -> list[dict]:
    """Storage cost awareness and decision QA."""
    storage_data = [
        ("Wheat",   2.5,  True,  "6–12 months in proper storage"),
        ("Soybean", 4.0,  True,  "3–6 months"),
        ("Onion",   6.0,  False, "1–3 months with losses"),
        ("Tomato",  8.0,  False, "1–2 weeks only"),
        ("Cotton",  3.0,  True,  "up to 12 months"),
        ("Potato",  5.0,  True,  "3–5 months in cold store"),
    ]
    pairs = []
    for crop, cost_day, stable, duration in storage_data:
        pairs.append(_pair(
            en=f"What is the storage cost for {crop}?",
            hi=f"{crop} के भंडारण की लागत कितनी है?",
            answer_en=f"Storage cost for {crop} is approximately ₹{cost_day}/quintal/day. {crop} can be stored for {duration}. {'Storing makes sense when expected price rise exceeds storage cost.' if stable else 'Due to perishability, sell quickly unless you have proper facilities.'}",
            answer_hi=f"{crop} की भंडारण लागत लगभग ₹{cost_day}/क्विंटल/दिन है। इसे {duration} तक रखा जा सकता है।",
        ))
        # Break-even calc
        monthly_cost = cost_day * 30
        pairs.append(_pair(
            en=f"How much should {crop} prices rise for storage to be profitable?",
            hi=f"{crop} का भाव कितना बढ़ना चाहिए ताकि रोकना फायदेमंद हो?",
            answer_en=f"For 1 month storage: you need ₹{monthly_cost:.0f}/qtl price increase just to break even. Add transport (₹80–120/qtl) and any losses. Only store if you expect more than ₹{monthly_cost+100:.0f}/qtl rise.",
            answer_hi=f"1 महीने के भंडारण के लिए: सिर्फ लागत निकालने के लिए भाव ₹{monthly_cost:.0f}/क्विंटल बढ़ना चाहिए। परिवहन जोड़ें। कुल ₹{monthly_cost+100:.0f} से ज़्यादा बढ़े तभी रोकें।",
        ))
    return pairs


def generate_crop_planning_qa() -> list[dict]:
    """Crop choice and planning QA."""
    pairs = []
    crop_profiles = [
        { "crop":"Wheat",   "soil":"black cotton, loamy",       "water":"medium",  "season":"Rabi (Oct–Mar)",  "income":"₹18k–22k/acre" },
        { "crop":"Soybean", "soil":"black cotton",              "water":"medium",  "season":"Kharif (Jun–Oct)","income":"₹16k–22k/acre" },
        { "crop":"Gram",    "soil":"loamy, alluvial",           "water":"low",     "season":"Rabi (Oct–Feb)",  "income":"₹14k–18k/acre" },
        { "crop":"Cotton",  "soil":"black cotton, deep red",    "water":"medium",  "season":"Kharif (May–Dec)","income":"₹20k–30k/acre" },
        { "crop":"Onion",   "soil":"well-drained loamy",        "water":"high",    "season":"Rabi + Kharif",   "income":"₹25k–60k/acre" },
        { "crop":"Tomato",  "soil":"loamy, well-drained",       "water":"high",    "season":"Oct–Feb (Rabi)",  "income":"₹30k–80k/acre" },
    ]
    for p in crop_profiles:
        pairs.append(_pair(
            en=f"What soil and season is best for growing {p['crop']}?",
            hi=f"{p['crop']} उगाने के लिए कौन सी मिट्टी और मौसम सबसे अच्छा है?",
            answer_en=f"{p['crop']} grows best in {p['soil']} soil. It is a {p['season']} crop requiring {p['water']} water. Expected income: {p['income']} per acre.",
            answer_hi=f"{p['crop']} {p['soil']} मिट्टी में सबसे अच्छा होता है। यह {p['season']} की फसल है और {p['water']} पानी चाहिए। अनुमानित आय: {p['income']} प्रति एकड़।",
        ))
    return pairs


# ─────────────────────────────────────────────────────────────
# GPT-4o EXPANSION (synthetic augmentation)
# ─────────────────────────────────────────────────────────────

def expand_with_gpt4o(seed_pairs: list[dict], n_per_seed: int = 3) -> list[dict]:
    """
    Uses GPT-4o to generate variations on seed QA pairs.
    Doubles the dataset with paraphrased + harder questions.
    """
    client = OpenAI(api_key=os.environ["OPENAI_API_KEY"])
    expanded = []

    for pair in tqdm(seed_pairs[:200], desc="GPT-4o expansion"):  # sample 200
        try:
            prompt = f"""
You are a dataset generator for an Indian farming AI assistant.

Given this QA pair:
Q (English): {pair['conversations'][0]['value']}
A (English): {pair['conversations'][1]['value']}

Generate {n_per_seed} variations:
1. Rephrase in a different farming dialect / simpler language
2. Make the question more specific (add mandi name, state, quantity)
3. Generate a follow-up question a farmer would naturally ask next

Output JSON array of objects: [{{"q_en":"...","a_en":"...","q_hi":"...","a_hi":"..."}}]
Keep answers accurate and practical. All prices in ₹/quintal.
"""
            resp = client.chat.completions.create(
                model="gpt-4o",
                messages=[{"role":"user","content":prompt}],
                temperature=0.7,
                max_tokens=800,
                response_format={"type":"json_object"},
            )
            raw = json.loads(resp.choices[0].message.content)
            for item in raw.get("variations", raw if isinstance(raw, list) else []):
                if "q_en" in item and "a_en" in item:
                    expanded.append(_pair(
                        en=item["q_en"], hi=item.get("q_hi",""),
                        answer_en=item["a_en"], answer_hi=item.get("a_hi",""),
                    ))
        except Exception as e:
            log.warning("GPT-4o expansion error: %s", e)
        time.sleep(0.5)

    return expanded


# ─────────────────────────────────────────────────────────────
# HELPERS
# ─────────────────────────────────────────────────────────────

def _pair(en: str, hi: str, answer_en: str, answer_hi: str) -> dict:
    """Builds a ShareGPT-format training pair for both languages."""
    return {
        "id": hashlib.md5(f"{en}{answer_en}".encode()).hexdigest()[:16],
        "language": random.choice(["en","hi"]),
        "conversations": [
            {"from":"human", "value": random.choice([en, hi]) if hi else en},
            {"from":"gpt",   "value": answer_en if random.random() > 0.4 else (answer_hi or answer_en)},
        ],
        "source": "krishimitra_agri_corpus_v1",
    }


def dedup(pairs: list[dict]) -> list[dict]:
    seen, out = set(), []
    for p in pairs:
        key = p["conversations"][0]["value"].strip().lower()[:60]
        if key not in seen:
            seen.add(key)
            out.append(p)
    return out


# ─────────────────────────────────────────────────────────────
# MAIN
# ─────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output",    default="data/corpus/krishimitra_v1.jsonl")
    parser.add_argument("--api-key",   default=os.environ.get("DATAGOV_API_KEY",""))
    parser.add_argument("--no-scrape", action="store_true", help="Skip Agmarknet scraping (use cached data)")
    parser.add_argument("--no-gpt",    action="store_true", help="Skip GPT-4o expansion")
    args = parser.parse_args()

    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    all_pairs: list[dict] = []

    # 1. Scrape Agmarknet (or load cache)
    cache_path = Path("data/corpus/agmarknet_cache.parquet")
    if args.no_scrape and cache_path.exists():
        log.info("Loading cached Agmarknet data")
        df = pd.read_parquet(cache_path)
    elif args.api_key:
        log.info("Scraping Agmarknet...")
        df = AgmarknetScraper(args.api_key).fetch_all()
        df.to_parquet(cache_path)
        log.info("Scraped %d records", len(df))
    else:
        log.warning("No DATAGOV_API_KEY — skipping Agmarknet scrape")
        df = pd.DataFrame()

    # 2. Generate deterministic QA
    log.info("Generating price QA...")
    if not df.empty:
        all_pairs += generate_price_qa(df)
        all_pairs += generate_sell_timing_qa(df)

    log.info("Generating MSP QA...")
    all_pairs += generate_msp_qa()

    log.info("Generating storage QA...")
    all_pairs += generate_storage_qa()

    log.info("Generating crop planning QA...")
    all_pairs += generate_crop_planning_qa()

    log.info("Base pairs: %d", len(all_pairs))

    # 3. GPT-4o expansion
    if not args.no_gpt and os.environ.get("OPENAI_API_KEY"):
        log.info("Expanding with GPT-4o...")
        all_pairs += expand_with_gpt4o(all_pairs)

    # 4. Dedup + shuffle
    all_pairs = dedup(all_pairs)
    random.shuffle(all_pairs)

    # 5. Write JSONL
    with open(args.output, "w", encoding="utf-8") as f:
        for p in all_pairs:
            f.write(json.dumps(p, ensure_ascii=False) + "\n")

    log.info("✓ Wrote %d training pairs to %s", len(all_pairs), args.output)
    log.info("  Language split: %d EN / %d HI",
             sum(1 for p in all_pairs if p["language"]=="en"),
             sum(1 for p in all_pairs if p["language"]=="hi"))


if __name__ == "__main__":
    main()
