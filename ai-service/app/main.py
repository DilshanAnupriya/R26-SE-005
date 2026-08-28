"""HTTP surface for the story-point, velocity, risk, cost, and resource allocation models.

The request and response shapes match what the Spring backend and frontend clients expect.
"""

from __future__ import annotations

import logging
import os
import time
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
import joblib
import pandas as pd
from pydantic import BaseModel, Field

from .behavior_service import BehaviorService
from .collaboration_features import FEATURE_RANGES
from .collaboration_predictor import CollaborationRiskPredictor
from .cost_predictor import CostPredictor
from .developer_ranker import allocate_sprint, rank_developers_for_task, rank_sprint_tasks
from .explainability import ExplainabilityEngine, explain_ranking
from .predictor import StoryPointPredictor
from .risk_predictor import RiskPredictor
from .velocity_predictor import VelocityPredictor

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-5s %(name)s: %(message)s")
log = logging.getLogger("sprintiq.ai")

MODELS = Path(__file__).resolve().parent.parent / "models"

MODEL_PATH = Path(os.getenv("SPRINTIQ_MODEL_PATH",
                            MODELS / "model_xgb_lgbm_uncertainty.pkl"))
VELOCITY_MODEL_PATH = Path(os.getenv("SPRINTIQ_VELOCITY_MODEL_PATH",
                                     MODELS / "velocity_transfer_bundle.pkl"))
OVERBUDGET_MODEL_PATH = Path(os.getenv("SPRINTIQ_OVERBUDGET_MODEL_PATH",
                                       MODELS / "overbudget_model_bundle.pkl"))
REQCHANGE_MODEL_PATH = Path(os.getenv("SPRINTIQ_REQCHANGE_MODEL_PATH",
                                      MODELS / "reqchange_model_bundle.pkl"))
COLLABORATION_MODEL_PATH = Path(os.getenv("SPRINTIQ_COLLABORATION_MODEL_PATH",
                                          MODELS / "communication_model"))
COST_INITIAL_MODEL_DIR = Path(os.getenv("SPRINTIQ_COST_INITIAL_MODEL_DIR",
                                        MODELS / "SprintCost-initial_model"))
COST_MID_MODEL_DIR = Path(os.getenv("SPRINTIQ_COST_MID_MODEL_DIR",
                                    MODELS / "SprintCost-mid_model"))
ALLOCATION_MODEL_PATH = Path(os.getenv("SPRINTIQ_ALLOCATION_MODEL_PATH",
                                       MODELS / "best_model.pkl"))
ALLOCATION_FEATURES_PATH = Path(os.getenv("SPRINTIQ_ALLOCATION_FEATURES_PATH",
                                          MODELS / "feature_columns.pkl"))
CONSISTENCY_MODEL_PATH = Path(os.getenv("SPRINTIQ_CONSISTENCY_MODEL_PATH",
                                        MODELS / "consistency_model.pkl"))
LEARNING_RATE_MODEL_PATH = Path(os.getenv("SPRINTIQ_LEARNING_RATE_MODEL_PATH",
                                          MODELS / "learning_rate_model.pkl"))

app = FastAPI(
    title="SprintIQ estimation and resource allocation service",
    description="Story-point estimation with calibrated uncertainty (M1), "
                "next-sprint velocity prediction with domain transfer (M1_VELOCITY), "
                "the three sprint risk classifiers (M2), the two cost "
                "forecasters (M4), and AI Resource Allocation & Developer Ranking (M3).",
    version="2.0.0",
)

# CORS Configuration
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:8080",
        "http://localhost:3000",
        "http://localhost:3001",
        "http://127.0.0.1:8080",
        "http://127.0.0.1:3000",
        "http://127.0.0.1:3001",
        "*"
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Model instances loaded at startup
predictor: StoryPointPredictor | None = None
velocity: VelocityPredictor | None = None
over_budget: RiskPredictor | None = None
requirement_change: RiskPredictor | None = None
collaboration: CollaborationRiskPredictor | None = None
cost_initial: CostPredictor | None = None
cost_mid: CostPredictor | None = None

# Resource allocation & behavioral components
allocation_model: Any | None = None
allocation_feature_columns: list[str] | None = None
explainability_engine: ExplainabilityEngine | None = None
behavior_service: BehaviorService | None = None


