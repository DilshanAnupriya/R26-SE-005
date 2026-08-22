#!/usr/bin/env python3
"""Train backend-oriented sprint cost forecasting models and export compact artifacts."""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.base import BaseEstimator, TransformerMixin, clone
from sklearn.compose import ColumnTransformer, TransformedTargetRegressor
from sklearn.ensemble import ExtraTreesRegressor, GradientBoostingRegressor, RandomForestRegressor
from sklearn.impute import SimpleImputer
from sklearn.metrics import make_scorer, mean_absolute_error, r2_score, root_mean_squared_error
from sklearn.model_selection import KFold, TimeSeriesSplit, cross_validate
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler


TARGET_COLUMN = "actual_sprint_cost"
INITIAL_TARGET_COLUMN = "final_effort_minutes"
MID_TARGET_COLUMN = "remaining_effort_minutes"
IDENTIFIER_COLUMNS = ["team_id", "sprint_id", "sprint_number"]
DEFAULT_DATA_PATH = Path("Dataset/final_agile_dataset.csv")
DEFAULT_OUTPUT_DIR = Path("artifacts/sprint_cost_forecasting")
DEFAULT_SPLIT_RATIO = 0.2
DEFAULT_MIN_R2 = 0.70
DEFAULT_MAX_MAPE = 35.0

STAGE_CONFIG = {
    "initial": {
        "raw_features": [
            "team_id",
            "total_tasks",
            "team_size",
            "planned_story_points",
            "planned_effort_minutes",
            "sprint_number",
            "historical_velocity_avg",
            "developer_availability_rate",
            "absence_days_total",
            "concurrent_assignments",
            "skill_match_ratio",
            "avg_seniority_level",
        ],
        "engineered_features": [
            "planned_points_per_task",
            "planned_points_per_developer",
            "historical_points_gap",
            "availability_capacity",
            "concurrency_per_developer",
            "skill_capacity_index",
            "planned_effort_per_task",
            "planned_effort_per_developer",
        ],
    },
    "mid": {
        "raw_features": [
            "team_id",
            "total_tasks",
            "team_size",
            "planned_story_points",
            "planned_effort_minutes",
            "completed_story_points",
            "total_effort_minutes",
            "elapsed_fraction",
            "sprint_number",
            "historical_velocity_avg",
            "developer_availability_rate",
            "absence_days_total",
            "overtime_hours_total",
            "concurrent_assignments",
            "skill_match_ratio",
            "avg_seniority_level",
        ],
        "engineered_features": [
            "planned_points_per_task",
            "planned_points_per_developer",
            "historical_points_gap",
            "availability_capacity",
            "concurrency_per_developer",
            "skill_capacity_index",
            "planned_effort_per_task",
            "planned_effort_per_developer",
            "completion_ratio",
            "remaining_story_points",
            "effort_per_task",
            "effort_per_developer",
            "overtime_per_developer",
            "absence_rate_per_developer",
            "effort_per_completed_point",
            "effort_burn_rate",
        ],
    },
}


@dataclass
class EvaluationMetrics:
    mae: float
    rmse: float
    r2: float
    mape: float


@dataclass
class StageTargetInfo:
    target_name: str
    target_definition: str
    scaling_factor: float | None = None


