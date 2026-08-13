"""Trains the two CatBoost risk models and writes the bundles the service loads.

The notebooks that define these models (Overbudget_Risk_CatBoost.ipynb and
Requirement_Change_Risk_CatBoost.ipynb) save their bundles beside themselves, and
those files were not part of what was handed over. Rather than commit two binaries
whose provenance nobody can check, this reproduces them from the same CSVs with
the same seed, the same project-level split and the same hyperparameters, so the
artifacts in models/ can always be regenerated and compared.

Both models follow one shape:

  * a multiclass head over Low / Medium / High
  * a binary head over the "is it over budget / is it high change risk" flag
  * trained only on features knowable at sprint planning or mid-sprint

That last point is the important one and it comes from the notebooks, not from
here. Both datasets carry post-hoc columns — actual_hours, effort_deviation_pct,
requirement_volatility_score — which are only known once the sprint has closed and
which define the labels. Training on them would produce a model that scores
beautifully and cannot forecast anything, so they are excluded by name.

Run:  python -m training.train_risk_models --data "/path/to/csv/folder"
"""

from __future__ import annotations

import argparse
import pickle
from pathlib import Path

import numpy as np
import pandas as pd
from catboost import CatBoostClassifier, Pool
from sklearn.metrics import accuracy_score, f1_score, roc_auc_score
from sklearn.model_selection import GroupKFold
from sklearn.preprocessing import LabelEncoder

RANDOM_STATE = 42
CLASSES = ["Low", "Medium", "High"]

OVERBUDGET = {
    "csv": "sprint_metrics_realistic_train_ready.csv",
    "bundle": "overbudget_model_bundle.pkl",
    "target_multi": "risk_overbudget",
    "target_binary": "is_overbudget",
    "cat_features": ["team_type"],
    "features": [
        "team_type", "team_size", "base_velocity", "complexity", "hours_per_point",
        "sprint_capacity_hours", "planned_hours", "committed_story_points",
        "velocity_rolling_prev", "velocity_trend", "prev_carry_over_points",
        "prev_carry_over_rate", "prev_rework_score", "prev_blocked_tasks",
        "prev_reopened_tasks", "fatigue", "scope_added",
    ],
    "task": "agile_sprint_overbudget_risk_prediction",
    "extra_metadata": {
        "overbudget_threshold": 1.20,
        "risk_thresholds": {"Low": 1.10, "Medium": 1.30, "High": float("inf")},
    },
}

REQUIREMENT_CHANGE = {
    "csv": "req_change_realistic_train_ready.csv",
    "bundle": "reqchange_model_bundle.pkl",
    "target_multi": "requirement_change_risk",
    "target_binary": "is_high_change_risk",
    "cat_features": ["project_domain", "project_phase"],
    "features": [
        "project_domain", "project_phase", "team_seniority", "has_dedicated_BA",
        "requirements_doc_quality", "n_stakeholders", "baseline_story_count",
        "avg_story_length_words", "avg_acceptance_criteria_per_story",
        "ambiguity_marker_count", "stories_without_AC", "stakeholder_meetings_held",
        "prev_change_requests_count", "prev_acceptance_criteria_changes",
        "prev_story_change_ratio", "prev_high_risk_flag",
        "prev_requirement_volatility_score",
    ],
    "task": "agile_sprint_requirement_change_risk_prediction",
    "extra_metadata": {"volatility_threshold_high": "percentile_85_of_volatility_score"},
}