@app.on_event("startup")
def load_model() -> None:
    global predictor, velocity, over_budget, requirement_change, collaboration, \
        cost_initial, cost_mid, allocation_model, allocation_feature_columns, \
        explainability_engine, behavior_service

    if not MODEL_PATH.exists():
        raise RuntimeError(
            f"No model bundle at {MODEL_PATH}. Set SPRINTIQ_MODEL_PATH or place the "
            f"pickle in ai-service/models/.")
    predictor = StoryPointPredictor(MODEL_PATH)

    if VELOCITY_MODEL_PATH.exists():
        velocity = VelocityPredictor(VELOCITY_MODEL_PATH)
    else:
        log.warning("No velocity bundle at %s — /predict/velocity will answer 503",
                    VELOCITY_MODEL_PATH)

    if OVERBUDGET_MODEL_PATH.exists():
        over_budget = RiskPredictor(OVERBUDGET_MODEL_PATH, "over_budget")
    else:
        log.warning("No over-budget bundle at %s — /predict/risk/over-budget will answer 503.",
                    OVERBUDGET_MODEL_PATH)

    if REQCHANGE_MODEL_PATH.exists():
        requirement_change = RiskPredictor(REQCHANGE_MODEL_PATH, "requirement_change")
    else:
        log.warning("No requirement-change bundle at %s — /predict/risk/requirement-change will answer 503",
                    REQCHANGE_MODEL_PATH)

    collaboration = CollaborationRiskPredictor(COLLABORATION_MODEL_PATH)

    if (COST_INITIAL_MODEL_DIR / "model.joblib").exists():
        cost_initial = CostPredictor(COST_INITIAL_MODEL_DIR, "initial")
    else:
        log.warning("No initial cost model in %s — /predict/cost/initial will answer 503",
                    COST_INITIAL_MODEL_DIR)

    if (COST_MID_MODEL_DIR / "model.joblib").exists():
        cost_mid = CostPredictor(COST_MID_MODEL_DIR, "mid")
    else:
        log.warning("No mid-sprint cost model in %s — /predict/cost/mid-sprint will answer 503",
                    COST_MID_MODEL_DIR)

    # Load Resource Allocation & Developer Ranking ML Model
    if ALLOCATION_MODEL_PATH.exists() and ALLOCATION_FEATURES_PATH.exists():
        try:
            allocation_model = joblib.load(ALLOCATION_MODEL_PATH)
            allocation_feature_columns = joblib.load(ALLOCATION_FEATURES_PATH)
            explainability_engine = ExplainabilityEngine(
                model=allocation_model,
                feature_columns=allocation_feature_columns
            )
            log.info("✓ Resource Allocation ML model and Explainability engine loaded successfully!")
        except Exception as e:
            log.exception("✗ Error loading Resource Allocation model: %s", e)
            allocation_model = None
            allocation_feature_columns = None
            explainability_engine = None
    else:
        log.warning("Resource allocation models not found at %s or %s",
                    ALLOCATION_MODEL_PATH, ALLOCATION_FEATURES_PATH)

    # Load Behavioral Service
    if CONSISTENCY_MODEL_PATH.exists() and LEARNING_RATE_MODEL_PATH.exists():
        try:
            behavior_service = BehaviorService(
                consistency_model_path=str(CONSISTENCY_MODEL_PATH),
                learning_model_path=str(LEARNING_RATE_MODEL_PATH)
            )
            log.info("✓ Behavioral profiling models loaded successfully!")
        except Exception as e:
            log.exception("✗ Error loading Behavioral models: %s", e)
            behavior_service = None
    else:
        log.warning("Behavioral models not found at %s or %s",
                    CONSISTENCY_MODEL_PATH, LEARNING_RATE_MODEL_PATH)


class InferenceRequest(BaseModel):
    """What the Spring client posts for M1, M2, M4."""
    model: str | None = Field(default=None, description="M1_STORY_POINTS")
    version: str | None = None
    mode: str | None = None
    features: dict[str, Any] = Field(default_factory=dict)
    model_config = {"protected_namespaces": ()}


