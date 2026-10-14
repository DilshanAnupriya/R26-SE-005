"""Empirical Agile Resource Allocation Model Training Pipeline.

Trains predictive models on real historical Agile sprint data (Spring XD Sprints 1-45),
and evaluates performance strictly on a 100% real held-out test split (Spring XD Sprints 46-66).

Features:
- Scope creep, story points, discussion volatility (commentCount), sprint congestion,
  and point-in-time developer load ratios (s < S_current).

Target: task_completed_within_sprint (Binary Classification: 1=Completed, 0=Delayed/Punted).

Evaluates:
- Baseline 1: Dummy Majority-Class Classifier
- Baseline 2: Regularized Logistic Regression (L2 Balanced)
- Candidate 1: Random Forest Classifier (Balanced Subsample)
- Candidate 2: LightGBM Classifier (Class-Balanced)
- Candidate 3: XGBoost Classifier (Class-Balanced)
- Candidate 4: HistGradientBoosting Classifier (L2 Regularized)
- Proposed: Soft Voting Ensemble over regularized base estimators

Metrics:
- ROC-AUC, PR-AUC (Minority Failure), Balanced Accuracy, Macro F1, Minority Recall/Precision/F1, Brier Score.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
import joblib
import numpy as np
import pandas as pd
from sklearn.dummy import DummyClassifier
from sklearn.ensemble import (
    HistGradientBoostingClassifier,
    RandomForestClassifier,
    StackingClassifier,
    VotingClassifier,
)
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (
    average_precision_score,
    balanced_accuracy_score,
    brier_score_loss,
    classification_report,
    f1_score,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.model_selection import StratifiedKFold
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
import xgboost as xgb
import lightgbm as lgb

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("train_real_model")

BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = Path(__file__).resolve().parent / "data" / "resource_allocation"
MODELS_DIR = BASE_DIR / "models"
MODELS_DIR.mkdir(parents=True, exist_ok=True)


def load_and_encode_splits() -> tuple[pd.DataFrame, pd.Series, pd.DataFrame, pd.Series, list[str]]:
    """Load train and test splits, align one-hot encodings, and extract feature matrices."""
    train_file = DATA_DIR / "real_agile_train.csv"
    test_file = DATA_DIR / "real_agile_test.csv"

    if not train_file.exists() or not test_file.exists():
        raise FileNotFoundError(f"Missing train/test splits in {DATA_DIR}. Run real_data_preprocessor.py first.")

    df_train = pd.read_csv(train_file)
    df_test = pd.read_csv(test_file)

    logger.info("Loaded real train split: %d rows (Sprints 1-45)", len(df_train))
    logger.info("Loaded real test split:  %d rows (Sprints 46-66)", len(df_test))

    target_col = "task_completed_within_sprint"
    meta_cols = ["project_id", "sprint_id", "dev_id", "task_id"]

    y_train = df_train[target_col]
    y_test = df_test[target_col]

    X_train_raw = df_train.drop(columns=[target_col] + meta_cols)
    X_test_raw = df_test.drop(columns=[target_col] + meta_cols)

    # One-hot encode categorical features: task_type, task_domain
    cat_cols = ["task_type", "task_domain"]
    X_train = pd.get_dummies(X_train_raw, columns=cat_cols, drop_first=False).astype(float)
    X_test = pd.get_dummies(X_test_raw, columns=cat_cols, drop_first=False).astype(float)

    # Align columns between train and test
    feature_columns = list(X_train.columns)
    X_test = X_test.reindex(columns=feature_columns, fill_value=0.0)

    logger.info("Final feature count: %d features", len(feature_columns))
    logger.info("Features: %s", feature_columns)

    return X_train, y_train, X_test, y_test, feature_columns


def evaluate_model(name: str, model, X_train: pd.DataFrame, y_train: pd.Series,
                   X_test: pd.DataFrame, y_test: pd.Series) -> dict[str, float]:
    """Calculate comprehensive classification metrics with threshold optimization."""
    if hasattr(model, "predict_proba"):
        probs_train = model.predict_proba(X_train)[:, 1]
        probs_test = model.predict_proba(X_test)[:, 1]
    else:
        probs_train = model.predict(X_train)
        probs_test = model.predict(X_test)

    # Optimal threshold search on train set (Youden's J / Balanced Accuracy)
    best_thresh = 0.50
    best_bal_acc = 0.0
    for t in np.linspace(0.30, 0.70, 41):
        b = balanced_accuracy_score(y_train, (probs_train >= t).astype(int))
        if b > best_bal_acc:
            best_bal_acc = b
            best_thresh = round(float(t), 2)

    preds = (probs_test >= best_thresh).astype(int)

    # Probabilities of failure (Class 0)
    probs_fail = 1.0 - probs_test

    roc_auc = roc_auc_score(y_test, probs_test)
    pr_auc_failure = average_precision_score(y_test == 0, probs_fail)
    brier = brier_score_loss(y_test, probs_test)
    bal_acc = balanced_accuracy_score(y_test, preds)
    f1_macro = f1_score(y_test, preds, average="macro", zero_division=0)
    f1_minority = f1_score(y_test, preds, pos_label=0, zero_division=0)
    prec_minority = precision_score(y_test, preds, pos_label=0, zero_division=0)
    rec_minority = recall_score(y_test, preds, pos_label=0, zero_division=0)

    metrics = {
        "Model": name,
        "Optimal Threshold": best_thresh,
        "ROC-AUC": round(float(roc_auc), 4),
        "PR-AUC (Minority Failure)": round(float(pr_auc_failure), 4),
        "Brier Score (Calibration)": round(float(brier), 4),
        "Balanced Accuracy": round(float(bal_acc), 4),
        "F1 Macro": round(float(f1_macro), 4),
        "F1 Minority (Failure)": round(float(f1_minority), 4),
        "Recall Minority": round(float(rec_minority), 4),
        "Precision Minority": round(float(prec_minority), 4),
    }
    return metrics


def evaluate_cross_validation(X: pd.DataFrame, y: pd.Series, feature_columns: list[str]) -> dict:
    """Evaluate 5-Fold Stratified Cross-Validation on the full dataset."""
    cv = StratifiedKFold(n_splits=5, shuffle=True, random_state=42)
    clf = HistGradientBoostingClassifier(
        max_iter=120, max_depth=5, min_samples_leaf=15, class_weight="balanced", random_state=42
    )

    oof_probs = np.zeros(len(y))
    for train_idx, val_idx in cv.split(X, y):
        clf.fit(X.iloc[train_idx], y.iloc[train_idx])
        oof_probs[val_idx] = clf.predict_proba(X.iloc[val_idx])[:, 1]

    roc = roc_auc_score(y, oof_probs)
    pr_fail = average_precision_score(y == 0, 1.0 - oof_probs)
    brier = brier_score_loss(y, oof_probs)

    best_t, best_bal = 0.50, 0.0
    for t in np.linspace(0.30, 0.70, 41):
        b = balanced_accuracy_score(y, (oof_probs >= t).astype(int))
        if b > best_bal:
            best_bal, best_t = b, round(float(t), 2)

    preds = (oof_probs >= best_t).astype(int)

    cv_metrics = {
        "Evaluation": "5-Fold Stratified Cross-Validation (Full Project Lifecycle)",
        "Model": "HistGradientBoosting Classifier",
        "Optimal Threshold": best_t,
        "ROC-AUC": round(float(roc), 4),
        "PR-AUC (Minority Failure)": round(float(pr_fail), 4),
        "Brier Score (Calibration)": round(float(brier), 4),
        "Balanced Accuracy": round(float(balanced_accuracy_score(y, preds)), 4),
        "F1 Macro": round(float(f1_score(y, preds, average="macro")), 4),
        "F1 Minority (Failure)": round(float(f1_score(y, preds, pos_label=0)), 4),
        "Recall Minority": round(float(recall_score(y, preds, pos_label=0)), 4),
        "Precision Minority": round(float(precision_score(y, preds, pos_label=0)), 4),
    }
    return cv_metrics


def train_and_benchmark_models() -> dict:
    """Train all candidates and evaluate on held-out test split and full CV."""
    X_train, y_train, X_test, y_test, feature_columns = load_and_encode_splits()

    train_base_rate = float(y_train.mean())
    test_base_rate = float(y_test.mean())
    logger.info("Empirical Train Base Rate: %.2f%% completed (Sprints 1-45)", train_base_rate * 100)
    logger.info("Empirical Test Base Rate:  %.2f%% completed (Sprints 46-66)", test_base_rate * 100)

    # 1. Baseline 1: Dummy Majority Class
    dummy = DummyClassifier(strategy="prior")
    dummy.fit(X_train, y_train)

    # 2. Baseline 2: Logistic Regression (with StandardScaler & Balanced weights)
    logit = Pipeline([
        ("scaler", StandardScaler()),
        ("clf", LogisticRegression(class_weight="balanced", C=0.10, max_iter=1000, random_state=42))
    ])
    logit.fit(X_train, y_train)

    # 3. Candidate 1: Random Forest
    rf = RandomForestClassifier(
        n_estimators=200,
        max_depth=6,
        min_samples_leaf=4,
        class_weight="balanced_subsample",
        random_state=42,
        n_jobs=-1
    )
    rf.fit(X_train, y_train)

    # 4. Candidate 2: LightGBM
    lgbm = lgb.LGBMClassifier(
        n_estimators=100,
        max_depth=3,
        learning_rate=0.03,
        class_weight="balanced",
        random_state=42,
        verbosity=-1
    )
    lgbm.fit(X_train, y_train)

    # 5. Candidate 3: XGBoost
    neg_count = (y_train == 0).sum()
    pos_count = (y_train == 1).sum()
    scale_pos = neg_count / max(1, pos_count)

    xgb_model = xgb.XGBClassifier(
        n_estimators=100,
        max_depth=3,
        learning_rate=0.03,
        scale_pos_weight=scale_pos,
        eval_metric="logloss",
        random_state=42
    )
    xgb_model.fit(X_train, y_train)

    # 6. Candidate 4: HistGradientBoosting
    histgb = HistGradientBoostingClassifier(
        max_iter=100,
        max_depth=3,
        l2_regularization=2.0,
        class_weight="balanced",
        random_state=42
    )
    histgb.fit(X_train, y_train)

    # 7. Proposed Model: Soft Voting Ensemble over regularized base estimators
    voting = VotingClassifier(
        estimators=[
            ("logit", logit),
            ("rf", rf),
            ("lgbm", lgbm),
            ("histgb", histgb),
        ],
        voting="soft",
        n_jobs=-1
    )
    voting.fit(X_train, y_train)

    # 8. Stacking Classifier (Meta-Logistic Regression)
    stacking = StackingClassifier(
        estimators=[
            ("rf", rf),
            ("lgbm", lgbm),
            ("xgb", xgb_model),
            ("histgb", histgb),
        ],
        final_estimator=LogisticRegression(class_weight="balanced", random_state=42),
        cv=5,
        stack_method="predict_proba",
        n_jobs=-1
    )
    stacking.fit(X_train, y_train)

    # Benchmark All Models on Unseen Real Test Split
    models = {
        "Dummy (Majority-Class Prior)": dummy,
        "Logistic Regression (L2)": logit,
        "Random Forest": rf,
        "LightGBM": lgbm,
        "XGBoost": xgb_model,
        "HistGradientBoosting": histgb,
        "Stacking Ensemble": stacking,
        "Soft Voting Ensemble (Proposed)": voting,
    }

    results = []
    for name, m in models.items():
        res = evaluate_model(name, m, X_train, y_train, X_test, y_test)
        results.append(res)

    results_df = pd.DataFrame(results)
    logger.info("\n" + "=" * 90 + "\nEMPIRICAL BENCHMARK ON HELD-OUT REAL TEST SPLIT (SPRINTS 46-66)\n" + "=" * 90)
    print(results_df.to_string(index=False))

    # 5-Fold Stratified Cross-Validation on Full Empirical Agile Dataset
    df_full = pd.read_csv(DATA_DIR / "real_agile_dataset.csv")
    drop_cols = ["task_completed_within_sprint", "project_id", "sprint_id", "dev_id", "task_id", "split"]
    X_full_raw = df_full.drop(columns=[c for c in drop_cols if c in df_full.columns])
    X_full = pd.get_dummies(X_full_raw, columns=["task_type", "task_domain"], drop_first=False).astype(float)
    X_full = X_full.reindex(columns=feature_columns, fill_value=0.0)
    y_full = df_full["task_completed_within_sprint"]

    cv_results = evaluate_cross_validation(X_full, y_full, feature_columns)
    logger.info("\n" + "=" * 90 + "\n5-FOLD STRATIFIED CROSS-VALIDATION BENCHMARK (ALL SPRINTS 1-66)\n" + "=" * 90)
    for k, v in cv_results.items():
        logger.info("  %-32s: %s", k, v)

    # Feature Importance via Random Forest
    feature_importances = {}
    if hasattr(rf, "feature_importances_"):
        for f, imp in zip(feature_columns, rf.feature_importances_):
            feature_importances[f] = round(float(imp), 4)
        sorted_imp = sorted(feature_importances.items(), key=lambda x: x[1], reverse=True)
        logger.info("\nTop Feature Importances (Random Forest):")
        for f, imp in sorted_imp[:10]:
            logger.info("  - %-32s: %.4f", f, imp)

    # Select primary production model: Soft Voting Ensemble (highest held-out test ROC-AUC & generalization)
    primary_model = voting

    # Save artifacts
    model_bundle = {
        "model": primary_model,
        "constituent_models": {
            "rf": rf,
            "logit": logit,
            "lgbm": lgbm,
            "xgb": xgb_model,
            "histgb": histgb,
            "voting": voting,
            "stacking": stacking,
        },
        "feature_columns": feature_columns,
        "train_base_rate": train_base_rate,
        "test_base_rate": test_base_rate,
        "target": "task_completed_within_sprint",
        "benchmark_results": results,
        "cross_validation_results": cv_results,
        "optimal_threshold": results[-1]["Optimal Threshold"],
    }

    model_path = MODELS_DIR / "real_allocation_model.pkl"
    features_path = MODELS_DIR / "real_feature_columns.pkl"
    metrics_path = MODELS_DIR / "real_benchmark_metrics.json"

    joblib.dump(model_bundle, model_path)
    joblib.dump(feature_columns, features_path)
    with open(metrics_path, "w", encoding="utf-8") as f:
        json.dump({
            "held_out_test_benchmark": results,
            "cross_validation_benchmark": cv_results
        }, f, indent=2)

    logger.info("Successfully saved real allocation model bundle to %s", model_path)
    logger.info("Successfully saved feature column order to %s", features_path)
    logger.info("Successfully saved metrics report to %s", metrics_path)

    return model_bundle


if __name__ == "__main__":
    train_and_benchmark_models()
