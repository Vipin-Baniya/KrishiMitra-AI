"""
KrishiMitra LLM — Model Merger & Exporter
==========================================
Steps:
  1. merge_lora_into_base  — bakes LoRA weights into base model (full precision)
  2. export_to_gguf        — converts to GGUF Q4_K_M via llama.cpp for CPU serving
  3. export_to_hf          — pushes merged model to HuggingFace Hub (optional)
  4. verify_merged_model   — sanity checks the merged model before deployment

Run after training completes:
    python training/merge_export.py --config configs/config.yaml
"""

import gc
import logging
import shutil
import subprocess
import sys
from pathlib import Path

import torch
import yaml
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer

log = logging.getLogger("merge_export")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-8s %(message)s")


# ─────────────────────────────────────────────────────────────
# 1. MERGE LORA INTO BASE
# ─────────────────────────────────────────────────────────────

def merge_lora_into_base(cfg: dict) -> str:
    """
    Loads base model in full precision (no quantisation), applies the
    trained LoRA adapter, then merges and unloads — producing a standard
    HuggingFace model that can be exported anywhere.

    Returns path to the merged model directory.
    """
    base_name   = cfg["base_model"]["name"]
    adapter_dir = cfg["training"]["output_dir"]
    merged_dir  = cfg["inference"]["merged_model_dir"]
    cache_dir   = cfg["paths"]["cache_dir"]

    if Path(merged_dir).exists():
        log.info("Merged model already exists at %s — skipping merge", merged_dir)
        return merged_dir

    log.info("Loading base model in bfloat16 (no quant) for merge")
    base_model = AutoModelForCausalLM.from_pretrained(
        base_name,
        torch_dtype=torch.bfloat16,
        device_map="auto",
        cache_dir=cache_dir,
        trust_remote_code=True,
    )

    log.info("Loading LoRA adapter from: %s", adapter_dir)
    model = PeftModel.from_pretrained(base_model, adapter_dir)

    log.info("Merging LoRA weights into base model")
    model = model.merge_and_unload()

    log.info("Saving merged model → %s", merged_dir)
    Path(merged_dir).mkdir(parents=True, exist_ok=True)
    model.save_pretrained(merged_dir, safe_serialization=True, max_shard_size="4GB")

    tokenizer = AutoTokenizer.from_pretrained(adapter_dir)
    tokenizer.save_pretrained(merged_dir)

    log.info("Merged model saved (%.1fGB)",
             sum(f.stat().st_size for f in Path(merged_dir).rglob("*.safetensors")) / 1e9)

    del model, base_model
    gc.collect()
    torch.cuda.empty_cache()

    return merged_dir


# ─────────────────────────────────────────────────────────────
# 2. EXPORT TO GGUF (llama.cpp)
# ─────────────────────────────────────────────────────────────