@app.get("/health")
def health() -> dict[str, Any]:
    if predictor is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    body: dict[str, Any] = {
        "status": "UP",
        "columns": int(predictor.model.n_features_in_),
        "test_mae": predictor.metrics.get("xgb_test", {}).get("mae"),
        "interval_coverage": predictor.coverage,
    }
    body["velocity"] = {
        "loaded": velocity is not None,
        "features": len(velocity.features) if velocity else None,
        "zero_shot_mae": velocity.zero_shot_mae if velocity else None,
        "fine_tuned_domains": velocity.tuned_domains if velocity else [],
    }
    body["risk"] = {
        name: {
            "loaded": model is not None,
            "features": len(model.features) if model else None,
            "test_auc": model.metadata.get("test_auc_binary") if model else None,
            "cv_auc": model.metadata.get("cv_mean_auc") if model else None,
        }
        for name, model in (("over_budget", over_budget),
                            ("requirement_change", requirement_change))
    }
    body["risk"]["communication_collaboration"] = {
        "loaded": collaboration is not None,
        "scorer": collaboration.scorer if collaboration else None,
        "features": len(FEATURE_RANGES),
    }
    body["cost"] = {
        "initial": {
            "loaded": cost_initial is not None,
            "method": cost_initial.deployment_method if cost_initial else None,
            "features": len(cost_initial.raw_features) if cost_initial else None,
            "test_mape": cost_initial.test_mape if cost_initial else None,
            "test_r2": cost_initial.test_r2 if cost_initial else None,
        },
        "mid_sprint": {
            "loaded": cost_mid is not None,
            "method": cost_mid.deployment_method if cost_mid else None,
            "features": len(cost_mid.raw_features) if cost_mid else None,
            "test_mape": cost_mid.test_mape if cost_mid else None,
            "test_r2": cost_mid.test_r2 if cost_mid else None,
        },
    }
    body["resource_allocation"] = {
        "loaded": allocation_model is not None,
        "explainability_ready": explainability_engine is not None,
        "feature_count": len(allocation_feature_columns) if allocation_feature_columns else 0,
        "behavioral_service_loaded": behavior_service is not None
    }
    return body


# =====================================================================
# RESOURCE ALLOCATION & DEVELOPER RANKING ENDPOINTS (M3)
# =====================================================================

@app.post("/predict")
def predict_developer_ranking(data: dict) -> dict[str, Any]:
    """Rank developers for a single task with explainability."""
    if allocation_model is None or explainability_engine is None:
        raise HTTPException(status_code=503, detail="Resource allocation model not loaded")

    log.info("📊 RECEIVED DEVELOPER RANKING REQUEST")
    try:
        task = data.get("task")
        developers = data.get("developers")

        if not task or not developers:
            raise HTTPException(status_code=400, detail="Task and developers are required")

        dev_df = pd.DataFrame(developers)
        log.info("Processing %d developers for ranking", len(dev_df))

        ranking_df = rank_developers_for_task(
            task_profile=task,
            dev_df=dev_df,
            model=allocation_model,
            feature_columns=allocation_feature_columns
        )

        # Generate explanations for top 3
        explained_rankings = explain_ranking(
            ranking_df=ranking_df.head(3),
            dev_features=dev_df,
            task_profile=task,
            explainability_engine=explainability_engine
        )

        log.info("✓ Generated explanations for %d recommendations", len(explained_rankings))

        return {
            "status": "success",
            "task_id": task.get("id", "unknown"),
            "task_type": task.get("taskType", task.get("task_type", "unknown")),
            "total_developers": len(dev_df),
            "recommendations": explained_rankings
        }
    except HTTPException:
        raise
    except Exception as e:
        log.exception("✗ Error during developer ranking: %s", e)
        raise HTTPException(status_code=500, detail=str(e)) from e