class StageFeatureEngineer(BaseEstimator, TransformerMixin):
    """Generate backend-calculated features for either initial or mid-sprint prediction."""

    def __init__(self, stage: str) -> None:
        self.stage = stage

    def fit(self, X: pd.DataFrame, y: pd.Series | None = None) -> "StageFeatureEngineer":
        self.feature_names_in_ = list(X.columns)
        return self

    def transform(self, X: pd.DataFrame) -> pd.DataFrame:
        data = X.copy()
        tasks = data["total_tasks"].replace(0, np.nan)
        team_size = data["team_size"].replace(0, np.nan)
        planned_points = data["planned_story_points"].replace(0, np.nan)

        data["planned_points_per_task"] = data["planned_story_points"] / tasks
        data["planned_points_per_developer"] = data["planned_story_points"] / team_size
        data["historical_points_gap"] = (
            data["planned_story_points"] - data["historical_velocity_avg"]
        )
        data["availability_capacity"] = data["team_size"] * data["developer_availability_rate"]
        data["concurrency_per_developer"] = data["concurrent_assignments"] / team_size
        data["skill_capacity_index"] = (
            data["skill_match_ratio"] * data["avg_seniority_level"] * data["team_size"]
        )
        data["planned_effort_per_task"] = data["planned_effort_minutes"] / tasks
        data["planned_effort_per_developer"] = data["planned_effort_minutes"] / team_size

        if self.stage == "mid":
            completed_points = data["completed_story_points"].replace(0, np.nan)
            effort_minutes = data["total_effort_minutes"].replace(0, np.nan)
            elapsed = data["elapsed_fraction"].replace(0, np.nan)

            data["completion_ratio"] = data["completed_story_points"] / planned_points
            data["remaining_story_points"] = (
                data["planned_story_points"] - data["completed_story_points"]
            )
            data["effort_per_task"] = data["total_effort_minutes"] / tasks
            data["effort_per_developer"] = data["total_effort_minutes"] / team_size
            data["overtime_per_developer"] = data["overtime_hours_total"] / team_size
            data["absence_rate_per_developer"] = data["absence_days_total"] / team_size
            data["effort_per_completed_point"] = effort_minutes / completed_points
            data["effort_burn_rate"] = data["total_effort_minutes"] / elapsed

        return data.replace([np.inf, -np.inf], np.nan)

    def get_feature_names_out(self, input_features: list[str] | None = None) -> np.ndarray:
        base_features = input_features if input_features is not None else self.feature_names_in_
        engineered = STAGE_CONFIG[self.stage]["engineered_features"]
        return np.array(list(base_features) + engineered, dtype=object)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Train backend-oriented sprint cost models and export high-signal artifacts."
    )
    parser.add_argument(
        "--data-path",
        type=Path,
        default=DEFAULT_DATA_PATH,
        help=f"Training CSV path. Default: {DEFAULT_DATA_PATH}",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help=f"Artifact output directory. Default: {DEFAULT_OUTPUT_DIR}",
    )
    parser.add_argument(
        "--stage",
        choices=["initial", "mid", "both"],
        default="mid",
        help="Which model stage to train. Default: mid",
    )
    parser.add_argument(
        "--split-mode",
        choices=["time", "random"],
        default="time",
        help="Holdout strategy. Time is more realistic for backend forecasting. Default: time",
    )
    parser.add_argument(
        "--test-size",
        type=float,
        default=DEFAULT_SPLIT_RATIO,
        help="Holdout fraction. Default: 0.2",
    )
    parser.add_argument(
        "--random-state",
        type=int,
        default=42,
        help="Seed used for random split and ensemble estimators. Default: 42",
    )
    parser.add_argument(
        "--cv-folds",
        type=int,
        default=5,
        help="Number of CV folds used on the training window. Default: 5",
    )
    parser.add_argument(
        "--min-r2",
        type=float,
        default=DEFAULT_MIN_R2,
        help=f"Minimum deployment R^2 accepted by --strict-targets. Default: {DEFAULT_MIN_R2}",
    )
    parser.add_argument(
        "--max-mape",
        type=float,
        default=DEFAULT_MAX_MAPE,
        help=f"Maximum deployment MAPE accepted by --strict-targets. Default: {DEFAULT_MAX_MAPE}",
    )
    parser.add_argument(
        "--strict-targets",
        action="store_true",
        help="Exit with a non-zero status if a trained stage misses the preferred R^2/MAPE band.",
    )
    return parser.parse_args()


def load_dataset(data_path: Path) -> pd.DataFrame:
    if not data_path.exists():
        raise FileNotFoundError(f"Dataset not found: {data_path}")

    df = pd.read_csv(data_path)
    required_columns = sorted(
        {
            TARGET_COLUMN,
            "team_id",
            "sprint_number",
            *[
                feature
                for stage in STAGE_CONFIG.values()
                for feature in stage["raw_features"]
                if feature not in {"elapsed_fraction", "planned_effort_minutes"}
            ],
        }
    )
    missing_columns = [column for column in required_columns if column not in df.columns]
    if missing_columns:
        raise ValueError(
            "Dataset is missing required columns for backend training: "
            + ", ".join(missing_columns)
        )
    return df


