"""HTTP surface for the story-point model.

The request and response shapes are not invented here — they are what
``HttpAiModelClient`` in the Spring backend already sends and parses. It posts
``{model, version, mode, features}`` and reads ``value``, ``confidence``,
``lower_bound``, ``upper_bound``, ``drivers`` and ``explanation`` back. Matching
that contract is why the backend needs no new client code to talk to this.
"""

from __future__ import annotations

import logging
import os
import time
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .cost_predictor import CostPredictor
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
COST_INITIAL_MODEL_DIR = Path(os.getenv("SPRINTIQ_COST_INITIAL_MODEL_DIR",
                                        MODELS / "SprintCost-initial_model"))
COST_MID_MODEL_DIR = Path(os.getenv("SPRINTIQ_COST_MID_MODEL_DIR",
                                    MODELS / "SprintCost-mid_model"))

app = FastAPI(
    title="SprintIQ estimation service",
    description="Story-point estimation with calibrated uncertainty (M1) and "
                "velocity, delivery-risk, and hybrid sprint-cost prediction.",
    version="1.2.0",
)

# Loaded once at import. A 2MB bundle takes about a second to unpickle, and doing
# it per request would put that on every planning click.
predictor: StoryPointPredictor | None = None
velocity: VelocityPredictor | None = None
over_budget: RiskPredictor | None = None
requirement_change: RiskPredictor | None = None
cost_initial: CostPredictor | None = None
cost_mid: CostPredictor | None = None


@app.on_event("startup")
def load_model() -> None:
    global predictor, velocity, over_budget, requirement_change, cost_initial, cost_mid
    if not MODEL_PATH.exists():
        raise RuntimeError(
            f"No model bundle at {MODEL_PATH}. Set SPRINTIQ_MODEL_PATH or place the "
            f"pickle in ai-service/models/.")
    predictor = StoryPointPredictor(MODEL_PATH)

    # The velocity model is optional at startup rather than required: a deployment
    # that only serves story points should not fail to boot over a bundle it never
    # calls. /predict/velocity answers 503 in that case, which is the honest reply.
    if VELOCITY_MODEL_PATH.exists():
        velocity = VelocityPredictor(VELOCITY_MODEL_PATH)
    else:
        log.warning("No velocity bundle at %s — /predict/velocity will answer 503",
                    VELOCITY_MODEL_PATH)

    if OVERBUDGET_MODEL_PATH.exists():
        over_budget = RiskPredictor(OVERBUDGET_MODEL_PATH, "over_budget")
    else:
        log.warning("No over-budget bundle at %s — /predict/risk/over-budget will answer 503. "
                    "Regenerate with: python -m training.train_risk_models --data <csv folder>",
                    OVERBUDGET_MODEL_PATH)

    if REQCHANGE_MODEL_PATH.exists():
        requirement_change = RiskPredictor(REQCHANGE_MODEL_PATH, "requirement_change")
    else:
        log.warning("No requirement-change bundle at %s — /predict/risk/requirement-change "
                    "will answer 503", REQCHANGE_MODEL_PATH)

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


class InferenceRequest(BaseModel):
    """What the Spring client posts. Only ``features`` carries anything we need."""

    model: str | None = Field(default=None, description="M1_STORY_POINTS")
    version: str | None = None
    mode: str | None = None
    features: dict[str, Any] = Field(default_factory=dict)

    # 'model_' is Pydantic's own namespace; without this the 'model' field warns.
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
    return body


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
        # A 500 here is the honest answer: the backend treats it as a failed
        # prediction and shows the story unestimated rather than inventing one.
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

    contract: dict[str, Any] = {
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
    }
    if velocity is not None:
        contract["velocity"] = {
            # Listed in the order the model was fitted with; the service builds the
            # row itself, but a caller comparing contracts should see the truth.
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
        contract["cost_initial"] = {
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
        contract["cost_mid_sprint"] = {
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
    return contract
