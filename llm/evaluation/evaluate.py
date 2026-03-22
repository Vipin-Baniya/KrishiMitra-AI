"""
KrishiMitra LLM — Evaluation Suite
=====================================
Benchmarks:
  1. AgriQABenchmark      — domain QA accuracy (EM + F1)
  2. PriceMAEBenchmark    — price prediction error vs ground truth
  3. SellAdviceAccuracy   — SELL/WAIT decision classification accuracy
  4. LanguageCoverage     — BLEU on Hindi/multilingual outputs
  5. BaselineComparison   — compare against GPT-4o-mini + Mistral base

Usage:
    python evaluation/evaluate.py \
        --model_dir outputs/krishimitra-llm-v1-merged \
        --test_file data/processed/test.jsonl \
        --compare_gpt4o   # optional: run against GPT-4o-mini too
"""

import json
import logging
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import numpy as np
import torch
import yaml
from datasets import Dataset
from tqdm import tqdm
from transformers import AutoModelForCausalLM, AutoTokenizer

log = logging.getLogger("evaluator")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-8s %(message)s")


# ─────────────────────────────────────────────────────────────
# RESULT DATACLASSES
# ─────────────────────────────────────────────────────────────

@dataclass
class BenchmarkResult:
    name: str
    score: float
    unit: str
    details: dict

    def __str__(self):
        return f"{self.name:<30} {self.score:>8.3f} {self.unit}"


@dataclass
class EvaluationReport:
    model_name: str
    results: list[BenchmarkResult]

    def print(self):
        print("\n" + "═" * 60)
        print(f"  EVALUATION REPORT: {self.model_name}")
        print("═" * 60)
        for r in self.results:
            print(f"  {r}")
        print("═" * 60 + "\n")

    def to_dict(self) -> dict:
        return {
            "model": self.model_name,
            "results": {r.name: {"score": r.score, "unit": r.unit, **r.details}
                        for r in self.results},
        }

    def save(self, path: str) -> None:
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with open(path, "w") as f:
            json.dump(self.to_dict(), f, indent=2)
        log.info("Report saved: %s", path)


# ─────────────────────────────────────────────────────────────
# BASE EVALUATOR
# ─────────────────────────────────────────────────────────────