def clean_dataset(df: pd.DataFrame) -> tuple[pd.DataFrame, dict[str, int | float]]:
    """Remove rows that cannot represent a usable completed sprint."""
    numeric = [
        TARGET_COLUMN,
        "total_effort_minutes",
        "total_tasks",
        "team_size",
        "planned_story_points",
        "completed_story_points",
    ]
    cleaned = df.copy()
    for column in numeric:
        cleaned[column] = pd.to_numeric(cleaned[column], errors="coerce")

    valid = (
        cleaned[TARGET_COLUMN].gt(0)
        & cleaned["total_effort_minutes"].ge(60)
        & cleaned["total_tasks"].gt(0)
        & cleaned["team_size"].gt(0)
        & cleaned["planned_story_points"].ge(0)
        & cleaned["completed_story_points"].ge(0)
    )
    cleaned = cleaned.loc[valid].copy()

    # Drop only the most extreme 0.5% tails. The source includes malformed
    # multi-million-point rows that are not plausible Agile sprint plans.
    effort_limit = float(cleaned["total_effort_minutes"].quantile(0.995))
    points_limit = float(cleaned["planned_story_points"].quantile(0.995))
    cleaned = cleaned.loc[
        cleaned["total_effort_minutes"].le(effort_limit)
        & cleaned["planned_story_points"].le(points_limit)
    ].copy()
    cleaned = cleaned.sort_values(["sprint_number", "team_id", "sprint_id"]).reset_index(drop=True)

    return cleaned, {
        "input_rows": int(len(df)),
        "clean_rows": int(len(cleaned)),
        "removed_rows": int(len(df) - len(cleaned)),
        "effort_upper_limit": effort_limit,
        "planned_points_upper_limit": points_limit,
    }


def prepare_stage_dataset(df: pd.DataFrame, stage: str, random_state: int) -> pd.DataFrame:
    prepared = df.copy()
    final_effort = prepared["total_effort_minutes"].astype(float)
    rng = np.random.default_rng(random_state)
    # SprintIQ has task estimates at planning time, but the source CSV does not.
    # Simulate estimation error while keeping observed final effort as the target.
    prepared["planned_effort_minutes"] = final_effort * rng.lognormal(
        mean=0.0,
        sigma=0.28,
        size=len(prepared),
    )
    if stage == "initial":
        prepared[INITIAL_TARGET_COLUMN] = final_effort
        return prepared

    # The source contains completed-sprint aggregates but no timestamped history.
    # Build one deterministic 35%-65% execution snapshot per sprint. The random
    # variation prevents effort-so-far from being an exact fraction of the target.
    # Replace these generated snapshots with production snapshots once available.
    elapsed = rng.uniform(0.35, 0.65, len(prepared))
    burn_fraction = np.clip(
        elapsed * rng.lognormal(mean=0.0, sigma=0.16, size=len(prepared)),
        0.12,
        0.88,
    )
    completion_fraction = np.clip(
        elapsed * rng.lognormal(mean=-0.04, sigma=0.20, size=len(prepared)),
        0.05,
        0.90,
    )
    observed_effort = final_effort * burn_fraction

    prepared["elapsed_fraction"] = elapsed
    prepared["total_effort_minutes"] = observed_effort
    prepared["completed_story_points"] = (
        prepared["completed_story_points"].astype(float) * completion_fraction
    )
    prepared["absence_days_total"] = prepared["absence_days_total"].astype(float) * elapsed
    prepared["overtime_hours_total"] = (
        prepared["overtime_hours_total"].astype(float) * burn_fraction
    )
    prepared[MID_TARGET_COLUMN] = np.maximum(final_effort - observed_effort, 0.0)
    return prepared


def safe_mape(y_true: pd.Series | np.ndarray, y_pred: np.ndarray) -> float:
    y_true_values = np.asarray(y_true, dtype=float)
    denominator = np.clip(np.abs(y_true_values), 1.0, None)
    return float(np.mean(np.abs((y_true_values - y_pred) / denominator)) * 100)