@app.post("/allocate-sprint")
def allocate_sprint_endpoint(data: dict) -> dict[str, Any]:
    """Allocate an entire sprint using Global Bipartite Matching with explanations."""
    if allocation_model is None or explainability_engine is None:
        raise HTTPException(status_code=503, detail="Resource allocation model not loaded")

    tasks = data.get("tasks", [])
    developers = data.get("developers", [])
    log.info("🚀 SPRINT ALLOCATION: %d tasks, %d developers", len(tasks), len(developers))

    try:
        if not tasks or not developers:
            raise HTTPException(status_code=400, detail="Tasks and developers are required")

        dev_df = pd.DataFrame(developers)

        allocations = allocate_sprint(
            tasks=tasks,
            dev_df=dev_df,
            model=allocation_model,
            feature_columns=allocation_feature_columns
        )

        explained_allocations = []
        for alloc in allocations:
            task = next((t for t in tasks if str(t.get("id")) == str(alloc["task_id"]) or str(t.get("taskId")) == str(alloc["task_id"])), None)
            if not task:
                continue

            ranking_df = rank_developers_for_task(
                task_profile=task,
                dev_df=dev_df,
                model=allocation_model,
                feature_columns=allocation_feature_columns
            )

            alloc_dev_row = ranking_df[ranking_df["dev_id"] == alloc["dev_id"]]
            if alloc_dev_row.empty:
                continue

            top_dev_row = alloc_dev_row.iloc[0]

            explanation = explainability_engine.explain_recommendation(
                developer_id=alloc["dev_id"],
                task_profile=task,
                dev_features=dev_df,
                ml_score=top_dev_row["predicted_performance"],
                skill_match_score=top_dev_row["skill_match_score"],
                workload_balance=top_dev_row["workload_balance"],
                consistency=top_dev_row.get("consistency", 0.5),
                learning_rate=top_dev_row.get("learning_rate", 0.1),
                final_score=top_dev_row["final_score"],
                rank=int(top_dev_row["rank"])
            )

            explained_allocations.append({
                "task_id": alloc["task_id"],
                "allocated_to": alloc["dev_id"],
                "match_score": round(float(alloc["match_score"]), 2),
                "skill_match": round(float(alloc["skill_match"]), 3),
                "explanation": explanation
            })

        log.info("✓ Allocated %d tasks", len(explained_allocations))
        return {
            "status": "success",
            "total_tasks": len(tasks),
            "allocated": len(explained_allocations),
            "allocations": explained_allocations
        }
    except HTTPException:
        raise
    except Exception as e:
        log.exception("✗ Error during sprint allocation: %s", e)
        raise HTTPException(status_code=500, detail=str(e)) from e


@app.post("/rank-sprint")
def rank_sprint_endpoint(data: dict) -> dict[str, Any]:
    """Rank developers for all sprint tasks with virtual workload balancing."""
    if allocation_model is None or explainability_engine is None:
        raise HTTPException(status_code=503, detail="Resource allocation model not loaded")

    tasks = data.get("tasks", [])
    developers = data.get("developers", [])
    top_n = data.get("top_n", 3)

    log.info("📋 SPRINT TASK RANKING: %d tasks, %d developers", len(tasks), len(developers))
    try:
        if not tasks or not developers:
            raise HTTPException(status_code=400, detail="Tasks and developers are required")

        dev_df = pd.DataFrame(developers)

        recommendations = rank_sprint_tasks(
            tasks=tasks,
            dev_df=dev_df,
            model=allocation_model,
            feature_columns=allocation_feature_columns,
            top_n=top_n
        )

        for task_rec in recommendations:
            task = next((t for t in tasks if str(t.get("id")) == str(task_rec["task_id"]) or str(t.get("taskId")) == str(task_rec["task_id"])), None)
            if not task:
                continue

            for dev_rec in task_rec["recommendations"]:
                explanation = explainability_engine.explain_recommendation(
                    developer_id=dev_rec["dev_id"],
                    task_profile=task,
                    dev_features=dev_df,
                    ml_score=dev_rec["predicted_performance"],
                    skill_match_score=dev_rec["skill_match_score"],
                    workload_balance=dev_rec["workload_balance"],
                    consistency=dev_rec.get("consistency", 0.5),
                    learning_rate=dev_rec.get("learning_rate", 0.1),
                    final_score=dev_rec["final_score"],
                    rank=int(dev_rec["rank"])
                )
                dev_rec["explanation"] = explanation

        log.info("✓ Generated ranking explanations for %d tasks", len(recommendations))
        return {
            "status": "success",
            "total_tasks": len(tasks),
            "recommendations": recommendations
        }
    except HTTPException:
        raise
    except Exception as e:
        log.exception("✗ Error during sprint ranking: %s", e)
        raise HTTPException(status_code=500, detail=str(e)) from e


@app.post("/explain")
def explain_recommendation(data: dict) -> dict[str, Any]:
    """Get detailed explanation for a single developer recommendation."""
    if allocation_model is None or explainability_engine is None:
        raise HTTPException(status_code=503, detail="Resource allocation model not loaded")

    try:
        dev_id = data.get("dev_id")
        task = data.get("task")
        developers = data.get("developers", [])
        ml_score = data.get("predicted_performance", 0)
        skill_match = data.get("skill_match_score", 0)
        workload_balance = data.get("workload_balance", 0)
        final_score = data.get("final_score", 0)

        if not dev_id or not task or not developers:
            raise HTTPException(status_code=400, detail="dev_id, task, and developers are required")

        dev_df = pd.DataFrame(developers)

        explanation = explainability_engine.explain_recommendation(
            developer_id=dev_id,
            task_profile=task,
            dev_features=dev_df,
            ml_score=ml_score,
            skill_match_score=skill_match,
            workload_balance=workload_balance,
            consistency=data.get("consistency", 0.5),
            learning_rate=data.get("learning_rate", 0.1),
            final_score=final_score,
            rank=1
        )

        return {
            "status": "success",
            "dev_id": dev_id,
            "explanation": explanation
        }
    except HTTPException:
        raise
    except Exception as e:
        log.exception("✗ Error during explanation: %s", e)
        raise HTTPException(status_code=500, detail=str(e)) from e