def split_by_project(df: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    """70/15/15 split on whole projects, never on individual sprints.

    Sprints from one project are not independent of each other — they share a team,
    a codebase and an estimating habit. Splitting rows at random would put sprint 4
    in training and sprint 5 in test, and the score would measure memorisation of
    the project rather than generalisation to a new one.
    """
    projects = df["project_id"].unique()
    np.random.seed(RANDOM_STATE)
    np.random.shuffle(projects)

    n_train = int(0.70 * len(projects))
    n_val = int(0.15 * len(projects))
    train, val, test = (projects[:n_train],
                        projects[n_train:n_train + n_val],
                        projects[n_train + n_val:])
    return (df[df["project_id"].isin(train)].reset_index(drop=True),
            df[df["project_id"].isin(val)].reset_index(drop=True),
            df[df["project_id"].isin(test)].reset_index(drop=True))


def _classifier(**overrides) -> CatBoostClassifier:
    params = dict(iterations=1000, learning_rate=0.05, depth=6, l2_leaf_reg=3,
                  auto_class_weights="Balanced", random_seed=RANDOM_STATE,
                  early_stopping_rounds=50, verbose=False)
    params.update(overrides)
    return CatBoostClassifier(**params)


def train_one(spec: dict, data_dir: Path, out_dir: Path) -> dict:
    df = pd.read_csv(data_dir / spec["csv"])
    train_df, val_df, test_df = split_by_project(df)

    features, cats = spec["features"], spec["cat_features"]

    def matrix(frame: pd.DataFrame) -> pd.DataFrame:
        X = frame[features].copy()
        for column in cats:
            X[column] = X[column].astype(str)
        return X

    X_train, X_val, X_test = matrix(train_df), matrix(val_df), matrix(test_df)

    encoder = LabelEncoder().fit(CLASSES)
    y_train_multi = encoder.transform(train_df[spec["target_multi"]])
    y_val_multi = encoder.transform(val_df[spec["target_multi"]])
    y_test_multi = encoder.transform(test_df[spec["target_multi"]])

    y_train_bin = train_df[spec["target_binary"]].values
    y_val_bin = val_df[spec["target_binary"]].values
    y_test_bin = test_df[spec["target_binary"]].values

    model_multi = _classifier(loss_function="MultiClass", eval_metric="TotalF1")
    model_multi.fit(Pool(X_train, y_train_multi, cat_features=cats),
                    eval_set=Pool(X_val, y_val_multi, cat_features=cats),
                    use_best_model=True)

    model_bin = _classifier(loss_function="Logloss", eval_metric="AUC")
    model_bin.fit(Pool(X_train, y_train_bin, cat_features=cats),
                  eval_set=Pool(X_val, y_val_bin, cat_features=cats),
                  use_best_model=True)

    # Held-out scores, on projects neither model has seen.
    proba_bin = model_bin.predict_proba(X_test)[:, 1]
    pred_bin = (proba_bin >= 0.5).astype(int)
    pred_multi = model_multi.predict(X_test).flatten().astype(int)
    proba_multi = model_multi.predict_proba(X_test)

    auc_b = roc_auc_score(y_test_bin, proba_bin)
    acc_b = accuracy_score(y_test_bin, pred_bin)
    f1_b = f1_score(y_test_bin, pred_bin)
    acc_m = accuracy_score(y_test_multi, pred_multi)
    auc_m = roc_auc_score(y_test_multi, proba_multi, multi_class="ovr")

    # Grouped CV, again by project — the number that says whether this generalises
    # to a team it has never seen, which is the only number that matters here.
    cv = []
    for tr_idx, va_idx in GroupKFold(n_splits=5).split(
            X_train, y_train_bin, groups=train_df["project_id"].values):
        fold = _classifier(iterations=300, loss_function="Logloss",
                           early_stopping_rounds=None)
        fold.fit(X_train.iloc[tr_idx], y_train_bin[tr_idx], cat_features=cats)
        cv.append(roc_auc_score(y_train_bin[va_idx],
                                fold.predict_proba(X_train.iloc[va_idx])[:, 1]))

    bundle = {
        "model_multiclass": model_multi,
        "model_binary": model_bin,
        "label_encoder": encoder,
        "predictive_features": features,
        "categorical_features": cats,
        "class_names": list(encoder.classes_),
        "metadata": {
            "model_type": "CatBoostClassifier",
            "task": spec["task"],
            "research_project": "R26-SE-005",
            "training_rows": int(len(X_train)),
            "validation_rows": int(len(X_val)),
            "test_rows": int(len(X_test)),
            "test_auc_binary": float(auc_b),
            "test_accuracy_binary": float(acc_b),
            "test_f1_binary": float(f1_b),
            "test_accuracy_multi": float(acc_m),
            "test_auc_multi": float(auc_m),
            "cv_mean_auc": float(np.mean(cv)),
            "cv_std_auc": float(np.std(cv)),
            **spec["extra_metadata"],
        },
    }

    out_dir.mkdir(parents=True, exist_ok=True)
    with open(out_dir / spec["bundle"], "wb") as handle:
        pickle.dump(bundle, handle, protocol=pickle.HIGHEST_PROTOCOL)

    print(f"{spec['bundle']}: test AUC {auc_b:.4f} | acc {acc_b:.4f} | F1 {f1_b:.4f} | "
          f"multi acc {acc_m:.4f} | grouped CV AUC {np.mean(cv):.4f} +/- {np.std(cv):.4f}")
    return bundle["metadata"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", required=True, help="folder holding the two CSVs")
    parser.add_argument("--out", default=str(Path(__file__).resolve().parent.parent / "models"))
    args = parser.parse_args()

    data_dir, out_dir = Path(args.data), Path(args.out)
    for spec in (OVERBUDGET, REQUIREMENT_CHANGE):
        train_one(spec, data_dir, out_dir)


if __name__ == "__main__":
    main()