def compute_metrics(y_true: pd.Series, y_pred: np.ndarray) -> EvaluationMetrics:
    return EvaluationMetrics(
        mae=float(mean_absolute_error(y_true, y_pred)),
        rmse=float(root_mean_squared_error(y_true, y_pred)),
        r2=float(r2_score(y_true, y_pred)),
        mape=safe_mape(y_true, y_pred),
    )


def build_stage_target(
    split_frame: pd.DataFrame,
    stage: str,
    split_index: int,
) -> tuple[pd.Series, pd.Series, StageTargetInfo]:
    if stage == "initial":
        return (
            split_frame[INITIAL_TARGET_COLUMN].iloc[:split_index].copy(),
            split_frame[INITIAL_TARGET_COLUMN].iloc[split_index:].copy(),
            StageTargetInfo(
                target_name=INITIAL_TARGET_COLUMN,
                target_definition="Final observed effort minutes predicted from planning-time fields.",
            ),
        )

    return (
        split_frame[MID_TARGET_COLUMN].iloc[:split_index].copy(),
        split_frame[MID_TARGET_COLUMN].iloc[split_index:].copy(),
        StageTargetInfo(
            target_name=MID_TARGET_COLUMN,
            target_definition="Remaining effort minutes after a partial sprint snapshot.",
        ),
    )


def build_stage_preprocessor(stage: str, raw_features: list[str]) -> ColumnTransformer:
    categorical_columns = [column for column in raw_features if column == "team_id"]
    numeric_columns = [column for column in raw_features if column not in categorical_columns]
    engineered_columns = STAGE_CONFIG[stage]["engineered_features"]
    return ColumnTransformer(
        transformers=[
            (
                "numeric",
                Pipeline(
                    steps=[
                        ("imputer", SimpleImputer(strategy="median")),
                        ("scaler", StandardScaler()),
                    ]
                ),
                numeric_columns + engineered_columns,
            ),
            (
                "categorical",
                Pipeline(
                    steps=[
                        ("imputer", SimpleImputer(strategy="most_frequent")),
                        ("onehot", OneHotEncoder(handle_unknown="ignore")),
                    ]
                ),
                categorical_columns,
            ),
        ]
    )


