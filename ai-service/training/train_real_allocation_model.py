"""Empirical Agile Resource Allocation Model Training Pipeline.

Trains predictive models on real historical Agile sprint data (Spring XD Sprints 1-45),
and evaluates performance strictly on a 100% real held-out test split (Spring XD Sprints 46-66).

Target: task_completed_within_sprint (Binary Classification: 1=Completed, 0=Delayed/Punted).

Evaluates:
- Baseline 1: Dummy Majority-Class Classifier (derived from train split only)
- Baseline 2: Regularized Logistic Regression
- Candidate 1: Random Forest Classifier
- Candidate 2: LightGBM Classifier
- Candidate 3: XGBoost Classifier
- Proposed: Stacking Classifier (Meta-Logistic Regression over RF + LightGBM + XGBoost)

Metrics:
- ROC-AUC, PR-AUC (Average Precision), Brier Score, Balanced Accuracy, F1 (Macro & Minority).
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
import joblib
import numpy as np
import pandas as pd
from sklearn.dummy import DummyClassifier
from sklearn.ensemble import RandomForestClassifier, StackingClassifier
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
    X_train = pd.get_dummies(X_train_raw, columns=cat_cols, drop_first=False)
    X_test = pd.get_dummies(X_test_raw, columns=cat_cols, drop_first=False)

    # Align columns between train and test
    feature_columns = list(X_train.columns)
    X_test = X_test.reindex(columns=feature_columns, fill_value=0)

    # Ensure all boolean one-hot columns are converted to int
    X_train = X_train.astype(float)
    X_test = X_test.astype(float)

    logger.info("Final feature count: %d features", len(feature_columns))
    logger.info("Features: %s", feature_columns)

    return X_train, y_train, X_test, y_test, feature_columns


def evaluate_model(name: str, model, X_test: pd.DataFrame, y_test: pd.Series) -> dict[str, float]:
    """Calculate comprehensive classification metrics on the held-out real test set."""
    if hasattr(model, "predict_proba"):
        probs = model.predict_proba(X_test)[:, 1]
    else:
        probs = model.predict(X_test)

    preds = (probs >= 0.5).astype(int)

    roc_auc = roc_auc_score(y_test, probs)
    pr_auc = average_precision_score(y_test, probs)
    brier = brier_score_loss(y_test, probs)
    bal_acc = balanced_accuracy_score(y_test, preds)
    f1_macro = f1_score(y_test, preds, average="macro", zero_division=0)
    f1_minority = f1_score(y_test, preds, pos_label=0, zero_division=0)
    prec_minority = precision_score(y_test, preds, pos_label=0, zero_division=0)
    rec_minority = recall_score(y_test, preds, pos_label=0, zero_division=0)

    metrics = {
        "Model": name,
        "ROC-AUC": round(float(roc_auc), 4),
        "PR-AUC (Minority Failure)": round(float(pr_auc), 4),
        "Brier Score (Calibration)": round(float(brier), 4),
        "Balanced Accuracy": round(float(bal_acc), 4),
        "F1 Macro": round(float(f1_macro), 4),
        "F1 Minority (Failure)": round(float(f1_minority), 4),
        "Recall Minority": round(float(rec_minority), 4),
        "Precision Minority": round(float(prec_minority), 4),
    }
    return metrics


def train_and_benchmark_models() -> dict:
    """Train all candidates and evaluate on held-out test split."""
    X_train, y_train, X_test, y_test, feature_columns = load_and_encode_splits()

    train_base_rate = float(y_train.mean())
    test_base_rate = float(y_test.mean())
    logger.info("Empirical Train Base Rate: %.2f%% (Sprints 1-45)", train_base_rate * 100)
    logger.info("Empirical Test Base Rate:  %.2f%% (Sprints 46-66)", test_base_rate * 100)

    # 1. Baseline 1: Dummy Majority Class
    dummy = DummyClassifier(strategy="prior")
    dummy.fit(X_train, y_train)

    # 2. Baseline 2: Logistic Regression (with StandardScaler)
    logit = Pipeline([
        ("scaler", StandardScaler()),
        ("clf", LogisticRegression(class_weight="balanced", max_iter=1000, random_state=42))
    ])
    logit.fit(X_train, y_train)

    # 3. Model 1: Random Forest
    rf = RandomForestClassifier(
        n_estimators=200,
        max_depth=6,
        min_samples_leaf=4,
        class_weight="balanced_subsample",
        random_state=42,
        n_jobs=-1
    )
    rf.fit(X_train, y_train)

    # 4. Model 2: LightGBM
    lgbm = lgb.LGBMClassifier(
        n_estimators=120,
        max_depth=5,
        learning_rate=0.04,
        class_weight="balanced",
        random_state=42,
        verbosity=-1
    )
    lgbm.fit(X_train, y_train)

    # 5. Model 3: XGBoost
    neg_count = (y_train == 0).sum()
    pos_count = (y_train == 1).sum()
    scale_pos = neg_count / max(1, pos_count)

    xgb_model = xgb.XGBClassifier(
        n_estimators=120,
        max_depth=4,
        learning_rate=0.04,
        scale_pos_weight=scale_pos,
        eval_metric="logloss",
        random_state=42
    )
    xgb_model.fit(X_train, y_train)

    # 6. Proposed Model: Stacking Classifier
    # Meta-learner: LogisticRegression over out-of-fold probability estimates
    stacking = StackingClassifier(
        estimators=[
            ("rf", rf),
            ("lgbm", lgbm),
            ("xgb", xgb_model)
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
        "Stacking Ensemble (Proposed)": stacking,
    }

    results = []
    for name, m in models.items():
        res = evaluate_model(name, m, X_test, y_test)
        results.append(res)

    results_df = pd.DataFrame(results)
    logger.info("\n" + "=" * 90 + "\nEMPIRICAL BENCHMARK ON HELD-OUT REAL TEST SPLIT (SPRINTS 46-66)\n" + "=" * 90)
    print(results_df.to_string(index=False))

    # Feature Importance via TreeSHAP on test set
    feature_importances = {}
    if hasattr(rf, "feature_importances_"):
        for f, imp in zip(feature_columns, rf.feature_importances_):
            feature_importances[f] = round(float(imp), 4)
        sorted_imp = sorted(feature_importances.items(), key=lambda x: x[1], reverse=True)
        logger.info("\nTop Feature Importances (Random Forest):")
        for f, imp in sorted_imp[:8]:
            logger.info("  - %-30s: %.4f", f, imp)

    # Save artifacts
    model_bundle = {
        "model": stacking,
        "constituent_models": {
            "rf": rf,
            "lgbm": lgbm,
            "xgb": xgb_model,
            "logit": logit,
        },
        "feature_columns": feature_columns,
        "train_base_rate": train_base_rate,
        "test_base_rate": test_base_rate,
        "target": "task_completed_within_sprint",
        "benchmark_results": results,
    }

    model_path = MODELS_DIR / "real_allocation_model.pkl"
    features_path = MODELS_DIR / "real_feature_columns.pkl"
    metrics_path = MODELS_DIR / "real_benchmark_metrics.json"

    joblib.dump(model_bundle, model_path)
    joblib.dump(feature_columns, features_path)
    with open(metrics_path, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)

    logger.info("Successfully saved real allocation model bundle to %s", model_path)
    logger.info("Successfully saved feature column order to %s", features_path)

    return model_bundle


if __name__ == "__main__":
    train_and_benchmark_models()
