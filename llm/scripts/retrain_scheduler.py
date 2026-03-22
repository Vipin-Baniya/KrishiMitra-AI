"""
KrishiMitra LLM — Continuous Retraining Scheduler
==================================================
Runs nightly (cron) or on-demand.
Detects when the model needs retraining based on:
  1. Data drift — new price patterns the model hasn't seen
  2. Accuracy degradation — eval metrics drop below threshold
  3. Fixed schedule — retrain every N days regardless
  4. New knowledge — new crops / mandis added to system

Retraining pipeline:
  fetch new data → generate new pairs → merge with existing dataset
  → fine-tune from latest checkpoint → evaluate → promote if better

Usage:
    # Nightly cron job
    0 2 * * * cd /app && python scripts/retrain_scheduler.py --check-and-run

    # Force retrain
    python scripts/retrain_scheduler.py --force
"""

import json
import logging
import os
import subprocess
import sys
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np
import pandas as pd
import yaml

log = logging.getLogger("retrain_scheduler")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-8s %(message)s")

with open("configs/config.yaml") as f:
    CFG = yaml.safe_load(f)

STATE_FILE = Path("scripts/.retrain_state.json")


# ─────────────────────────────────────────────────────────────
# STATE MANAGEMENT
# ─────────────────────────────────────────────────────────────

def load_state() -> dict:
    if STATE_FILE.exists():
        with open(STATE_FILE) as f:
            return json.load(f)
    return {
        "last_retrain":    None,
        "last_eval_score": None,
        "retrain_count":   0,
        "last_data_hash":  None,
    }

def save_state(state: dict) -> None:
    STATE_FILE.parent.mkdir(exist_ok=True)
    with open(STATE_FILE, "w") as f:
        json.dump(state, f, indent=2)


# ─────────────────────────────────────────────────────────────
# DRIFT DETECTION
# ─────────────────────────────────────────────────────────────

class DataDriftDetector:
    """
    Compares the price distribution of the last 30 days against
    the training data distribution. If KL divergence exceeds threshold,
    triggers retraining.
    """

    DRIFT_THRESHOLD = 0.15    # KL divergence — tune this
    LOOKBACK_DAYS   = 30

    def __init__(self, train_csv: str, live_csv: str):
        self.train_df = pd.read_csv(train_csv) if Path(train_csv).exists() else pd.DataFrame()
        self.live_df  = pd.read_csv(live_csv)  if Path(live_csv).exists()  else pd.DataFrame()

    def compute_kl_divergence(self, p: np.ndarray, q: np.ndarray) -> float:
        """KL(p || q) — symmetric via Jensen-Shannon divergence."""
        p = p + 1e-10
        q = q + 1e-10
        p = p / p.sum()
        q = q / q.sum()
        m = (p + q) / 2
        return float(0.5 * np.sum(p * np.log(p / m)) + 0.5 * np.sum(q * np.log(q / m)))

    def drift_score(self, commodity: str) -> float:
        """Returns JS divergence for one commodity's price distribution."""
        if self.train_df.empty or self.live_df.empty:
            return 0.0

        cutoff = (datetime.today() - timedelta(days=self.LOOKBACK_DAYS)).strftime("%Y-%m-%d")

        train_prices = self.train_df[
            self.train_df["commodity"] == commodity
        ]["modal_price"].dropna().values

        live_prices = self.live_df[
            (self.live_df["commodity"] == commodity) &
            (self.live_df["date"] >= cutoff)
        ]["modal_price"].dropna().values

        if len(train_prices) < 10 or len(live_prices) < 5:
            return 0.0

        # Bin both into 20 equal-width bins
        min_p = min(train_prices.min(), live_prices.min())
        max_p = max(train_prices.max(), live_prices.max())
        bins  = np.linspace(min_p, max_p, 21)

        p_hist, _ = np.histogram(train_prices, bins=bins, density=True)
        q_hist, _ = np.histogram(live_prices,  bins=bins, density=True)

        return self.compute_kl_divergence(p_hist, q_hist)

    def is_drifted(self, commodities: list[str]) -> tuple[bool, dict]:
        scores = {}
        for c in commodities:
            scores[c] = round(self.drift_score(c), 4)

        drifted = {c: s for c, s in scores.items() if s > self.DRIFT_THRESHOLD}
        is_drift = len(drifted) > 0

        if is_drift:
            log.info("Data drift detected for: %s", drifted)
        else:
            log.info("No significant drift (max JS=%.3f)", max(scores.values()) if scores else 0)

        return is_drift, scores


# ─────────────────────────────────────────────────────────────
# ACCURACY MONITOR
# ─────────────────────────────────────────────────────────────

class AccuracyMonitor:
    """
    Reads the latest eval report and checks if metrics have degraded
    below the acceptable threshold.
    """

    THRESHOLDS = {
        "AgriQA F1":              0.65,
        "Sell advice accuracy":   0.70,
        "Price MAE":              350,    # ₹/quintal — lower is better, so threshold is max
    }

    LOWER_IS_BETTER = {"Price MAE"}

    def __init__(self, eval_dir: str = "evaluation/results"):
        self.eval_dir = Path(eval_dir)

    def latest_scores(self) -> dict:
        report_file = self.eval_dir / "krishimitra_eval.json"
        if not report_file.exists():
            return {}
        with open(report_file) as f:
            data = json.load(f)
        return {k: v["score"] for k, v in data.get("results", {}).items()}

    def is_degraded(self) -> tuple[bool, dict]:
        scores = self.latest_scores()
        degraded = {}

        for metric, threshold in self.THRESHOLDS.items():
            score = scores.get(metric)
            if score is None:
                continue
            if metric in self.LOWER_IS_BETTER:
                if score > threshold:
                    degraded[metric] = {"score": score, "threshold": threshold}
            else:
                if score < threshold:
                    degraded[metric] = {"score": score, "threshold": threshold}

        return bool(degraded), degraded