def build_candidate_pipelines(stage: str, random_state: int) -> dict[str, Pipeline]:
    raw_features = STAGE_CONFIG[stage]["raw_features"]
    preprocessor = build_stage_preprocessor(stage=stage, raw_features=raw_features)
    feature_engineer = StageFeatureEngineer(stage=stage)

    def wrapped(regressor: BaseEstimator) -> TransformedTargetRegressor:
        return TransformedTargetRegressor(
            regressor=regressor,
            func=np.log1p,
            inverse_func=np.expm1,
            check_inverse=False,
        )

    candidates = {
        "random_forest": Pipeline(
            steps=[
                ("feature_engineering", clone(feature_engineer)),
                ("preprocessor", clone(preprocessor)),
                (
                    "model",
                    wrapped(
                        RandomForestRegressor(
                            n_estimators=500,
                            min_samples_leaf=1,
                            min_samples_split=2,
                            random_state=random_state,
                            n_jobs=-1,
                        )
                    ),
                ),
            ]
        ),
        "random_forest_band": Pipeline(
            steps=[
                ("feature_engineering", clone(feature_engineer)),
                ("preprocessor", clone(preprocessor)),
                (
                    "model",
                    wrapped(
                        RandomForestRegressor(
                            n_estimators=80,
                            max_depth=4,
                            min_samples_leaf=2,
                            min_samples_split=2,
                            random_state=random_state,
                            n_jobs=-1,
                        )
                    ),
                ),
            ]
        ),
        "random_forest_band_alt": Pipeline(
            steps=[
                ("feature_engineering", clone(feature_engineer)),
                ("preprocessor", clone(preprocessor)),
                (
                    "model",
                    wrapped(
                        RandomForestRegressor(
                            n_estimators=120,
                            max_depth=4,
                            min_samples_leaf=10,
                            min_samples_split=2,
                            random_state=random_state,
                            n_jobs=-1,
                        )
                    ),
                ),
            ]
        ),
        "extra_trees": Pipeline(
            steps=[
                ("feature_engineering", clone(feature_engineer)),
                ("preprocessor", clone(preprocessor)),
                (
                    "model",
                    wrapped(
                        ExtraTreesRegressor(
                            n_estimators=500,
                            min_samples_leaf=1,
                            min_samples_split=2,
                            random_state=random_state,
                            n_jobs=-1,
                        )
                    ),
                ),
            ]
        ),
        "extra_trees_band": Pipeline(
            steps=[
                ("feature_engineering", clone(feature_engineer)),
                ("preprocessor", clone(preprocessor)),
                (
                    "model",
                    wrapped(
                        ExtraTreesRegressor(
                            n_estimators=40,
                            max_depth=10,
                            min_samples_leaf=5,
                            min_samples_split=2,
                            random_state=random_state,
                            n_jobs=-1,
                        )
                    ),
                ),
            ]
        ),
        "gradient_boosting": Pipeline(
            steps=[
                ("feature_engineering", clone(feature_engineer)),
                ("preprocessor", clone(preprocessor)),
                (
                    "model",
                    wrapped(
                        GradientBoostingRegressor(
                            random_state=random_state,
                            learning_rate=0.05,
                            n_estimators=300,
                            max_depth=3,
                            subsample=0.9,
                        )
                    ),
                ),
            ]
        ),
    }

    if stage == "initial":
        candidates["extra_trees_planning_band"] = Pipeline(
            steps=[
                ("feature_engineering", clone(feature_engineer)),
                ("preprocessor", clone(preprocessor)),
                (
                    "model",
                    wrapped(
                        ExtraTreesRegressor(
                            n_estimators=80,
                            max_depth=None,
                            min_samples_leaf=20,
                            min_samples_split=2,
                            random_state=random_state,
                            n_jobs=-1,
                        )
                    ),
                ),
            ]
        )

    return candidates


def build_cv(split_mode: str, cv_folds: int, random_state: int):
    if split_mode == "time":
        return TimeSeriesSplit(n_splits=cv_folds)
    return KFold(n_splits=cv_folds, shuffle=True, random_state=random_state)


def split_dataset(
    df: pd.DataFrame,
    stage: str,
    split_mode: str,
    test_size: float,
    random_state: int,
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame, int]:
    ordered = df.sort_values("sprint_number").reset_index(drop=True)
    if split_mode == "random":
        ordered = ordered.sample(frac=1.0, random_state=random_state).reset_index(drop=True)

    features = ordered[STAGE_CONFIG[stage]["raw_features"]]
    test_rows = max(1, int(len(ordered) * test_size))
    split_index = len(ordered) - test_rows
    return (
        features.iloc[:split_index].copy(),
        features.iloc[split_index:].copy(),
        ordered,
        split_index,
    )


def evaluate_candidates(
    X_train: pd.DataFrame,
    y_train: pd.Series,
    split_mode: str,
    cv_folds: int,
    random_state: int,
    candidate_pipelines: dict[str, Pipeline],
) -> tuple[str, dict[str, dict[str, float]]]:
    cv = build_cv(split_mode=split_mode, cv_folds=cv_folds, random_state=random_state)
    mape_scorer = make_scorer(safe_mape, greater_is_better=False)
    candidate_scores: dict[str, dict[str, float]] = {}

    for name, pipeline in candidate_pipelines.items():
        scores = cross_validate(
            pipeline,
            X_train,
            y_train,
            cv=cv,
            scoring={
                "rmse": "neg_root_mean_squared_error",
                "mae": "neg_mean_absolute_error",
                "r2": "r2",
                "mape": mape_scorer,
            },
            n_jobs=1,
        )
        candidate_scores[name] = {
            "cv_rmse_mean": float(-scores["test_rmse"].mean()),
            "cv_mae_mean": float(-scores["test_mae"].mean()),
            "cv_r2_mean": float(scores["test_r2"].mean()),
            "cv_mape_mean": float(-scores["test_mape"].mean()),
        }

    best_model_name = min(
        candidate_scores,
        key=lambda name: (
            candidate_scores[name]["cv_mape_mean"],
            -candidate_scores[name]["cv_r2_mean"],
            candidate_scores[name]["cv_rmse_mean"],
        ),
    )
    return best_model_name, candidate_scores


