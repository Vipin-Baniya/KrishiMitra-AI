"""
KrishiMitra ML — LSTM Price Forecasting Model
==============================================
Architecture:
  - Stacked Bidirectional LSTM layers
  - Temporal attention mechanism (learns which time steps matter most)
  - Direct multi-step output head (one neuron per forecast horizon)
  - Residual connections between LSTM layers for deep stacks
  - Dropout + recurrent dropout for regularisation

Training:
  - Adam with cosine-annealing LR schedule
  - Gradient clipping
  - Early stopping on validation MAE
  - Mixed precision (float16) when CUDA available

One LSTMModel instance per commodity × mandi pair.
Each instance trains separate output heads for horizons [1,3,7,14,21,30].

Usage:
    model = LSTMModel(config)
    model.fit(X_train, y_train, X_val, y_val)
    preds = model.predict(X, horizon=7)
"""

import logging
import pickle
from pathlib import Path
from typing import Optional

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.optim.lr_scheduler import CosineAnnealingWarmRestarts
from torch.utils.data import DataLoader, TensorDataset

log = logging.getLogger("lstm_model")


# ─────────────────────────────────────────────────────────────
# TEMPORAL ATTENTION
# ─────────────────────────────────────────────────────────────

class TemporalAttention(nn.Module):
    """
    Soft attention over the LSTM sequence dimension.
    Learns a weighting over time steps so the model can focus on
    the most informative recent days (e.g., last 7 days before harvest).

    Input:  (batch, seq_len, hidden_size)
    Output: (batch, hidden_size)  — context vector
    """

    def __init__(self, hidden_size: int):
        super().__init__()
        self.attn  = nn.Linear(hidden_size, hidden_size)
        self.v     = nn.Linear(hidden_size, 1, bias=False)

    def forward(self, lstm_out: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        # lstm_out: (batch, seq_len, hidden)
        energy  = torch.tanh(self.attn(lstm_out))         # (batch, seq, hidden)
        weights = self.v(energy).squeeze(-1)               # (batch, seq)
        weights = F.softmax(weights, dim=1)                # normalise over time
        context = torch.bmm(weights.unsqueeze(1), lstm_out).squeeze(1)  # (batch, hidden)
        return context, weights


# ─────────────────────────────────────────────────────────────
# LSTM ARCHITECTURE
# ─────────────────────────────────────────────────────────────

class PriceLSTM(nn.Module):
    """
    Stacked Bidirectional LSTM with temporal attention.

    Input shape:  (batch, seq_len, n_features)
    Output shape: (batch, n_horizons)

    Architecture diagram:
        Input → [BiLSTM layer 1] → Dropout → [BiLSTM layer 2]
              → Temporal Attention → [Dense 32 → ReLU] → [Output head]
    """

    def __init__(
        self,
        n_features:   int,
        hidden_sizes: list[int],
        n_horizons:   int,
        dropout:      float = 0.2,
        recurrent_dropout: float = 0.1,
        dense_layers: list[int] = None,
    ):
        super().__init__()
        self.n_features   = n_features
        self.hidden_sizes = hidden_sizes
        self.n_horizons   = n_horizons

        # Stacked BiLSTM layers
        self.lstm_layers = nn.ModuleList()
        input_size = n_features
        for i, hidden_size in enumerate(hidden_sizes):
            self.lstm_layers.append(nn.LSTM(
                input_size=input_size,
                hidden_size=hidden_size,
                num_layers=1,
                batch_first=True,
                bidirectional=True,
                dropout=0,
            ))
            # Residual projection if input/output dims differ
            bi_out = hidden_size * 2
            if input_size != bi_out:
                setattr(self, f"residual_proj_{i}", nn.Linear(input_size, bi_out))
            else:
                setattr(self, f"residual_proj_{i}", nn.Identity())
            input_size = bi_out

        self.layer_norms = nn.ModuleList([
            nn.LayerNorm(hidden_sizes[i] * 2) for i in range(len(hidden_sizes))
        ])
        self.dropout = nn.Dropout(dropout)

        # Temporal attention over last LSTM layer output
        last_bi_size = hidden_sizes[-1] * 2
        self.attention = TemporalAttention(last_bi_size)

        # Dense head
        dense_in = last_bi_size
        dense_layers = dense_layers or [32]
        dense = []
        for d_size in dense_layers:
            dense.extend([
                nn.Linear(dense_in, d_size),
                nn.ReLU(),
                nn.Dropout(dropout * 0.5),
            ])
            dense_in = d_size
        self.dense = nn.Sequential(*dense)

        # Output: one value per forecast horizon
        self.output_head = nn.Linear(dense_in, n_horizons)

        self._init_weights()

    def _init_weights(self):
        for name, param in self.named_parameters():
            if "weight_ih" in name:
                nn.init.xavier_uniform_(param)
            elif "weight_hh" in name:
                nn.init.orthogonal_(param)
            elif "bias" in name:
                nn.init.zeros_(param)

    def forward(self, x: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        """
        Args:
            x: (batch, seq_len, n_features)
        Returns:
            predictions: (batch, n_horizons)
            attn_weights: (batch, seq_len) — for interpretability
        """
        out = x
        for i, lstm in enumerate(self.lstm_layers):
            residual = getattr(self, f"residual_proj_{i}")(out)
            lstm_out, _ = lstm(out)
            lstm_out = self.dropout(lstm_out)
            lstm_out = self.layer_norms[i](lstm_out)
            out = lstm_out + residual   # residual connection

        # Attention over sequence
        context, attn_weights = self.attention(out)

        # Dense + output
        dense_out  = self.dense(context)
        predictions = self.output_head(dense_out)
        return predictions, attn_weights


# ─────────────────────────────────────────────────────────────
# SEQUENCE DATASET BUILDER
# ─────────────────────────────────────────────────────────────

class SequenceBuilder:
    """
    Converts a feature matrix + targets into overlapping sequences
    of length `seq_len` for LSTM training.
    """

    def __init__(self, seq_len: int = 60):
        self.seq_len = seq_len

    def build(
        self,
        X: np.ndarray,
        Y: np.ndarray,
    ) -> tuple[np.ndarray, np.ndarray]:
        """
        X: (n_samples, n_features)
        Y: (n_samples, n_horizons)

        Returns:
            X_seq: (n_windows, seq_len, n_features)
            Y_seq: (n_windows, n_horizons)
        """
        n = len(X)
        if n <= self.seq_len:
            raise ValueError(f"Need > {self.seq_len} samples, got {n}")

        X_seq, Y_seq = [], []
        for i in range(self.seq_len, n):
            X_seq.append(X[i - self.seq_len:i])
            Y_seq.append(Y[i])

        return np.array(X_seq, dtype=np.float32), np.array(Y_seq, dtype=np.float32)

    def build_single(self, X: np.ndarray) -> np.ndarray:
        """Build a single prediction window from the last seq_len rows."""
        if len(X) < self.seq_len:
            raise ValueError(f"Need ≥ {self.seq_len} rows for inference")
        window = X[-self.seq_len:].astype(np.float32)
        return window[np.newaxis, :, :]   # (1, seq_len, n_features)


# ─────────────────────────────────────────────────────────────
# LSTM MODEL WRAPPER
# ─────────────────────────────────────────────────────────────

class LSTMModel:
    """
    Full training + inference wrapper around PriceLSTM.

    Handles:
      - Device selection (CUDA > MPS > CPU)
      - Mixed precision training
      - Early stopping with best-model restore
      - Quantile loss option (predicts 10th, 50th, 90th percentile)
      - Serialisation (state dict + hyperparams)
    """

    HORIZONS = [1, 3, 7, 14, 21, 30]

    def __init__(self, config: dict):
        self.cfg        = config["lstm"]
        self.device     = self._get_device()
        self.net:       Optional[PriceLSTM] = None
        self.seq_builder = SequenceBuilder(self.cfg["sequence_length"])
        self.n_features  = None
        self._train_loss_history: list[float] = []
        self._val_loss_history:   list[float] = []
        log.info("LSTM device: %s", self.device)

    @staticmethod
    def _get_device() -> torch.device:
        if torch.cuda.is_available():
            return torch.device("cuda")
        if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
            return torch.device("mps")
        return torch.device("cpu")

    # ──────────────────────────────────────────────────────────
    # FIT
    # ──────────────────────────────────────────────────────────

    def fit(
        self,
        X_train:   np.ndarray,
        Y_train:   np.ndarray,
        X_val:     np.ndarray,
        Y_val:     np.ndarray,
        commodity: str = "",
        mandi:     str = "",
    ) -> "LSTMModel":
        """
        Y_train / Y_val: (n_samples, len(HORIZONS)) — multi-horizon targets.
        Build with FeatureEngineer then stack: Y = df[[target_h1, target_h3, ...]].values
        """
        self.n_features = X_train.shape[1]
        self.commodity  = commodity
        self.mandi      = mandi

        # Build sequences
        X_tr_seq, Y_tr_seq = self.seq_builder.build(X_train, Y_train)
        X_va_seq, Y_va_seq = self.seq_builder.build(X_val, Y_val)

        log.info("LSTM training: %s @ %s | train=%d val=%d | features=%d",
                 commodity, mandi, len(X_tr_seq), len(X_va_seq), self.n_features)

        # Build model
        self.net = PriceLSTM(
            n_features   = self.n_features,
            hidden_sizes = self.cfg["hidden_sizes"],
            n_horizons   = len(self.HORIZONS),
            dropout      = self.cfg["dropout"],
            recurrent_dropout = self.cfg["recurrent_dropout"],
            dense_layers = self.cfg["dense_layers"],
        ).to(self.device)

        # DataLoaders
        train_dl = self._make_loader(X_tr_seq, Y_tr_seq, shuffle=True)
        val_dl   = self._make_loader(X_va_seq, Y_va_seq, shuffle=False)

        # Optimiser + scheduler
        optimizer = torch.optim.Adam(
            self.net.parameters(),
            lr=self.cfg["learning_rate"],
            weight_decay=1e-5,
        )
        scheduler = CosineAnnealingWarmRestarts(
            optimizer, T_0=20, T_mult=2, eta_min=self.cfg["min_lr"]
        )
        scaler = torch.cuda.amp.GradScaler(enabled=self.device.type == "cuda")

        # Training loop
        best_val_loss  = float("inf")
        best_state     = None
        patience_count = 0

        for epoch in range(1, self.cfg["epochs"] + 1):
            train_loss = self._train_epoch(train_dl, optimizer, scaler)
            val_loss   = self._eval_epoch(val_dl)

            self._train_loss_history.append(train_loss)
            self._val_loss_history.append(val_loss)

            scheduler.step()

            if epoch % 10 == 0:
                lr = optimizer.param_groups[0]["lr"]
                log.info("Epoch %3d | train_MAE=%.2f  val_MAE=%.2f  lr=%.2e",
                         epoch, train_loss, val_loss, lr)

            # Early stopping
            if val_loss < best_val_loss - self.cfg["min_delta"]:
                best_val_loss  = val_loss
                best_state     = {k: v.cpu().clone() for k, v in self.net.state_dict().items()}
                patience_count = 0
            else:
                patience_count += 1
                if patience_count >= self.cfg["patience"]:
                    log.info("Early stopping at epoch %d (best val_MAE=%.2f)", epoch, best_val_loss)
                    break

        # Restore best weights
        if best_state:
            self.net.load_state_dict(best_state)
        self.net.eval()

        log.info("LSTM training complete. Best val MAE: ₹%.2f/qtl", best_val_loss)
        return self

    def _make_loader(
        self,
        X: np.ndarray,
        Y: np.ndarray,
        shuffle: bool = False,
    ) -> DataLoader:
        X_t = torch.from_numpy(X).float()
        Y_t = torch.from_numpy(Y).float()
        ds  = TensorDataset(X_t, Y_t)
        return DataLoader(
            ds,
            batch_size=self.cfg["batch_size"],
            shuffle=shuffle,
            num_workers=0,
            pin_memory=self.device.type == "cuda",
        )

    def _train_epoch(
        self,
        loader:    DataLoader,
        optimizer: torch.optim.Optimizer,
        scaler:    torch.cuda.amp.GradScaler,
    ) -> float:
        self.net.train()
        total_loss = 0.0
        n_batches  = 0

        for X_batch, Y_batch in loader:
            X_batch = X_batch.to(self.device)
            Y_batch = Y_batch.to(self.device)

            optimizer.zero_grad()
            with torch.cuda.amp.autocast(enabled=self.device.type == "cuda"):
                preds, _ = self.net(X_batch)
                # Huber loss (less sensitive to outliers than MSE)
                loss = F.huber_loss(preds, Y_batch, delta=50.0)

            scaler.scale(loss).backward()
            scaler.unscale_(optimizer)
            torch.nn.utils.clip_grad_norm_(
                self.net.parameters(), self.cfg["gradient_clip"]
            )
            scaler.step(optimizer)
            scaler.update()

            total_loss += loss.item()
            n_batches  += 1

        return total_loss / max(n_batches, 1)

    @torch.no_grad()
    def _eval_epoch(self, loader: DataLoader) -> float:
        self.net.eval()
        total_mae = 0.0
        n_batches = 0

        for X_batch, Y_batch in loader:
            X_batch = X_batch.to(self.device)
            Y_batch = Y_batch.to(self.device)
            preds, _ = self.net(X_batch)
            mae = F.l1_loss(preds, Y_batch)
            total_mae += mae.item()
            n_batches += 1

        return total_mae / max(n_batches, 1)

    # ──────────────────────────────────────────────────────────
    # PREDICT
    # ──────────────────────────────────────────────────────────

    @torch.no_grad()
    def predict(
        self,
        X: np.ndarray,
        return_attention: bool = False,
    ) -> dict:
        """
        Predict prices for all horizons from the last `seq_len` rows of X.

        Returns dict keyed by horizon:
            {1: price, 3: price, 7: price, 14: price, 21: price, 30: price}
        """
        if self.net is None:
            raise RuntimeError("Model not fitted. Call fit() first.")

        self.net.eval()
        window = self.seq_builder.build_single(X)
        X_t    = torch.from_numpy(window).float().to(self.device)

        preds, attn = self.net(X_t)
        preds_np    = preds.cpu().numpy().flatten()
        attn_np     = attn.cpu().numpy().flatten()

        result = {h: float(max(preds_np[i], 0)) for i, h in enumerate(self.HORIZONS)}

        if return_attention:
            result["attention_weights"] = attn_np.tolist()

        return result

    @torch.no_grad()
    def predict_with_uncertainty(
        self,
        X:           np.ndarray,
        n_samples:   int = 100,
    ) -> dict:
        """
        Monte Carlo Dropout uncertainty estimation.
        Runs forward pass n_samples times with dropout enabled.
        Returns mean + std per horizon.
        """
        if self.net is None:
            raise RuntimeError("Model not fitted.")

        # Enable dropout at inference
        self.net.train()

        window = self.seq_builder.build_single(X)
        X_t    = torch.from_numpy(window).float().to(self.device)

        samples = []
        for _ in range(n_samples):
            preds, _ = self.net(X_t)
            samples.append(preds.cpu().numpy().flatten())

        self.net.eval()

        samples  = np.array(samples)   # (n_samples, n_horizons)
        mean     = np.maximum(samples.mean(axis=0), 0)
        std      = samples.std(axis=0)
        lower_90 = np.maximum(np.percentile(samples, 5,  axis=0), 0)
        upper_90 = np.maximum(np.percentile(samples, 95, axis=0), 0)

        result = {}
        for i, h in enumerate(self.HORIZONS):
            result[h] = {
                "mean":     float(mean[i]),
                "std":      float(std[i]),
                "lower_90": float(lower_90[i]),
                "upper_90": float(upper_90[i]),
            }
        return result

    # ──────────────────────────────────────────────────────────
    # SERIALISATION
    # ──────────────────────────────────────────────────────────

    def save(self, path: str) -> None:
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        state = {
            "net_state_dict": self.net.state_dict() if self.net else None,
            "net_config": {
                "n_features":        self.n_features,
                "hidden_sizes":      self.cfg["hidden_sizes"],
                "n_horizons":        len(self.HORIZONS),
                "dropout":           self.cfg["dropout"],
                "recurrent_dropout": self.cfg["recurrent_dropout"],
                "dense_layers":      self.cfg["dense_layers"],
            },
            "train_loss_history": self._train_loss_history,
            "val_loss_history":   self._val_loss_history,
            "commodity":          getattr(self, "commodity", ""),
            "mandi":              getattr(self, "mandi", ""),
            "horizons":           self.HORIZONS,
        }
        torch.save(state, path)
        log.info("LSTM model saved: %s", path)

    @classmethod
    def load(cls, path: str, config: dict) -> "LSTMModel":
        obj   = cls(config)
        state = torch.load(path, map_location=obj.device)

        nc  = state["net_config"]
        obj.net = PriceLSTM(
            n_features   = nc["n_features"],
            hidden_sizes = nc["hidden_sizes"],
            n_horizons   = nc["n_horizons"],
            dropout      = nc["dropout"],
            recurrent_dropout = nc["recurrent_dropout"],
            dense_layers = nc["dense_layers"],
        ).to(obj.device)
        obj.net.load_state_dict(state["net_state_dict"])
        obj.net.eval()

        obj.n_features           = nc["n_features"]
        obj._train_loss_history  = state.get("train_loss_history", [])
        obj._val_loss_history    = state.get("val_loss_history", [])
        obj.commodity            = state.get("commodity", "")
        obj.mandi                = state.get("mandi", "")
        log.info("LSTM model loaded from %s", path)
        return obj
