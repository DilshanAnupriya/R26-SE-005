"""M2 sprint risk prediction, served on its own.

The same three classifiers ``app.main`` serves, without loading the M1, M3 and M4
models, so the risk component can be deployed, sized and evaluated as a separate
service. The request and response shapes are identical to ``app.main``, so the
backend's model registry can point at either one without any other change.

Run it locally with:  uvicorn app.m2_main:app --port 8000
"""

from __future__ import annotations

import logging
import os
import time
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .collaboration_features import FEATURE_RANGES
from .collaboration_predictor import CollaborationRiskPredictor
from .risk_predictor import RiskPredictor

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)-5s %(name)s: %(message)s")
log = logging.getLogger("sprintiq.m2")

MODELS = Path(__file__).resolve().parent.parent / "models"

OVERBUDGET_MODEL_PATH = Path(os.getenv("SPRINTIQ_OVERBUDGET_MODEL_PATH",
                                       MODELS / "overbudget_model_bundle.pkl"))
REQCHANGE_MODEL_PATH = Path(os.getenv("SPRINTIQ_REQCHANGE_MODEL_PATH",
                                      MODELS / "reqchange_model_bundle.pkl"))
COLLABORATION_MODEL_PATH = Path(os.getenv("SPRINTIQ_COLLABORATION_MODEL_PATH",
                                          MODELS / "communication_model"))

app = FastAPI(
    title="SprintIQ M2: sprint risk prediction",
    description="Over-budget risk, requirement-change risk and communication and "
                "collaboration risk for a sprint, each with its drivers and explanation.",
    version="2.0.0",
)

over_budget: RiskPredictor | None = None
requirement_change: RiskPredictor | None = None
collaboration: CollaborationRiskPredictor | None = None


@app.on_event("startup")
def load_models() -> None:
    global over_budget, requirement_change, collaboration

    # Unlike app.main, a missing bundle stops the service here: this deployment
    # exists only to serve these models, so booting without them would pass the
    # platform's health check while answering 503 to every request.
    over_budget = RiskPredictor(OVERBUDGET_MODEL_PATH, "over_budget")
    requirement_change = RiskPredictor(REQCHANGE_MODEL_PATH, "requirement_change")
    collaboration = CollaborationRiskPredictor(COLLABORATION_MODEL_PATH)


class InferenceRequest(BaseModel):
    """What the Spring client posts for each M2 model."""
    model: str | None = Field(default=None, description="M2 model code")
    version: str | None = None
    mode: str | None = None
    features: dict[str, Any] = Field(default_factory=dict)
    model_config = {"protected_namespaces": ()}


@app.get("/health")
def health() -> dict[str, Any]:
    if over_budget is None or requirement_change is None or collaboration is None:
        raise HTTPException(status_code=503, detail="Models not loaded")
    return {
        "status": "UP",
        "component": "M2",
        "risk": {
            name: {
                "loaded": True,
                "features": len(model.features),
                "test_auc": model.metadata.get("test_auc_binary"),
                "cv_auc": model.metadata.get("cv_mean_auc"),
            }
            for name, model in (("over_budget", over_budget),
                                ("requirement_change", requirement_change))
        } | {
            "communication_collaboration": {
                "loaded": True,
                "scorer": collaboration.scorer,
                "features": len(FEATURE_RANGES),
            },
        },
    }


@app.get("/model/contract")
def contract() -> dict[str, Any]:
    """What each M2 model accepts, so a mismatch is discoverable, not guessed at."""
    if over_budget is None or requirement_change is None or collaboration is None:
        raise HTTPException(status_code=503, detail="Models not loaded")
    return {
        "over_budget": {"fields": over_budget.features, "defaults": over_budget.medians},
        "requirement_change": {"fields": requirement_change.features,
                               "defaults": requirement_change.medians},
        "communication_collaboration": {
            "numeric_fields": sorted(FEATURE_RANGES),
            "training_ranges": {k: list(v) for k, v in FEATURE_RANGES.items()},
            "missing_value_policy": "zero (healthy), not the training median",
            "scorer": collaboration.scorer,
            "bands": collaboration.metadata.get("bands"),
        },
    }


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
