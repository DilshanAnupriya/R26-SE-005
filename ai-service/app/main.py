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

from .predictor import StoryPointPredictor

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-5s %(name)s: %(message)s")
log = logging.getLogger("sprintiq.ai")

MODEL_PATH = Path(os.getenv(
    "SPRINTIQ_MODEL_PATH",
    Path(__file__).resolve().parent.parent / "models" / "model_xgb_lgbm_uncertainty.pkl",
))

app = FastAPI(
    title="SprintIQ estimation service",
    description="Story-point estimation with calibrated uncertainty (M1).",
    version="1.0.0",
)

# Loaded once at import. A 2MB bundle takes about a second to unpickle, and doing
# it per request would put that on every planning click.
predictor: StoryPointPredictor | None = None


@app.on_event("startup")
def load_model() -> None:
    global predictor
    if not MODEL_PATH.exists():
        raise RuntimeError(
            f"No model bundle at {MODEL_PATH}. Set SPRINTIQ_MODEL_PATH or place the "
            f"pickle in ai-service/models/.")
    predictor = StoryPointPredictor(MODEL_PATH)


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
    return {
        "status": "UP",
        "columns": int(predictor.model.n_features_in_),
        "test_mae": predictor.metrics.get("xgb_test", {}).get("mae"),
        "interval_coverage": predictor.coverage,
    }


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

    return {
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
    }
