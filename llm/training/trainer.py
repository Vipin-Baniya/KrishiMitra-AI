"""
KrishiMitra LLM — QLoRA Fine-tuning Trainer
============================================
Stack:
  - HuggingFace Transformers + PEFT (QLoRA)
  - TRL SFTTrainer (chat template aware)
  - bitsandbytes 4-bit quantisation
  - Weights & Biases for experiment tracking

Hardware target: single A100 40GB (fits with QLoRA r=64, bs=4 x ga=8)
Expected training time: ~4–6 hours for 3 epochs on ~15k samples
"""

import os
import gc
import json
import logging
from pathlib import Path
from typing import Optional

import torch
import yaml
import wandb
from datasets import load_from_disk, DatasetDict
from peft import (
    LoraConfig,
    TaskType,
    get_peft_model,
    prepare_model_for_kbit_training,
)
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    BitsAndBytesConfig,
    TrainerCallback,
    TrainerControl,
    TrainerState,
    TrainingArguments,
)
from trl import SFTTrainer, SFTConfig, DataCollatorForCompletionOnlyLM

log = logging.getLogger("trainer")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-8s %(message)s")


# ─────────────────────────────────────────────────────────────
# CALLBACKS
# ─────────────────────────────────────────────────────────────

class EarlyStoppingOnPlateau(TrainerCallback):
    """
    Stop if eval_loss hasn't improved by `min_delta` for `patience` evals.
    More aggressive than the built-in: also checks for NaN loss.
    """

    def __init__(self, patience: int = 5, min_delta: float = 0.001):
        self.patience   = patience
        self.min_delta  = min_delta
        self.best_loss  = float("inf")
        self.wait_count = 0

    def on_evaluate(
        self,
        args: TrainingArguments,
        state: TrainerState,
        control: TrainerControl,
        metrics: dict,
        **kwargs,
    ) -> None:
        eval_loss = metrics.get("eval_loss", float("inf"))

        if torch.isnan(torch.tensor(eval_loss)):
            log.error("NaN eval loss detected — stopping training")
            control.should_training_stop = True
            return

        if eval_loss < self.best_loss - self.min_delta:
            self.best_loss  = eval_loss
            self.wait_count = 0
        else:
            self.wait_count += 1
            log.info("No improvement: %d/%d", self.wait_count, self.patience)
            if self.wait_count >= self.patience:
                log.info("Early stopping triggered (patience=%d)", self.patience)
                control.should_training_stop = True


class MemoryMonitorCallback(TrainerCallback):
    """Log GPU memory usage every N steps."""

    def __init__(self, log_every_n_steps: int = 50):
        self.n = log_every_n_steps

    def on_step_end(self, args, state, control, **kwargs):
        if state.global_step % self.n == 0 and torch.cuda.is_available():
            allocated = torch.cuda.memory_allocated() / 1e9
            reserved  = torch.cuda.memory_reserved()  / 1e9
            log.info("Step %d | GPU mem: %.2fGB alloc / %.2fGB reserved",
                     state.global_step, allocated, reserved)


class SampleGenerationCallback(TrainerCallback):
    """
    Generate a few sample outputs every N eval steps.
    Useful for watching model quality improve during training.
    """

    SAMPLE_PROMPTS = [
        "What is the current wheat price in Indore?",
        "Soybean abhi bechun ya rukun?",
        "Which crop should I plant in black soil this rabi season?",
        "Should I store my onion crop or sell immediately?",
    ]

    def __init__(self, tokenizer, every_n_evals: int = 2):
        self.tokenizer   = tokenizer
        self.every_n     = every_n_evals
        self.eval_count  = 0

    def on_evaluate(self, args, state, control, model=None, **kwargs):
        self.eval_count += 1
        if self.eval_count % self.every_n != 0 or model is None:
            return

        model.eval()
        log.info("\n── Sample generations (step %d) ──", state.global_step)
        with torch.no_grad():
            for prompt in self.SAMPLE_PROMPTS[:2]:
                chat = [
                    {"role": "system", "content": "You are KrishiMitra AI, an expert agricultural advisor."},
                    {"role": "user",   "content": prompt},
                ]
                text = self.tokenizer.apply_chat_template(
                    chat, tokenize=False, add_generation_prompt=True
                )
                inputs = self.tokenizer(text, return_tensors="pt").to(model.device)
                out    = model.generate(
                    **inputs,
                    max_new_tokens=150,
                    temperature=0.3,
                    do_sample=True,
                    pad_token_id=self.tokenizer.eos_token_id,
                )
                response = self.tokenizer.decode(
                    out[0][inputs["input_ids"].shape[1]:], skip_special_tokens=True
                )
                log.info("Q: %s\nA: %s\n", prompt, response.strip())
        model.train()


