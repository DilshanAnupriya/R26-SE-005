"""M1 effort and sprint estimation, served on its own.

The story-point estimator and the next-sprint velocity model ``app.main`` serves,
without loading the M2, M3 and M4 models, so the estimation component can be
deployed, sized and evaluated as a separate service. The request and response
shapes are identical to ``app.main``, so the backend's model registry can point
at either one without any other change.

Run it locally with:  uvicorn app.m1_main:app --port 8000
"""

from __future__ import annotations

import logging
import os
import time
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .features import (KNOWN_DOMAINS, KNOWN_EXPERIENCE, KNOWN_TASK_TYPES,
                       KNOWN_TECH_STACKS, TRAINING_MEDIANS)
from .predictor import StoryPointPredictor
from .velocity_features import KNOWN_DOMAINS as VELOCITY_DOMAINS
from .velocity_features import TRAINING_MEDIANS as VELOCITY_MEDIANS
from .velocity_predictor import VelocityPredictor

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-5s %(name)s: %(message)s")
log = logging.getLogger("sprintiq.m1")

MODELS = Path(__file__).resolve().parent.parent / "models"

MODEL_PATH = Path(os.getenv("SPRINTIQ_MODEL_PATH",
                            MODELS / "model_xgb_lgbm_uncertainty.pkl"))
VELOCITY_MODEL_PATH = Path(os.getenv("SPRINTIQ_VELOCITY_MODEL_PATH",
                                     MODELS / "velocity_transfer_bundle.pkl"))

app = FastAPI(
    title="SprintIQ M1: effort and sprint estimation",
    description="Story-point estimation with a calibrated range (XGBoost with LightGBM "
                "quantile heads) and next-sprint velocity prediction with domain transfer.",
    version="2.0.0",
)

predictor: StoryPointPredictor | None = None
velocity: VelocityPredictor | None = None


@app.on_event("startup")
def load_models() -> None:
    global predictor, velocity

    # Unlike app.main, a missing velocity bundle stops the service here: this
    # deployment exists only to serve these two models, so booting without one would
    # pass the platform's health check while answering 503 to half its requests.
    predictor = StoryPointPredictor(MODEL_PATH)
    velocity = VelocityPredictor(VELOCITY_MODEL_PATH)


class InferenceRequest(BaseModel):
    """What the Spring client posts for each M1 model."""
    model: str | None = Field(default=None, description="M1_STORY_POINTS or M1_VELOCITY")
    version: str | None = None
    mode: str | None = None
    features: dict[str, Any] = Field(default_factory=dict)
    model_config = {"protected_namespaces": ()}


@app.get("/health")
def health() -> dict[str, Any]:
    if predictor is None or velocity is None:
        raise HTTPException(status_code=503, detail="Models not loaded")
    return {
        "status": "UP",
        "component": "M1",
        "story_points": {
            "loaded": True,
            "columns": int(predictor.model.n_features_in_),
            "test_mae": predictor.metrics.get("xgb_test", {}).get("mae"),
            "interval_coverage": predictor.coverage,
        },
        "velocity": {
            "loaded": True,
            "features": len(velocity.features),
            "zero_shot_mae": velocity.zero_shot_mae,
            "fine_tuned_domains": velocity.tuned_domains,
        },
    }


@app.get("/model/contract")
def contract() -> dict[str, Any]:
    """What each M1 model accepts, so a mismatch is discoverable, not guessed at."""
    if predictor is None or velocity is None:
        raise HTTPException(status_code=503, detail="Models not loaded")
    return {
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
        "velocity": {
            "features": velocity.features,
            "supplied_by_caller": sorted(VELOCITY_MEDIANS.keys()) + ["historical_velocities", "domain"],
            "derived_by_service": ["velocity_delta", "effective_capacity", "stress_score",
                                   "velocity_per_dev", "workload_ratio"],
            "domains": list(VELOCITY_DOMAINS),
            "fine_tuned_domains": velocity.tuned_domains,
            "defaults": VELOCITY_MEDIANS,
            "metrics": velocity.metrics,
        },
    }


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