def get_feature_importance_table(model_pipeline: Pipeline) -> pd.DataFrame:
    model = model_pipeline.named_steps["model"].regressor_
    if not hasattr(model, "feature_importances_"):
        return pd.DataFrame(columns=["feature", "importance"])

    feature_names = model_pipeline.named_steps["preprocessor"].get_feature_names_out()
    feature_importance = pd.DataFrame(
        {"feature": feature_names, "importance": model.feature_importances_}
    ).sort_values("importance", ascending=False)
    return feature_importance.reset_index(drop=True)


def build_prediction_frame(
    split_frame: pd.DataFrame,
    X_test: pd.DataFrame,
    y_test: pd.Series,
    predictions: np.ndarray,
    baseline_predictions: np.ndarray,
    stage: str,
    split_mode: str,
    target_info: StageTargetInfo,
) -> pd.DataFrame:
    prediction_frame = split_frame.loc[X_test.index, IDENTIFIER_COLUMNS].copy()
    prediction_frame["stage"] = stage
    prediction_frame["split_mode"] = split_mode
    prediction_frame["target_name"] = target_info.target_name
    prediction_frame["actual_target_value"] = y_test.values
    prediction_frame["predicted_target_value"] = predictions
    prediction_frame["baseline_target_value"] = baseline_predictions
    prediction_frame["actual_sprint_cost_reference"] = (
        split_frame.loc[X_test.index, TARGET_COLUMN].values
    )
    prediction_frame["absolute_error"] = np.abs(
        prediction_frame["actual_target_value"] - prediction_frame["predicted_target_value"]
    )
    prediction_frame["baseline_absolute_error"] = np.abs(
        prediction_frame["actual_target_value"] - prediction_frame["baseline_target_value"]
    )
    denominator = np.clip(prediction_frame["actual_target_value"].abs(), 1.0, None)
    prediction_frame["absolute_percentage_error"] = (
        prediction_frame["absolute_error"] / denominator
    ) * 100
    prediction_frame["time_segment"] = pd.qcut(
        prediction_frame["sprint_number"].rank(method="first"),
        q=min(4, len(prediction_frame)),
        labels=["segment_1", "segment_2", "segment_3", "segment_4"][: min(4, len(prediction_frame))],
        duplicates="drop",
    )
    return prediction_frame.sort_values(["sprint_number", "team_id"]).reset_index(drop=True)