# ─────────────────────────────────────────────────────────────
# RETRAINING ORCHESTRATOR
# ─────────────────────────────────────────────────────────────

class RetrainOrchestrator:
    """
    Orchestrates the full retraining pipeline:
    1. Fetch new data
    2. Rebuild dataset (merge new + existing)
    3. Fine-tune from last checkpoint
    4. Evaluate
    5. Promote if better, roll back if worse
    """

    RETRAIN_INTERVAL_DAYS = 14    # force retrain every 2 weeks regardless

    def __init__(self, state: dict):
        self.state = state

    def should_retrain(
        self,
        drift_detected: bool,
        accuracy_degraded: bool,
        force: bool = False,
    ) -> tuple[bool, str]:
        if force:
            return True, "forced"

        if drift_detected:
            return True, "data_drift"

        if accuracy_degraded:
            return True, "accuracy_degradation"

        # Schedule-based
        last_retrain = self.state.get("last_retrain")
        if last_retrain:
            last_dt = datetime.fromisoformat(last_retrain)
            if datetime.utcnow() - last_dt > timedelta(days=self.RETRAIN_INTERVAL_DAYS):
                return True, "scheduled"
        else:
            return True, "first_run"

        return False, ""

    def run_pipeline(self, reason: str) -> bool:
        """
        Run the full retrain pipeline.
        Returns True if the new model is promoted to production.
        """
        log.info("=" * 60)
        log.info("STARTING RETRAINING — reason: %s", reason)
        log.info("=" * 60)

        version = f"v{self.state['retrain_count'] + 1}"
        output_dir = f"outputs/krishimitra-llm-{version}"

        steps = [
            ("Fetch new data",    [sys.executable, "data/data_pipeline.py"]),
            ("Fine-tune model",   [sys.executable, "training/trainer.py"]),
            ("Merge & export",    [sys.executable, "training/merge_export.py"]),
            ("Evaluate",          [sys.executable, "evaluation/evaluate.py",
                                   f"--model_dir={output_dir}-merged"]),
        ]

        for step_name, cmd in steps:
            log.info("Step: %s", step_name)
            result = subprocess.run(cmd, capture_output=False)
            if result.returncode != 0:
                log.error("Step '%s' failed — aborting retraining", step_name)
                return False

        # Compare new vs current eval scores
        monitor = AccuracyMonitor()
        new_f1  = monitor.latest_scores().get("AgriQA F1", 0.0)
        old_f1  = self.state.get("last_eval_score") or 0.0

        if new_f1 >= old_f1 * 0.97:   # promote if within 3% or better
            log.info("New model F1=%.3f vs old F1=%.3f — PROMOTING", new_f1, old_f1)
            self._promote_model(output_dir)
            self.state.update({
                "last_retrain":    datetime.utcnow().isoformat(),
                "last_eval_score": new_f1,
                "retrain_count":   self.state["retrain_count"] + 1,
            })
            save_state(self.state)
            return True
        else:
            log.warning("New model F1=%.3f worse than old F1=%.3f — ROLLING BACK", new_f1, old_f1)
            return False

    def _promote_model(self, new_model_dir: str) -> None:
        """
        Hot-swap the vLLM server to the new model.
        Sends SIGHUP to the vLLM process to reload from new path.
        In production: Blue-Green deployment or Kubernetes rolling update.
        """
        prod_symlink = Path("outputs/krishimitra-llm-production")
        if prod_symlink.is_symlink():
            prod_symlink.unlink()
        prod_symlink.symlink_to(Path(new_model_dir + "-merged").resolve())
        log.info("Production symlink updated → %s", prod_symlink)

        # Signal vLLM to reload (if running)
        pid_file = Path(".vllm.pid")
        if pid_file.exists():
            pid = int(pid_file.read_text().strip())
            try:
                os.kill(pid, 1)   # SIGHUP
                log.info("Sent SIGHUP to vLLM process (pid=%d)", pid)
            except ProcessLookupError:
                log.warning("vLLM process not found — manual restart required")


# ─────────────────────────────────────────────────────────────
# ENTRY POINT
# ─────────────────────────────────────────────────────────────

def main(force: bool = False) -> None:
    state      = load_state()
    orchestrator = RetrainOrchestrator(state)

    # Check drift
    detector = DataDriftDetector(
        train_csv="data/raw/agmarknet_prices.csv",
        live_csv="data/raw/agmarknet_prices_latest.csv",
    )
    commodities  = ["Wheat", "Soybean", "Onion", "Tomato"]
    drift_found, drift_scores = detector.is_drifted(commodities)

    # Check accuracy
    monitor          = AccuracyMonitor()
    acc_degraded, ac = monitor.is_degraded()
    if acc_degraded:
        log.info("Accuracy degraded: %s", ac)

    # Decide
    should, reason = orchestrator.should_retrain(drift_found, acc_degraded, force)
    log.info("Retrain decision: %s (reason=%s)", should, reason or "none")

    if should:
        success = orchestrator.run_pipeline(reason)
        exit(0 if success else 1)
    else:
        log.info("No retraining needed — all metrics healthy")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", action="store_true", help="Force retraining even if metrics are healthy")
    args = parser.parse_args()
    main(force=args.force)