@app.post("/behavior/predict-all")
def predict_behavior_all(data: dict) -> dict[str, Any]:
    """Generate behavioral profile (consistency + learning rate) from sprint logs."""
    if behavior_service is None:
        raise HTTPException(status_code=503, detail="Behavioral service not loaded")

    try:
        logs = data.get("logs", [])
        sprint_logs = pd.DataFrame(logs)
        result_df = behavior_service.generate_behavioral_profile(sprint_logs)
        return {
            "developers": result_df.to_dict(orient="records")
        }
    except Exception as e:
        log.exception("✗ Error during behavioral prediction: %s", e)
        raise HTTPException(status_code=500, detail=str(e)) from e


# =====================================================================
# ESTIMATION, RISK, AND COST PREDICTION ENDPOINTS (M1, M2, M4)
# =====================================================================

def _risk(model: RiskPredictor | None, name: str, request: InferenceRequest) -> dict[str, Any]:
    if model is None:
        raise HTTPException(status_code=503, detail=f"{name} model not loaded")

    started = time.perf_counter()
    try:
        result = model.predict(request.features)
    except Exception as error:  # noqa: BLE001
        log.exception("%s prediction failed", name)
        raise HTTPException(status_code=500, detail=str(error)) from error

    elapsed_ms = round((time.perf_counter() - started) * 1000, 1)
    log.info("%s risk: %s (%.0f%%) in %sms", name, result["risk_level"],
             result["probability"] * 100, elapsed_ms)
    return {**result, "latency_ms": elapsed_ms}


@app.post("/predict/risk/over-budget")
def predict_over_budget(request: InferenceRequest) -> dict[str, Any]:
    return _risk(over_budget, "over-budget", request)


@app.post("/predict/risk/requirement-change")
def predict_requirement_change(request: InferenceRequest) -> dict[str, Any]:
    return _risk(requirement_change, "requirement-change", request)


@app.post("/predict/risk/communication-collaboration")
def predict_communication_collaboration(request: InferenceRequest) -> dict[str, Any]:
    if collaboration is None:
        raise HTTPException(status_code=503, detail="Collaboration model not loaded")

    started = time.perf_counter()
    try:
        result = collaboration.predict(request.features)
    except Exception as error:  # noqa: BLE001
        log.exception("Collaboration prediction failed")
        raise HTTPException(status_code=500, detail=str(error)) from error

    elapsed_ms = round((time.perf_counter() - started) * 1000, 1)
    log.info("communication-collaboration risk: %s (%.0f%%) via %s in %sms",
             result["risk_level"], result["probability"] * 100,
             result["provenance"]["scorer"], elapsed_ms)
    return {**result, "latency_ms": elapsed_ms}


def _cost(model: CostPredictor | None, name: str, request: InferenceRequest) -> dict[str, Any]:
    if model is None:
        raise HTTPException(status_code=503, detail=f"{name} cost model not loaded")

    started = time.perf_counter()
    try:
        result = model.predict(request.features)
    except Exception as error:  # noqa: BLE001
        log.exception("%s cost prediction failed", name)
        raise HTTPException(status_code=500, detail=str(error)) from error

    elapsed_ms = round((time.perf_counter() - started) * 1000, 1)
    log.info("%s cost forecast: %s [%s, %s] in %sms", name, result["value"],
             result["lower_bound"], result["upper_bound"], elapsed_ms)
    return {**result, "latency_ms": elapsed_ms}


@app.post("/predict/cost/initial")
def predict_initial_cost(request: InferenceRequest) -> dict[str, Any]:
    return _cost(cost_initial, "initial", request)


@app.post("/predict/cost/mid-sprint")
def predict_mid_sprint_cost(request: InferenceRequest) -> dict[str, Any]:
    return _cost(cost_mid, "mid-sprint", request)


