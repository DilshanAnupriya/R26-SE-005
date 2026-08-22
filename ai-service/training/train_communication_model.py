"""Fine-tunes DistilBERT on the communication-risk corpus and writes the weights
the service loads.

Three labels — Low, Medium, High — over one sentence per sprint. The output goes
to ``models/communication_model/``, which ``CollaborationRiskPredictor`` picks up
at startup; without it the service scores from the composite equation instead and
says so, so this script is what upgrades the endpoint rather than what enables it.

The weights are about 270MB, which is why they are not in the repository and why
this script exists in a form that reproduces them exactly: same seed, same split,
same hyperparameters. Anyone can regenerate the artifact and compare.

Prerequisites:
    pip install -r requirements-training.txt
    python -m training.create_communication_dataset

Run:  python -m training.train_communication_model
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd
import torch
from sklearn.metrics import (accuracy_score, classification_report,
                             confusion_matrix, f1_score)
from sklearn.model_selection import train_test_split
from torch.utils.data import Dataset
from transformers import (DistilBertForSequenceClassification,
                          DistilBertTokenizerFast, EarlyStoppingCallback,
                          Trainer, TrainingArguments)

ROOT = Path(__file__).resolve().parent.parent

DEFAULT_DATASET = ROOT / "training" / "data" / "communication_dataset.csv"
DEFAULT_OUT = ROOT / "models" / "communication_model"
DEFAULT_CHECKPOINTS = ROOT / "models" / "communication_model_checkpoints"

BASE_MODEL = "distilbert-base-uncased"
RANDOM_SEED = 42
TEST_SIZE = 0.20
N_EPOCHS = 5
TRAIN_BATCH = 16
EVAL_BATCH = 32
MAX_LEN = 128
LR = 2e-5
WEIGHT_DECAY = 0.01
WARMUP_STEPS = 100

LABEL2ID = {"Low": 0, "Medium": 1, "High": 2}
ID2LABEL = {v: k for k, v in LABEL2ID.items()}
CLASS_NAMES = ["Low", "Medium", "High"]


class SprintCommunicationDataset(Dataset):
    """Tokenised sentences plus their band, in the shape Trainer expects."""

    def __init__(self, encodings: dict, labels: list[int]):
        self.encodings = encodings
        self.labels = labels

    def __len__(self) -> int:
        return len(self.labels)

    def __getitem__(self, index: int) -> dict:
        item = {key: torch.tensor(value[index]) for key, value in self.encodings.items()}
        item["labels"] = torch.tensor(self.labels[index], dtype=torch.long)
        return item


def compute_metrics(eval_pred) -> dict:
    """Macro F1 is the one that matters here.

    The corpus is balanced by construction, so accuracy and macro F1 track each
    other — but the *deployment* distribution is not balanced (most sprints are
    Low), and macro F1 is the metric that stays honest if the corpus is ever
    rebalanced to match.
    """
    logits, labels = eval_pred
    preds = np.argmax(logits, axis=-1)
    return {
        "accuracy": round(float(accuracy_score(labels, preds)), 4),
        "f1_macro": round(float(f1_score(labels, preds, average="macro", zero_division=0)), 4),
        "f1_weighted": round(float(f1_score(labels, preds, average="weighted", zero_division=0)), 4),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default=str(DEFAULT_DATASET))
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--checkpoints", default=str(DEFAULT_CHECKPOINTS))
    parser.add_argument("--epochs", type=int, default=N_EPOCHS)
    args = parser.parse_args()

    dataset_path = Path(args.data)
    if not dataset_path.exists():
        raise FileNotFoundError(
            f"No corpus at {dataset_path}. Run: python -m training.create_communication_dataset")

    print(f"Fine-tuning {BASE_MODEL}: {args.epochs} epochs, batch {TRAIN_BATCH}, lr {LR}\n")

    frame = pd.read_csv(dataset_path)
    frame["label_id"] = frame["label"].map(LABEL2ID)
    print(f"  {len(frame)} rows")
    print(frame["label"].value_counts().to_string())

    # Stratified so both splits carry all three bands in the corpus proportions;
    # a random split of 3000 rows can plausibly starve one band in validation.
    train_df, val_df = train_test_split(frame, test_size=TEST_SIZE,
                                        random_state=RANDOM_SEED,
                                        stratify=frame["label_id"])
    print(f"\n  train {len(train_df)} / validation {len(val_df)}")

    tokenizer = DistilBertTokenizerFast.from_pretrained(BASE_MODEL)
    train_enc = tokenizer(train_df["text"].tolist(), truncation=True,
                          padding=True, max_length=MAX_LEN)
    val_enc = tokenizer(val_df["text"].tolist(), truncation=True,
                        padding=True, max_length=MAX_LEN)

    train_dataset = SprintCommunicationDataset(train_enc, train_df["label_id"].tolist())
    val_dataset = SprintCommunicationDataset(val_enc, val_df["label_id"].tolist())

    model = DistilBertForSequenceClassification.from_pretrained(
        BASE_MODEL, num_labels=3, id2label=ID2LABEL, label2id=LABEL2ID)
    print(f"  {sum(p.numel() for p in model.parameters()):,} parameters")

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    Path(args.checkpoints).mkdir(parents=True, exist_ok=True)

    training_args = TrainingArguments(
        output_dir=args.checkpoints,
        num_train_epochs=args.epochs,
        per_device_train_batch_size=TRAIN_BATCH,
        per_device_eval_batch_size=EVAL_BATCH,
        learning_rate=LR,
        warmup_steps=WARMUP_STEPS,
        weight_decay=WEIGHT_DECAY,
        # Renamed from evaluation_strategy in transformers 4.46.
        eval_strategy="epoch",
        save_strategy="epoch",
        load_best_model_at_end=True,
        metric_for_best_model="f1_macro",
        greater_is_better=True,
        save_total_limit=2,
        logging_steps=50,
        report_to="none",
        seed=RANDOM_SEED,
        # Windows spawns rather than forks; extra workers deadlock more often
        # than they help on a corpus this size.
        dataloader_num_workers=0,
    )

    trainer = Trainer(model=model, args=training_args,
                      train_dataset=train_dataset, eval_dataset=val_dataset,
                      compute_metrics=compute_metrics,
                      callbacks=[EarlyStoppingCallback(early_stopping_patience=2)])
    trainer.train()

    print("\nValidation:")
    results = trainer.evaluate()
    for key in ("eval_accuracy", "eval_f1_macro", "eval_f1_weighted"):
        print(f"  {key:20s} {results.get(key, 'n/a')}")

    y_pred = np.argmax(trainer.predict(val_dataset).predictions, axis=-1)
    y_true = val_df["label_id"].tolist()
    print("\n" + classification_report(y_true, y_pred, target_names=CLASS_NAMES,
                                       zero_division=0))

    print("Confusion matrix (rows true, columns predicted):")
    print(f"  {'':10s}" + "".join(f"{name:>10s}" for name in CLASS_NAMES))
    for name, row in zip(CLASS_NAMES, confusion_matrix(y_true, y_pred)):
        print(f"  {name:10s}" + "".join(f"{value:>10d}" for value in row))

    model.save_pretrained(out_dir)
    tokenizer.save_pretrained(out_dir)
    print(f"\nWrote weights to {out_dir}")
    print("Restart the service — /predict/risk/communication-collaboration will "
          "report scorer=distilbert.")


if __name__ == "__main__":
    main()