class ModelEvaluator:
    """
    Wraps a local HF model or an API model (GPT-4o-mini / Claude)
    behind a uniform .generate(messages) → str interface.
    """

    def __init__(
        self,
        model_dir: Optional[str] = None,
        api_provider: Optional[str] = None,    # "openai" | "anthropic"
        api_model: Optional[str] = None,
        max_new_tokens: int = 256,
        temperature: float = 0.1,
    ):
        self.max_new_tokens = max_new_tokens
        self.temperature    = temperature
        self.api_provider   = api_provider
        self.name           = model_dir or api_model or "unknown"

        if model_dir:
            log.info("Loading local model: %s", model_dir)
            self.tokenizer = AutoTokenizer.from_pretrained(model_dir)
            self.model = AutoModelForCausalLM.from_pretrained(
                model_dir,
                torch_dtype=torch.bfloat16,
                device_map="auto",
            )
            self.model.eval()
            self._generate = self._generate_local
        elif api_provider == "openai":
            from openai import OpenAI
            self.client    = OpenAI()
            self.api_model = api_model or "gpt-4o-mini"
            self._generate = self._generate_openai
        elif api_provider == "anthropic":
            import anthropic
            self.client    = anthropic.Anthropic()
            self.api_model = api_model or "claude-haiku-4-5-20251001"
            self._generate = self._generate_anthropic
        else:
            raise ValueError("Provide model_dir or api_provider")

    def generate(self, messages: list[dict]) -> str:
        return self._generate(messages)

    def _generate_local(self, messages: list[dict]) -> str:
        text   = self.tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
        inputs = self.tokenizer(text, return_tensors="pt").to(self.model.device)
        with torch.no_grad():
            out = self.model.generate(
                **inputs,
                max_new_tokens=self.max_new_tokens,
                temperature=self.temperature,
                do_sample=self.temperature > 0,
                pad_token_id=self.tokenizer.eos_token_id,
            )
        return self.tokenizer.decode(out[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True).strip()

    def _generate_openai(self, messages: list[dict]) -> str:
        resp = self.client.chat.completions.create(
            model=self.api_model,
            messages=messages,
            max_tokens=self.max_new_tokens,
            temperature=self.temperature,
        )
        return resp.choices[0].message.content.strip()

    def _generate_anthropic(self, messages: list[dict]) -> str:
        system = next((m["content"] for m in messages if m["role"] == "system"), "")
        user_msgs = [m for m in messages if m["role"] != "system"]
        resp = self.client.messages.create(
            model=self.api_model,
            max_tokens=self.max_new_tokens,
            system=system,
            messages=user_msgs,
        )
        return resp.content[0].text.strip()


# ─────────────────────────────────────────────────────────────
# 1. AGRI QA BENCHMARK
# ─────────────────────────────────────────────────────────────

class AgriQABenchmark:
    """
    Tests factual accuracy on held-out agriculture QA.
    Metrics: Exact Match (EM) and token-level F1.
    """

    SYSTEM = "You are KrishiMitra AI. Answer concisely and factually."

    def __init__(self, test_file: str):
        self.samples = []
        with open(test_file) as f:
            for line in f:
                obj = json.loads(line)
                msgs = obj.get("messages", [])
                user_msg = next((m["content"] for m in msgs if m["role"] == "user"), "")
                gold_msg = next((m["content"] for m in msgs if m["role"] == "assistant"), "")
                if user_msg and gold_msg:
                    self.samples.append({"user": user_msg, "gold": gold_msg})

    @staticmethod
    def _normalize(text: str) -> str:
        text = text.lower().strip()
        text = re.sub(r"[^\w\s₹/]", " ", text)
        return re.sub(r"\s+", " ", text).strip()

    @staticmethod
    def _token_f1(pred: str, gold: str) -> float:
        pred_toks = set(AgriQABenchmark._normalize(pred).split())
        gold_toks = set(AgriQABenchmark._normalize(gold).split())
        if not gold_toks:
            return 1.0 if not pred_toks else 0.0
        common = pred_toks & gold_toks
        if not common:
            return 0.0
        precision = len(common) / len(pred_toks)
        recall    = len(common) / len(gold_toks)
        return 2 * precision * recall / (precision + recall)

    def run(self, evaluator: ModelEvaluator, n: int = 200) -> BenchmarkResult:
        samples = self.samples[:n]
        em_scores, f1_scores = [], []

        for s in tqdm(samples, desc="AgriQA"):
            messages = [
                {"role": "system", "content": self.SYSTEM},
                {"role": "user",   "content": s["user"]},
            ]
            pred = evaluator.generate(messages)
            em   = 1.0 if self._normalize(pred) == self._normalize(s["gold"]) else 0.0
            f1   = self._token_f1(pred, s["gold"])
            em_scores.append(em)
            f1_scores.append(f1)

        return BenchmarkResult(
            name="AgriQA F1",
            score=float(np.mean(f1_scores)),
            unit="(0–1)",
            details={"exact_match": float(np.mean(em_scores)), "n_samples": len(samples)},
        )


# ─────────────────────────────────────────────────────────────
# 2. PRICE MAE BENCHMARK
# ─────────────────────────────────────────────────────────────

class PriceMAEBenchmark:
    """
    Asks the model for a price, then compares the extracted number
    against the ground-truth modal price.

    Metric: MAE in ₹/quintal (lower is better).
    """

    PRICE_TEMPLATE = (
        "What is the modal price of {commodity} at {mandi} mandi in {state} today? "
        "Give only the number in ₹/quintal."
    )

    def __init__(self, price_df):
        self.df = price_df

    @staticmethod
    def _extract_price(text: str) -> Optional[float]:
        matches = re.findall(r"₹?\s*([0-9,]+(?:\.[0-9]+)?)", text.replace(",", ""))
        for m in matches:
            try:
                val = float(m.replace(",", ""))
                if 100 < val < 50000:   # sanity range for ₹/quintal
                    return val
            except ValueError:
                continue
        return None

    def run(self, evaluator: ModelEvaluator, n: int = 100) -> BenchmarkResult:
        sample = self.df.sample(min(n, len(self.df)), random_state=42)
        errors = []

        for _, row in tqdm(sample.iterrows(), total=len(sample), desc="Price MAE"):
            prompt = self.PRICE_TEMPLATE.format(
                commodity=row["commodity"],
                mandi=row["mandi"],
                state=row["state"],
            )
            messages = [{"role": "user", "content": prompt}]
            pred_text = evaluator.generate(messages)
            pred_price = self._extract_price(pred_text)

            if pred_price is not None:
                errors.append(abs(pred_price - row["modal_price"]))

        mae = float(np.mean(errors)) if errors else float("inf")
        return BenchmarkResult(
            name="Price MAE",
            score=mae,
            unit="₹/qtl",
            details={"n_successful": len(errors), "n_total": n},
        )


# ─────────────────────────────────────────────────────────────
# 3. SELL ADVICE ACCURACY
# ─────────────────────────────────────────────────────────────

class SellAdviceAccuracy:
    """
    Classification accuracy on SELL vs WAIT decisions.
    Ground truth: if modal_price is trending up (7-day slope > 0) → WAIT, else → SELL.
    """

    PROMPT_TEMPLATE = (
        "Based on current trends, should a farmer SELL or WAIT to sell their {commodity} "
        "in {mandi}, {state}? Answer with only 'SELL' or 'WAIT' and a brief reason."
    )

    def __init__(self, price_df):
        import pandas as pd
        self.df = price_df
        # Pre-compute ground truth labels
        self.df = self.df.sort_values("date")
        self.df["label"] = self.df.groupby(["commodity", "mandi"])["modal_price"] \
            .transform(lambda s: (s.rolling(7).mean().diff() > 0).map({True: "WAIT", False: "SELL"}))

    def run(self, evaluator: ModelEvaluator, n: int = 100) -> BenchmarkResult:
        labelled = self.df.dropna(subset=["label"]).sample(min(n, len(self.df)), random_state=0)
        correct  = 0
        total    = 0

        for _, row in tqdm(labelled.iterrows(), total=len(labelled), desc="SellAdvice"):
            prompt = self.PROMPT_TEMPLATE.format(
                commodity=row["commodity"],
                mandi=row["mandi"],
                state=row["state"],
            )
            messages = [{"role": "user", "content": prompt}]
            resp = evaluator.generate(messages).upper()

            pred  = "SELL" if "SELL" in resp else ("WAIT" if "WAIT" in resp else None)
            gold  = row["label"]
            if pred is not None:
                correct += int(pred == gold)
                total   += 1

        accuracy = correct / total if total else 0.0
        return BenchmarkResult(
            name="Sell advice accuracy",
            score=accuracy,
            unit="(0–1)",
            details={"correct": correct, "total": total},
        )


# ─────────────────────────────────────────────────────────────
# 4. LANGUAGE COVERAGE (BLEU)
# ─────────────────────────────────────────────────────────────

class LanguageCoverage:
    """
    BLEU score on Hindi + multilingual outputs.
    Requires sacrebleu: pip install sacrebleu
    """

    def __init__(self, hindi_test_file: str):
        self.samples = []
        if not Path(hindi_test_file).exists():
            log.warning("Hindi test file not found: %s", hindi_test_file)
            return
        with open(hindi_test_file) as f:
            for line in f:
                obj = json.loads(line)
                msgs = obj.get("messages", [])
                user_msg = next((m["content"] for m in msgs if m["role"] == "user"), "")
                gold_msg = next((m["content"] for m in msgs if m["role"] == "assistant"), "")
                if user_msg and gold_msg and obj.get("language") == "hi":
                    self.samples.append({"user": user_msg, "gold": gold_msg})

    def run(self, evaluator: ModelEvaluator, n: int = 100) -> BenchmarkResult:
        try:
            from sacrebleu.metrics import BLEU
        except ImportError:
            log.warning("sacrebleu not installed — skipping BLEU benchmark")
            return BenchmarkResult("Language BLEU (Hindi)", 0.0, "(N/A)", {"error": "sacrebleu missing"})

        samples = self.samples[:n]
        if not samples:
            return BenchmarkResult("Language BLEU (Hindi)", 0.0, "(N/A)", {"error": "no Hindi samples"})

        bleu   = BLEU(effective_order=True)
        preds, refs = [], []

        for s in tqdm(samples, desc="Hindi BLEU"):
            messages = [{"role": "user", "content": s["user"]}]
            preds.append(evaluator.generate(messages))
            refs.append([s["gold"]])

        result = bleu.corpus_score(preds, refs)
        return BenchmarkResult(
            name="Language BLEU (Hindi)",
            score=result.score / 100,
            unit="(0–1)",
            details={"bleu_score": result.score, "n_samples": len(samples)},
        )


# ─────────────────────────────────────────────────────────────
# FULL EVALUATION RUN
# ─────────────────────────────────────────────────────────────

def run_evaluation(
    model_dir: str,
    test_file: str,
    price_csv: str,
    hindi_test_file: str,
    compare_gpt4o: bool = False,
    compare_claude: bool = False,
    output_dir: str = "evaluation/results",
) -> None:

    import pandas as pd
    price_df = pd.read_csv(price_csv) if Path(price_csv).exists() else pd.DataFrame()

    # Build benchmarks
    benchmarks_qa = AgriQABenchmark(test_file) if Path(test_file).exists() else None
    benchmarks_price  = PriceMAEBenchmark(price_df) if not price_df.empty else None
    benchmarks_sell   = SellAdviceAccuracy(price_df) if not price_df.empty else None
    benchmarks_lang   = LanguageCoverage(hindi_test_file)

    def evaluate_model(evaluator: ModelEvaluator) -> EvaluationReport:
        results = []
        if benchmarks_qa:
            results.append(benchmarks_qa.run(evaluator))
        if benchmarks_price:
            results.append(benchmarks_price.run(evaluator))
        if benchmarks_sell:
            results.append(benchmarks_sell.run(evaluator))
        results.append(benchmarks_lang.run(evaluator))
        return EvaluationReport(model_name=evaluator.name, results=results)

    reports = []

    # KrishiMitra custom model
    km_evaluator = ModelEvaluator(model_dir=model_dir)
    km_report    = evaluate_model(km_evaluator)
    km_report.print()
    km_report.save(f"{output_dir}/krishimitra_eval.json")
    reports.append(km_report)

    # Baseline: GPT-4o-mini
    if compare_gpt4o:
        gpt_evaluator = ModelEvaluator(api_provider="openai", api_model="gpt-4o-mini")
        gpt_report    = evaluate_model(gpt_evaluator)
        gpt_report.print()
        gpt_report.save(f"{output_dir}/gpt4o_mini_eval.json")
        reports.append(gpt_report)

    # Baseline: Claude fallback
    if compare_claude:
        cl_evaluator = ModelEvaluator(api_provider="anthropic", api_model="claude-haiku-4-5-20251001")
        cl_report    = evaluate_model(cl_evaluator)
        cl_report.print()
        cl_report.save(f"{output_dir}/claude_eval.json")
        reports.append(cl_report)

    # Delta comparison table
    if len(reports) > 1:
        print("\n  COMPARISON vs BASELINES")
        print("  " + "-" * 56)
        km_scores = {r.name: r.score for r in reports[0].results}
        for baseline_report in reports[1:]:
            print(f"\n  KrishiMitra vs {baseline_report.model_name}")
            for r in baseline_report.results:
                km_s = km_scores.get(r.name, 0)
                delta = km_s - r.score
                arrow = "▲" if delta > 0 else "▼"
                print(f"    {r.name:<30} {arrow} {abs(delta):.3f}")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--model_dir",        default="outputs/krishimitra-llm-v1-merged")
    parser.add_argument("--test_file",        default="data/processed/test.jsonl")
    parser.add_argument("--price_csv",        default="data/raw/agmarknet_prices.csv")
    parser.add_argument("--hindi_test_file",  default="data/processed/test.jsonl")
    parser.add_argument("--compare_gpt4o",    action="store_true")
    parser.add_argument("--compare_claude",   action="store_true")
    parser.add_argument("--output_dir",       default="evaluation/results")
    args = parser.parse_args()
    run_evaluation(**vars(args))