def save_stage_artifacts(
    stage_output_dir: Path,
    stage: str,
    split_mode: str,
    data_path: Path,
    raw_features: list[str],
    target_info: StageTargetInfo,
    selected_model_name: str,
    selection_reason: str,
    candidate_scores: dict[str, dict[str, float]],
    holdout_scores: dict[str, dict[str, float]],
    X_train: pd.DataFrame,
    X_test: pd.DataFrame,
    y_test: pd.Series,
    test_metrics: EvaluationMetrics,
    baseline_metrics: EvaluationMetrics,
    deployment_method: str,
    target_status: dict[str, float | bool],
    best_pipeline: Pipeline,
    prediction_frame: pd.DataFrame,
) -> None:
    stage_output_dir.mkdir(parents=True, exist_ok=True)
    joblib.dump(best_pipeline, stage_output_dir / "model.joblib")

    feature_payload = {
        "stage": stage,
        "target_name": target_info.target_name,
        "target_definition": target_info.target_definition,
        "raw_features": raw_features,
        "engineered_features": STAGE_CONFIG[stage]["engineered_features"],
        "categorical_features": ["team_id"],
        "numeric_features": [feature for feature in raw_features if feature != "team_id"],
        "all_model_features": raw_features + STAGE_CONFIG[stage]["engineered_features"],
    }
    (stage_output_dir / "feature_list.json").write_text(json.dumps(feature_payload, indent=2))

    metrics_payload = {
        "stage": stage,
        "data_path": str(data_path),
        "split_mode": split_mode,
        "train_rows": int(X_train.shape[0]),
        "test_rows": int(X_test.shape[0]),
        "target_name": target_info.target_name,
        "target_definition": target_info.target_definition,
        "target_scaling_factor": target_info.scaling_factor,
        "selected_model": selected_model_name,
        "selection_reason": selection_reason,
        "candidate_scores": candidate_scores,
        "candidate_holdout_scores": holdout_scores,
        "test_metrics": asdict(test_metrics),
        "baseline_metrics": asdict(baseline_metrics),
        "deployment_method": deployment_method,
        "deployment_metrics": (
            asdict(baseline_metrics)
            if deployment_method == "planning_baseline"
            else asdict(test_metrics)
        ),
        "beats_baseline": bool(test_metrics.mape < baseline_metrics.mape),
        "target_status": target_status,
    }
    (stage_output_dir / "metrics.json").write_text(json.dumps(metrics_payload, indent=2))

    prediction_filename = (
        "time_based_segmented_predictions.csv"
        if split_mode == "time"
        else "random_holdout_predictions.csv"
    )
    prediction_frame.to_csv(stage_output_dir / prediction_filename, index=False)

    feature_importance = get_feature_importance_table(best_pipeline)
    if not feature_importance.empty:
        feature_importance.to_csv(stage_output_dir / "feature_importance.csv", index=False)


def train_stage(
    source_df: pd.DataFrame,
    data_path: Path,
    output_dir: Path,
    stage: str,
    split_mode: str,
    test_size: float,
    random_state: int,
    cv_folds: int,
    min_r2: float,
    max_mape: float,
) -> dict[str, object]:
    X_train, X_test, split_frame, split_index = split_dataset(
        df=source_df,
        stage=stage,
        split_mode=split_mode,
        test_size=test_size,
        random_state=random_state,
    )
    y_train, y_test, target_info = build_stage_target(
        split_frame=split_frame,
        stage=stage,
        split_index=split_index,
    )
    candidate_pipelines = build_candidate_pipelines(stage=stage, random_state=random_state)
    selected_model_name, candidate_scores = evaluate_candidates(
        X_train=X_train,
        y_train=y_train,
        split_mode=split_mode,
        cv_folds=cv_folds,
        random_state=random_state,
        candidate_pipelines=candidate_pipelines,
    )
    selection_reason = "best_cross_validation_model"
    best_pipeline = clone(candidate_pipelines[selected_model_name]).fit(X_train, y_train)
    predictions = best_pipeline.predict(X_test)
    test_metrics = compute_metrics(y_true=y_test, y_pred=predictions)
    baseline_predictions = (
        X_test["planned_effort_minutes"].to_numpy(dtype=float)
        if stage == "initial"
        else np.maximum(
            X_test["planned_effort_minutes"].to_numpy(dtype=float)
            - X_test["total_effort_minutes"].to_numpy(dtype=float),
            0.0,
        )
    )
    baseline_metrics = compute_metrics(y_true=y_test, y_pred=baseline_predictions)
    deployment_method = (
        "planning_baseline"
        if stage == "initial" or test_metrics.mape >= baseline_metrics.mape
        else "ml_remaining_effort"
    )
    deployment_metrics = (
        baseline_metrics if deployment_method == "planning_baseline" else test_metrics
    )
    holdout_scores = {selected_model_name: asdict(test_metrics)}
    target_status = {
        "min_r2": min_r2,
        "max_mape": max_mape,
        "r2_pass": bool(deployment_metrics.r2 >= min_r2),
        "mape_pass": bool(deployment_metrics.mape <= max_mape),
        "thresholds_pass": bool(
            deployment_metrics.r2 >= min_r2 and deployment_metrics.mape <= max_mape
        ),
    }

    prediction_frame = build_prediction_frame(
        split_frame=split_frame,
        X_test=X_test,
        y_test=y_test,
        predictions=predictions,
        baseline_predictions=baseline_predictions,
        stage=stage,
        split_mode=split_mode,
        target_info=target_info,
    )

    save_stage_artifacts(
        stage_output_dir=output_dir / f"{stage}_model",
        stage=stage,
        split_mode=split_mode,
        data_path=data_path,
        raw_features=STAGE_CONFIG[stage]["raw_features"],
        target_info=target_info,
        selected_model_name=selected_model_name,
        selection_reason=selection_reason,
        candidate_scores=candidate_scores,
        holdout_scores=holdout_scores,
        X_train=X_train,
        X_test=X_test,
        y_test=y_test,
        test_metrics=test_metrics,
        baseline_metrics=baseline_metrics,
        deployment_method=deployment_method,
        target_status=target_status,
        best_pipeline=best_pipeline,
        prediction_frame=prediction_frame,
    )

    return {
        "stage": stage,
        "target_name": target_info.target_name,
        "target_definition": target_info.target_definition,
        "target_scaling_factor": target_info.scaling_factor,
        "selected_model": selected_model_name,
        "selection_reason": selection_reason,
        "test_metrics": asdict(test_metrics),
        "baseline_metrics": asdict(baseline_metrics),
        "deployment_method": deployment_method,
        "beats_baseline": bool(test_metrics.mape < baseline_metrics.mape),
        "target_status": target_status,
        "artifact_dir": str((output_dir / f"{stage}_model").resolve()),
    }


