"""Generates the corpus the communication-and-collaboration classifier is fitted on.

There is no observational dataset for this model, and that is not a shortcut —
communication risk has no recorded outcome the way effort overrun does. There is
no column in any sprint's history saying "this sprint failed because nobody was
talking". So the corpus is synthetic and the labels come from
:func:`app.collaboration_features.compute_risk_score`, a deterministic composite
over the five readings.

Which raises the obvious question: if the labels are a function, what is the
transformer for? Tolerance. The composite is a stack of step functions, so a
sprint at 7 and a sprint at 8 are Medium and High with nothing between them. The
fine-tuned model learns the shape of those boundaries from three thousand
examples and answers with a distribution instead of a step, which is what lets
a near-boundary sprint read as near-boundary. It is a smoothing of the equation,
not a second opinion about it — and the service says which of the two produced
any given score.

The corpus is balanced by rejection sampling rather than by weighting the loss:
uniform sampling over the feature ranges produces mostly Medium, and a model
fitted on that learns the base rate rather than the boundaries.

``features_to_text`` is imported from the app package rather than duplicated
here. The original two-file arrangement kept a copy in each and a comment in
both begging whoever edited one to edit the other; importing it makes the
requirement structural instead of hopeful.

Run:  python -m training.create_communication_dataset
"""

from __future__ import annotations

import argparse
import random
from pathlib import Path

import numpy as np
import pandas as pd

from app.collaboration_features import (FEATURE_RANGES, HIGH_BAND, MEDIUM_BAND,
                                        compute_risk_score, features_to_text)

RANDOM_SEED = 42
N_PER_CLASS = 1000  # 3000 rows total, evenly split

DEFAULT_OUT = Path(__file__).resolve().parent / "data" / "communication_dataset.csv"


def _random_features(rng: random.Random) -> tuple[int, ...]:
    """One draw, uniform over each reading's observed range."""
    return tuple(rng.randint(low, high) for low, high in FEATURE_RANGES.values())


def generate_balanced_dataset(n_per_class: int, seed: int = RANDOM_SEED) -> pd.DataFrame:
    """Exactly ``n_per_class`` rows per band, by drawing until each fills."""
    rng = random.Random(seed)

    conditions = {
        "Low": lambda s: s < MEDIUM_BAND,
        "Medium": lambda s: MEDIUM_BAND <= s < HIGH_BAND,
        "High": lambda s: s >= HIGH_BAND,
    }

    rows = []
    for label, accepts in conditions.items():
        collected = attempts = 0
        while collected < n_per_class:
            avg_rt, cpt, inactive, blockers, reopened = _random_features(rng)
            score = compute_risk_score(avg_rt, cpt, inactive, blockers, reopened)
            attempts += 1
            if not accepts(score):
                continue
            rows.append({
                "text": features_to_text(avg_rt, cpt, inactive, blockers, reopened),
                "label": label,
                "avg_response_time": avg_rt,
                "comments_per_task": cpt,
                "inactive_days": inactive,
                "blockers": blockers,
                "reopened_tasks": reopened,
                "composite_score": score,
            })
            collected += 1
        print(f"  {label:6s}: {collected} rows (accepted {collected / attempts * 100:.1f}% "
              f"of {attempts} draws)")

    # Shuffled so the three bands are not contiguous — a stratified split would
    # cope either way, but an unshuffled file makes every eyeball check of the
    # head of the CSV misleading.
    return (pd.DataFrame(rows)
            .sample(frac=1, random_state=seed)
            .reset_index(drop=True))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--per-class", type=int, default=N_PER_CLASS)
    parser.add_argument("--seed", type=int, default=RANDOM_SEED)
    args = parser.parse_args()

    random.seed(args.seed)
    np.random.seed(args.seed)

    print(f"Generating {args.per_class} rows per band ({args.per_class * 3} total)\n")
    frame = generate_balanced_dataset(args.per_class, args.seed)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    frame.to_csv(out, index=False)

    print(f"\nWrote {len(frame)} rows to {out}")
    print(frame["label"].value_counts().to_string())
    print("\nNext: python -m training.train_communication_model")


if __name__ == "__main__":
    main()