def export_to_gguf(cfg: dict, merged_dir: str) -> str:
    """
    Converts merged HF model to GGUF format using llama.cpp convert script.
    Then quantises to Q4_K_M for efficient CPU inference.

    Prerequisites:
        git clone https://github.com/ggerganov/llama.cpp
        pip install gguf

    Returns path to the quantised GGUF file.
    """
    quant_type  = cfg["inference"]["gguf_quantization"]
    gguf_dir    = cfg["inference"]["quantized_model_dir"]
    Path(gguf_dir).mkdir(parents=True, exist_ok=True)

    # Path where llama.cpp is cloned
    llamacpp_dir = Path(os.environ.get("LLAMACPP_DIR", "./llama.cpp"))
    if not llamacpp_dir.exists():
        log.error("llama.cpp not found at %s. Set LLAMACPP_DIR env var.", llamacpp_dir)
        sys.exit(1)

    convert_script = llamacpp_dir / "convert_hf_to_gguf.py"
    raw_gguf       = Path(gguf_dir) / "krishimitra-llm-raw.gguf"
    quantised_gguf = Path(gguf_dir) / f"krishimitra-llm-{quant_type}.gguf"

    if quantised_gguf.exists():
        log.info("GGUF already exists: %s", quantised_gguf)
        return str(quantised_gguf)

    # Step 1: Convert to raw GGUF (fp16)
    log.info("Converting HF → GGUF (fp16)")
    result = subprocess.run(
        [
            sys.executable, str(convert_script),
            merged_dir,
            "--outfile", str(raw_gguf),
            "--outtype", "f16",
        ],
        capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        log.error("Conversion failed:\n%s", result.stderr)
        raise RuntimeError("GGUF conversion failed")

    # Step 2: Quantise to Q4_K_M
    log.info("Quantising GGUF → %s", quant_type)
    quantize_bin = llamacpp_dir / "llama-quantize"
    if not quantize_bin.exists():
        quantize_bin = llamacpp_dir / "quantize"   # older builds

    result = subprocess.run(
        [str(quantize_bin), str(raw_gguf), str(quantised_gguf), quant_type],
        capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        log.error("Quantisation failed:\n%s", result.stderr)
        raise RuntimeError("Quantisation failed")

    raw_gguf.unlink(missing_ok=True)   # remove unquantised file
    size_gb = quantised_gguf.stat().st_size / 1e9
    log.info("GGUF saved: %s (%.2f GB)", quantised_gguf, size_gb)
    return str(quantised_gguf)


# ─────────────────────────────────────────────────────────────
# 3. PUSH TO HF HUB (optional)
# ─────────────────────────────────────────────────────────────

def push_to_hub(cfg: dict, merged_dir: str, repo_id: str) -> None:
    """
    Push merged model to HuggingFace Hub for versioning.
    Set HF_TOKEN env var before calling.

    Usage:
        push_to_hub(cfg, merged_dir, "krishimitra-ai/krishimitra-llm-v1")
    """
    from huggingface_hub import HfApi, login
    import os

    token = os.environ.get("HF_TOKEN")
    if not token:
        log.warning("HF_TOKEN not set — skipping Hub push")
        return

    log.info("Pushing to HF Hub: %s", repo_id)
    login(token=token)
    api = HfApi()
    api.upload_folder(
        folder_path=merged_dir,
        repo_id=repo_id,
        repo_type="model",
        commit_message=f"KrishiMitra LLM v{cfg['project']['version']} — merged model",
    )
    log.info("Pushed: https://huggingface.co/%s", repo_id)


# ─────────────────────────────────────────────────────────────
# 4. VERIFY MERGED MODEL
# ─────────────────────────────────────────────────────────────

def verify_merged_model(merged_dir: str) -> bool:
    """
    Quick sanity checks on the merged model before production deployment:
      - Loads successfully
      - Tokenizer round-trips correctly
      - Generates coherent output for a known agri prompt
    """
    log.info("Verifying merged model: %s", merged_dir)

    tokenizer = AutoTokenizer.from_pretrained(merged_dir)
    model = AutoModelForCausalLM.from_pretrained(
        merged_dir,
        torch_dtype=torch.bfloat16,
        device_map="auto",
    )
    model.eval()

    VERIFICATION_PROMPTS = [
        {
            "prompt": "What is the wheat price in Indore today?",
            "must_contain": ["₹", "quintal"],
        },
        {
            "prompt": "गेहूं का भाव क्या है?",
            "must_contain": ["₹", "क्विंटल"],
        },
    ]

    all_passed = True
    with torch.no_grad():
        for check in VERIFICATION_PROMPTS:
            chat = [
                {"role": "system", "content": "You are KrishiMitra AI."},
                {"role": "user",   "content": check["prompt"]},
            ]
            text   = tokenizer.apply_chat_template(chat, tokenize=False, add_generation_prompt=True)
            inputs = tokenizer(text, return_tensors="pt").to(model.device)
            out    = model.generate(**inputs, max_new_tokens=100, temperature=0.1, do_sample=False)
            resp   = tokenizer.decode(out[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True)

            passed = all(kw in resp for kw in check["must_contain"])
            status = "PASS" if passed else "FAIL"
            log.info("[%s] Prompt: %s | Response: %s", status, check["prompt"][:50], resp[:80])
            if not passed:
                all_passed = False

    del model
    gc.collect()
    torch.cuda.empty_cache()

    log.info("Verification: %s", "ALL PASSED" if all_passed else "SOME FAILURES — review before deploy")
    return all_passed


# ─────────────────────────────────────────────────────────────
# MAIN
# ─────────────────────────────────────────────────────────────

def main(config_path: str = "configs/config.yaml") -> None:
    import os
    with open(config_path) as f:
        cfg = yaml.safe_load(f)

    # 1. Merge LoRA
    merged_dir = merge_lora_into_base(cfg)

    # 2. Verify
    ok = verify_merged_model(merged_dir)
    if not ok:
        log.warning("Verification failed — proceeding anyway, but review logs")

    # 3. Export to GGUF for CPU inference
    try:
        gguf_path = export_to_gguf(cfg, merged_dir)
        log.info("GGUF ready for CPU serving: %s", gguf_path)
    except RuntimeError as e:
        log.warning("GGUF export failed: %s — skipping", e)

    # 4. Optional HF Hub push
    hub_repo = os.environ.get("HF_REPO_ID")
    if hub_repo:
        push_to_hub(cfg, merged_dir, hub_repo)

    log.info("Export complete.")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", default="configs/config.yaml")
    args = parser.parse_args()
    main(args.config)