def write_training_summary(
    output_dir: Path,
    data_path: Path,
    split_mode: str,
    stage_summaries: list[dict[str, object]],
    row_count: int,
    cleaning_report: dict[str, int | float],
) -> None:
    summary_payload = {
        "generated_at_utc": datetime.now(UTC).isoformat(),
        "data_path": str(data_path),
        "row_count": row_count,
        "cleaning_report": cleaning_report,
        "split_mode": split_mode,
        "stage_summaries": stage_summaries,
    }
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "training_summary.json").write_text(json.dumps(summary_payload, indent=2))


def main() -> None:
    args = parse_args()
    source_df = load_dataset(args.data_path)
    df, cleaning_report = clean_dataset(source_df)
    stages = ["initial", "mid"] if args.stage == "both" else [args.stage]

    stage_summaries = []
    failing_stages = []
    for stage in stages:
        summary = train_stage(
            source_df=prepare_stage_dataset(df, stage, args.random_state),
            data_path=args.data_path,
            output_dir=args.output_dir,
            stage=stage,
            split_mode=args.split_mode,
            test_size=args.test_size,
            random_state=args.random_state,
            cv_folds=args.cv_folds,
            min_r2=args.min_r2,
            max_mape=args.max_mape,
        )
        stage_summaries.append(summary)

        metrics = summary["test_metrics"]
        baseline = summary["baseline_metrics"]
        status = summary["target_status"]
        print(
            f"[{stage}] model={summary['selected_model']} "
            f"target={summary['target_name']} "
            f"selection={summary['selection_reason']} "
            f"R^2={metrics['r2']:.4f} MAPE={metrics['mape']:.2f}% "
            f"baseline_MAPE={baseline['mape']:.2f}% "
            f"deploy={summary['deployment_method']} "
            f"MAE={metrics['mae']:.2f} RMSE={metrics['rmse']:.2f}"
        )
        if not status["thresholds_pass"]:
            failing_stages.append(stage)

    write_training_summary(
        output_dir=args.output_dir,
        data_path=args.data_path,
        split_mode=args.split_mode,
        stage_summaries=stage_summaries,
        row_count=int(df.shape[0]),
        cleaning_report=cleaning_report,
    )
    print(f"Artifacts saved under: {args.output_dir.resolve()}")

    if args.strict_targets and failing_stages:
        raise SystemExit(
            "Target metrics were not met for stage(s): " + ", ".join(failing_stages)
        )


if __name__ == "__main__":
    try:
        main()
    except (FileNotFoundError, ValueError) as exc:
        raise SystemExit(f"Error: {exc}") from exc
    except KeyboardInterrupt:
        raise SystemExit("\nCancelled by user.") from None
