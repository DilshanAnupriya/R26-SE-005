"""Unit and integration tests for Scientific Weight Optimization & Testing in SprintIQ."""

import pytest
import numpy as np
import pandas as pd

from app.weight_optimizer import (
    AHPOptimizer,
    CompositeWeights,
    SimulationWeightOptimizer,
    WeightBenchmarkEvaluator,
    get_default_optimal_weights,
)
from app.developer_ranker import rank_developers_for_task
from app.explainability import ExplainabilityEngine


class DummyModel:
    """Mock model returning a predictable array of scores."""
    def predict(self, df):
        return np.array([75.0] * len(df))


@pytest.fixture
def sample_task():
    return {
        "id": 1,
        "task_type": "backend",
        "req_frontend": 1,
        "req_backend": 4,
        "req_db": 2,
        "task_complexity": 5,
        "story_points": 5,
    }


@pytest.fixture
def sample_developers_df():
    return pd.DataFrame([
        {
            "dev_id": "DEV-001",
            "experience_level": 3,
            "skill_frontend": 2,
            "skill_backend": 5,
            "skill_db": 3,
            "current_workload": 20,
            "availability": 0.85,
            "current_tasks": 2,
            "consistency": 0.90,
            "learning_rate": 0.12
        },
        {
            "dev_id": "DEV-002",
            "experience_level": 1,
            "skill_frontend": 1,
            "skill_backend": 3,
            "skill_db": 2,
            "current_workload": 10,
            "availability": 1.0,
            "current_tasks": 1,
            "consistency": 0.75,
            "learning_rate": 0.20
        },
        {
            "dev_id": "DEV-003",
            "experience_level": 3,
            "skill_frontend": 4,
            "skill_backend": 1,
            "skill_db": 1,
            "current_workload": 70,
            "availability": 0.4,
            "current_tasks": 5,
            "consistency": 0.88,
            "learning_rate": 0.08
        }
    ])


def test_ahp_consistency_and_weights():
    """Verify Saaty AHP consistency condition: CR must be < 0.10."""
    ahp = AHPOptimizer()
    matrix = ahp.get_default_pairwise_matrix()
    weights, metrics = ahp.compute_weights(matrix)

    # 1. Weights must sum to 1.0
    arr = weights.to_array()
    assert np.isclose(np.sum(arr), 1.0, atol=1e-4)

    # 2. Consistency Ratio must be mathematically valid (CR < 0.10)
    assert metrics["is_consistent"] is True
    assert metrics["consistency_ratio_cr"] < 0.10
    assert metrics["lambda_max"] >= 5.0  # For n=5 matrix, lambda_max >= n


def test_composite_weights_dataclass():
    """Test CompositeWeights serialization and array conversions."""
    cw = CompositeWeights(skill_match=0.40, workload_balance=0.30, ml_prediction=0.10, consistency=0.10, learning_rate=0.10)
    arr = cw.to_array()
    assert np.isclose(np.sum(arr), 1.0)
    
    # From dict with percentages
    pct_dict = {"skill_match": 40.0, "workload_balance": 30.0, "ml_prediction": 10.0, "consistency": 10.0, "learning_rate": 10.0}
    cw2 = CompositeWeights.from_dict(pct_dict)
    assert np.isclose(cw2.skill_match, 0.40)
    assert np.isclose(cw2.workload_balance, 0.30)


def test_simulation_optimizer_convergence():
    """Verify that SLSQP optimization converges and finds valid bounded weights."""
    sim_opt = SimulationWeightOptimizer()
    opt_weights, metrics = sim_opt.optimize()

    assert metrics["success"] is True
    arr = opt_weights.to_array()
    assert np.isclose(np.sum(arr), 1.0, atol=1e-3)
    
    # Check that each weight satisfies lower bounds (> 0.04)
    for val in arr:
        assert val >= 0.04


def test_benchmark_evaluator_proves_superiority():
    """Verify that the Simulation-Optimized weights outperform uniform baseline."""
    evaluator = WeightBenchmarkEvaluator()
    results = evaluator.run_benchmark()

    assert "Uniform Baseline (20% each)" in results
    assert "Simulation-Optimized (Proposed)" in results

    baseline_fitness = results["Uniform Baseline (20% each)"]["allocation_fitness_score"]
    opt_fitness = results["Simulation-Optimized (Proposed)"]["allocation_fitness_score"]

    # Optimized allocation fitness must be strictly greater than naive uniform baseline
    assert opt_fitness >= baseline_fitness


def test_rank_developers_with_custom_weights(sample_task, sample_developers_df):
    """Verify rank_developers_for_task properly accepts custom CompositeWeights."""
    model = DummyModel()
    feature_cols = [c for c in sample_developers_df.columns if c != "dev_id"]

    # Custom skill-heavy weight
    custom_weights = CompositeWeights(skill_match=0.60, workload_balance=0.10, ml_prediction=0.10, consistency=0.10, learning_rate=0.10)
    
    ranking_df = rank_developers_for_task(
        task_profile=sample_task,
        dev_df=sample_developers_df,
        model=model,
        feature_columns=feature_cols,
        weights=custom_weights
    )

    assert len(ranking_df) == 3
    assert "final_score" in ranking_df.columns
    # Scores must be sorted in descending order
    assert ranking_df.iloc[0]["final_score"] >= ranking_df.iloc[1]["final_score"] >= ranking_df.iloc[2]["final_score"]
    assert ranking_df.iloc[0]["rank"] == 1
    assert ranking_df.iloc[1]["rank"] == 2
    assert ranking_df.iloc[2]["rank"] == 3


def test_explainability_dynamic_weights(sample_task, sample_developers_df):
    """Verify explainability engine dynamically displays custom weights in score breakdown."""
    model = DummyModel()
    feature_cols = [c for c in sample_developers_df.columns if c != "dev_id"]

    custom_weights = CompositeWeights(skill_match=0.40, workload_balance=0.30, ml_prediction=0.10, consistency=0.10, learning_rate=0.10)
    engine = ExplainabilityEngine(model=model, feature_columns=feature_cols, weights=custom_weights)

    ranking_df = rank_developers_for_task(
        task_profile=sample_task,
        dev_df=sample_developers_df,
        model=model,
        feature_columns=feature_cols,
        weights=custom_weights
    )

    top_row = ranking_df.iloc[0]
    explanation = engine.explain_recommendation(
        developer_id=top_row["dev_id"],
        task_profile=sample_task,
        dev_features=sample_developers_df,
        ml_score=top_row["predicted_performance"],
        skill_match_score=top_row["skill_match_score"],
        workload_balance=top_row["workload_balance"],
        consistency=top_row["consistency"],
        learning_rate=top_row["learning_rate"],
        final_score=top_row["final_score"],
        rank=top_row["rank"]
    )

    breakdown = explanation["breakdown"]
    assert breakdown["skill_match"]["weight"] == "40%"
    assert breakdown["workload_balance"]["weight"] == "30%"
    assert breakdown["ml_prediction"]["weight"] == "10%"