# ─────────────────────────────────────────────────────────────
# MODEL LOADER
# ─────────────────────────────────────────────────────────────

def load_base_model(cfg: dict):
    """
    Load base model with 4-bit QLoRA quantisation.
    Returns (model, tokenizer) ready for PEFT wrapping.
    """
    model_name = cfg["base_model"]["name"]
    cache_dir  = cfg["paths"]["cache_dir"]

    log.info("Loading tokenizer: %s", model_name)
    tokenizer = AutoTokenizer.from_pretrained(
        model_name,
        cache_dir=cache_dir,
        padding_side=cfg["base_model"]["tokenizer_padding_side"],
        trust_remote_code=True,
    )
    # Ensure pad token exists (Mistral uses eos as pad)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token
        tokenizer.pad_token_id = tokenizer.eos_token_id

    bnb_config = BitsAndBytesConfig(
        load_in_4bit=cfg["base_model"]["load_in_4bit"],
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.bfloat16,
        bnb_4bit_use_double_quant=True,    # nested quant saves ~0.4 bits/param
    )

    log.info("Loading model: %s (4-bit)", model_name)
    model = AutoModelForCausalLM.from_pretrained(
        model_name,
        quantization_config=bnb_config,
        device_map="auto",
        cache_dir=cache_dir,
        trust_remote_code=True,
        torch_dtype=torch.bfloat16,
        attn_implementation="flash_attention_2",  # requires flash-attn installed
    )
    model.config.use_cache = False                # required for gradient checkpointing
    model.config.pretraining_tp = 1

    # Prepare for k-bit training (freezes base, casts norms to float32)
    model = prepare_model_for_kbit_training(
        model, use_gradient_checkpointing=True
    )

    log.info("Model loaded. Trainable params before LoRA: %s",
             sum(p.numel() for p in model.parameters() if p.requires_grad))
    return model, tokenizer


def apply_lora(model, cfg: dict):
    """Wrap model with LoRA adapters."""
    lora_cfg = cfg["lora"]
    peft_config = LoraConfig(
        r=lora_cfg["r"],
        lora_alpha=lora_cfg["lora_alpha"],
        lora_dropout=lora_cfg["lora_dropout"],
        bias=lora_cfg["bias"],
        task_type=TaskType.CAUSAL_LM,
        target_modules=lora_cfg["target_modules"],
        modules_to_save=lora_cfg.get("modules_to_save"),
    )
    model = get_peft_model(model, peft_config)

    trainable = sum(p.numel() for p in model.parameters() if p.requires_grad)
    total     = sum(p.numel() for p in model.parameters())
    log.info("LoRA applied — trainable: %s / %s (%.2f%%)",
             f"{trainable:,}", f"{total:,}", 100 * trainable / total)
    model.print_trainable_parameters()
    return model


# ─────────────────────────────────────────────────────────────
# DATASET PREP
# ─────────────────────────────────────────────────────────────

def prepare_datasets(cfg: dict, tokenizer) -> tuple:
    """Load processed dataset, apply chat template, return train/val."""
    data_cfg = cfg["data"]
    dataset_dict: DatasetDict = load_from_disk("data/processed/hf_dataset")

    def format_messages(example):
        """Apply chat template — turns messages list into a single string."""
        text = tokenizer.apply_chat_template(
            example["messages"],
            tokenize=False,
            add_generation_prompt=False,
        )
        return {"text": text}

    log.info("Applying chat template to dataset")
    dataset_dict = dataset_dict.map(
        format_messages,
        batched=False,
        desc="Formatting",
        remove_columns=["messages", "source", "language", "tags"],
    )

    # Optional: cap training samples
    train_ds = dataset_dict["train"]
    if data_cfg.get("max_train_samples"):
        train_ds = train_ds.select(range(data_cfg["max_train_samples"]))

    val_ds = dataset_dict["val"]
    if data_cfg.get("max_val_samples"):
        val_ds = val_ds.select(range(min(data_cfg["max_val_samples"], len(val_ds))))

    log.info("Train: %d samples | Val: %d samples", len(train_ds), len(val_ds))
    return train_ds, val_ds


# ─────────────────────────────────────────────────────────────
# TRAINER BUILDER
# ─────────────────────────────────────────────────────────────