@app.post("/predict/velocity")
def predict_velocity(request: InferenceRequest) -> dict[str, Any]:
    if velocity is None:
        raise HTTPException(status_code=503, detail="Velocity model not loaded")

    started = time.perf_counter()
    try:
        result = velocity.predict(request.features)
    except Exception as error:  # noqa: BLE001
        log.exception("Velocity prediction failed")
        raise HTTPException(status_code=500, detail=str(error)) from error

    elapsed_ms = round((time.perf_counter() - started) * 1000, 1)
    log.info("Predicted %s points [%s, %s] via %s in %sms", result["value"],
             result["lower_bound"], result["upper_bound"],
             result["provenance"]["routed_to"], elapsed_ms)
    return {**result, "latency_ms": elapsed_ms}


@app.post("/predict/story-points")
def predict_story_points(request: InferenceRequest) -> dict[str, Any]:
    if predictor is None:
        raise HTTPException(status_code=503, detail="Model not loaded")

    started = time.perf_counter()
    try:
        result = predictor.predict(request.features)
    except Exception as error:  # noqa: BLE001
        log.exception("Prediction failed")
        raise HTTPException(status_code=500, detail=str(error)) from error

    elapsed_ms = round((time.perf_counter() - started) * 1000, 1)
    log.info("Estimated %s points [%s, %s] in %sms",
             result["value"], result["lower_bound"], result["upper_bound"], elapsed_ms)
    return {**result, "latency_ms": elapsed_ms}


@app.get("/model/contract")
def contract() -> dict[str, Any]:
    """What this service accepts — so a mismatch is discoverable, not guessed at."""
    if predictor is None:
        raise HTTPException(status_code=503, detail="Model not loaded")

    from .features import (KNOWN_DOMAINS, KNOWN_EXPERIENCE, KNOWN_TASK_TYPES,
                           KNOWN_TECH_STACKS, TRAINING_MEDIANS)
    from .velocity_features import TRAINING_MEDIANS as VELOCITY_MEDIANS
    from .velocity_features import KNOWN_DOMAINS as VELOCITY_DOMAINS

    contract_data: dict[str, Any] = {
        "story_points": {
            "text_fields": ["title", "description", "acceptance_criteria"],
            "numeric_fields": sorted(TRAINING_MEDIANS.keys()),
            "categorical_fields": {
                "task_type": list(KNOWN_TASK_TYPES),
                "domain": list(KNOWN_DOMAINS),
                "tech_stack": list(KNOWN_TECH_STACKS),
                "dev_experience_level": list(KNOWN_EXPERIENCE),
            },
            "output_scale": predictor.fib,
            "defaults": TRAINING_MEDIANS,
        },
        "communication_collaboration": {
            "numeric_fields": sorted(FEATURE_RANGES),
            "training_ranges": {k: list(v) for k, v in FEATURE_RANGES.items()},
            "missing_value_policy": "zero (healthy), not the training median",
            "scorer": collaboration.scorer if collaboration else None,
            "bands": collaboration.metadata.get("bands") if collaboration else None,
        }
    }
    if velocity is not None:
        contract_data["velocity"] = {
            "features": velocity.features,
            "supplied_by_caller": sorted(VELOCITY_MEDIANS.keys()) + ["historical_velocities", "domain"],
            "derived_by_service": ["velocity_delta", "effective_capacity", "stress_score",
                                   "velocity_per_dev", "workload_ratio"],
            "domains": list(VELOCITY_DOMAINS),
            "fine_tuned_domains": velocity.tuned_domains,
            "defaults": VELOCITY_MEDIANS,
            "metrics": velocity.metrics,
        }
    if cost_initial is not None:
        contract_data["cost_initial"] = {
            "features": cost_initial.raw_features,
            "engineered_features": cost_initial.contract.get("engineered_features", []),
            "defaults": {
                "team_size": 1,
                "developer_availability_rate": 1.0,
                "skill_match_ratio": 1.0,
                "avg_seniority_level": 2.0,
            },
            "metrics": cost_initial.metrics,
        }
    if cost_mid is not None:
        contract_data["cost_mid_sprint"] = {
            "features": cost_mid.raw_features,
            "engineered_features": cost_mid.contract.get("engineered_features", []),
            "defaults": {
                "team_size": 1,
                "developer_availability_rate": 1.0,
                "skill_match_ratio": 1.0,
                "avg_seniority_level": 2.0,
            },
            "metrics": cost_mid.metrics,
        }
    if allocation_feature_columns is not None:
        contract_data["resource_allocation"] = {
            "feature_columns": allocation_feature_columns,
            "weights": {
                "skill_match": "35%",
                "workload_balance": "30%",
                "ml_prediction": "15%",
                "consistency": "10%",
                "learning_rate": "10%"
            }
        }
    return contract_data
