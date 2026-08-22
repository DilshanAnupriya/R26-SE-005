import pytest
import pandas as pd
from fastapi.testclient import TestClient

from app.main import app, load_model
from app.developer_ranker import rank_developers_for_task, allocate_sprint, rank_sprint_tasks
from app.explainability import ExplainabilityEngine, explain_ranking
from app.behavior_service import BehaviorService


@pytest.fixture(scope="module")
def client():
    load_model()
    return TestClient(app)


@pytest.fixture
def sample_task():
    return {
        "id": 1,
        "task_type": "feature",
        "req_frontend": 4,
        "req_backend": 3,
        "req_db": 2,
        "estimated_hours": 16,
        "story_points": 5,
        "priority": "High"
    }


@pytest.fixture
def sample_developers():
    return [
        {
            "dev_id": "DEV-001",
            "experience_level": 4,
            "skill_frontend": 5,
            "skill_backend": 3,
            "skill_db": 3,
            "current_workload": 20,
            "availability": 0.9,
            "current_tasks": 2,
            "consistency": 0.85,
            "learning_rate": 0.15
        },
        {
            "dev_id": "DEV-002",
            "experience_level": 2,
            "skill_frontend": 2,
            "skill_backend": 4,
            "skill_db": 4,
            "current_workload": 60,
            "availability": 0.5,
            "current_tasks": 5,
            "consistency": 0.70,
            "learning_rate": 0.10
        }
    ]


def test_health_includes_resource_allocation(client):
    res = client.get("/health")
    assert res.status_code == 200
    data = res.json()
    assert "resource_allocation" in data
    assert data["resource_allocation"]["loaded"] is True
    assert data["resource_allocation"]["explainability_ready"] is True
    assert data["resource_allocation"]["behavioral_service_loaded"] is True


def test_predict_developer_ranking_endpoint(client, sample_task, sample_developers):
    payload = {
        "task": sample_task,
        "developers": sample_developers
    }
    res = client.post("/predict", json=payload)
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "success"
    assert "recommendations" in data
    assert len(data["recommendations"]) == 2
    top = data["recommendations"][0]
    assert top["rank"] == 1
    assert "final_score" in top
    assert "breakdown" in top
    assert "strengths" in top


def test_allocate_sprint_endpoint(client, sample_task, sample_developers):
    tasks = [
        sample_task,
        {
            "id": 2,
            "task_type": "backend",
            "req_frontend": 1,
            "req_backend": 5,
            "req_db": 4,
            "estimated_hours": 20,
            "story_points": 5,
            "priority": "High"
        }
    ]
    payload = {
        "tasks": tasks,
        "developers": sample_developers
    }
    res = client.post("/allocate-sprint", json=payload)
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "success"
    assert "allocations" in data
    assert len(data["allocations"]) == 2


def test_rank_sprint_endpoint(client, sample_task, sample_developers):
    payload = {
        "tasks": [sample_task],
        "developers": sample_developers,
        "top_n": 2
    }
    res = client.post("/rank-sprint", json=payload)
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "success"
    assert "recommendations" in data
    assert len(data["recommendations"]) == 1


def test_explain_endpoint(client, sample_task, sample_developers):
    payload = {
        "dev_id": "DEV-001",
        "task": sample_task,
        "developers": sample_developers,
        "predicted_performance": 8.5,
        "skill_match_score": 0.85,
        "workload_balance": 0.5,
        "final_score": 75.0
    }
    res = client.post("/explain", json=payload)
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "success"
    assert "explanation" in data


def test_behavior_predict_all_endpoint(client):
    logs = [
        {
            "dev_id": "DEV-001",
            "sprint_id": 1,
            "completion_time": 4.5,
            "defects": 0,
            "velocity_contribution": 8.0,
            "blocked_tasks": 0,
            "reopened_tasks": 0
        },
        {
            "dev_id": "DEV-001",
            "sprint_id": 2,
            "completion_time": 4.0,
            "defects": 0,
            "velocity_contribution": 10.0,
            "blocked_tasks": 0,
            "reopened_tasks": 0
        }
    ]
    res = client.post("/behavior/predict-all", json={"logs": logs})
    assert res.status_code == 200
    data = res.json()
    assert "developers" in data
    assert len(data["developers"]) > 0