def build_trainer(
    model,
    tokenizer,
    train_ds,
    val_ds,
    cfg: dict,
    extra_callbacks: Optional[list] = None,
) -> SFTTrainer:

    train_cfg = cfg["training"]

    sft_config = SFTConfig(
        output_dir=train_cfg["output_dir"],
        num_train_epochs=train_cfg["num_train_epochs"],
        per_device_train_batch_size=train_cfg["per_device_train_batch_size"],
        per_device_eval_batch_size=train_cfg["per_device_eval_batch_size"],
        gradient_accumulation_steps=train_cfg["gradient_accumulation_steps"],
        learning_rate=train_cfg["learning_rate"],
        lr_scheduler_type=train_cfg["lr_scheduler_type"],
        warmup_ratio=train_cfg["warmup_ratio"],
        weight_decay=train_cfg["weight_decay"],
        max_grad_norm=train_cfg["max_grad_norm"],
        fp16=train_cfg["fp16"],
        bf16=train_cfg["bf16"],
        optim=train_cfg["optim"],
        save_strategy=train_cfg["save_strategy"],
        save_steps=train_cfg["save_steps"],
        eval_strategy=train_cfg["eval_strategy"],
        eval_steps=train_cfg["eval_steps"],
        logging_steps=train_cfg["logging_steps"],
        load_best_model_at_end=train_cfg["load_best_model_at_end"],
        metric_for_best_model=train_cfg["metric_for_best_model"],
        greater_is_better=train_cfg["greater_is_better"],
        report_to=train_cfg["report_to"],
        dataloader_num_workers=train_cfg["dataloader_num_workers"],
        remove_unused_columns=train_cfg["remove_unused_columns"],
        seed=train_cfg["seed"],
        # SFT-specific
        max_seq_length=cfg["base_model"]["max_seq_length"],
        dataset_text_field="text",
        packing=False,          # disable packing — keeps samples clean
        gradient_checkpointing=True,
        gradient_checkpointing_kwargs={"use_reentrant": False},
    )

    callbacks = [
        EarlyStoppingOnPlateau(patience=5, min_delta=0.001),
        MemoryMonitorCallback(log_every_n_steps=50),
        SampleGenerationCallback(tokenizer, every_n_evals=2),
    ]
    if extra_callbacks:
        callbacks.extend(extra_callbacks)

    trainer = SFTTrainer(
        model=model,
        args=sft_config,
        train_dataset=train_ds,
        eval_dataset=val_ds,
        tokenizer=tokenizer,
        callbacks=callbacks,
    )
    return trainer


# ─────────────────────────────────────────────────────────────
# MAIN TRAINING ENTRY POINT
# ─────────────────────────────────────────────────────────────

def train(config_path: str = "configs/config.yaml") -> None:
    with open(config_path) as f:
        cfg = yaml.safe_load(f)

    # ── W&B init ─────────────────────────────────────────────
    wb_cfg = cfg.get("wandb", {})
    wandb.init(
        project=wb_cfg.get("project", "krishimitra-llm"),
        entity=wb_cfg.get("entity"),
        config=cfg,
        tags=wb_cfg.get("tags", []),
        name=f"qlora-{cfg['base_model']['name'].split('/')[-1]}-r{cfg['lora']['r']}",
    )

    # ── Load model ────────────────────────────────────────────
    model, tokenizer = load_base_model(cfg)
    model = apply_lora(model, cfg)

    # ── Datasets ─────────────────────────────────────────────
    train_ds, val_ds = prepare_datasets(cfg, tokenizer)

    # ── Train ─────────────────────────────────────────────────
    trainer = build_trainer(model, tokenizer, train_ds, val_ds, cfg)

    log.info("Starting training — %d epochs, %d steps/epoch",
             cfg["training"]["num_train_epochs"],
             len(train_ds) // (cfg["training"]["per_device_train_batch_size"] *
                               cfg["training"]["gradient_accumulation_steps"]))

    train_result = trainer.train()

    # ── Save adapter ──────────────────────────────────────────
    output_dir = cfg["training"]["output_dir"]
    trainer.save_model(output_dir)
    tokenizer.save_pretrained(output_dir)

    metrics = train_result.metrics
    trainer.log_metrics("train", metrics)
    trainer.save_metrics("train", metrics)
    trainer.save_state()

    log.info("Training complete. Adapter saved to: %s", output_dir)

    # ── Free memory before merge ──────────────────────────────
    del model, trainer
    gc.collect()
    torch.cuda.empty_cache()

    wandb.finish()
    log.info("Done.")


if __name__ == "__main__":
    train()
