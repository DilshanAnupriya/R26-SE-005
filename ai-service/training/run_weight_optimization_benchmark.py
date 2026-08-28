"""Scientific Weight Optimization, Validation & Benchmarking Script for SprintIQ.

Executes:
1. Analytic Hierarchy Process (AHP) consistency verification (CR < 0.10).
2. Constrained empirical optimization (SLSQP) on agile dataset.
3. 4-way comparative benchmarking across allocation fitness, skill match, and workload balance.
4. Exports verified optimal weights to models/optimal_weights.json.
"""

from __future__ import annotations

import json
import logging
import os
import sys
from pathlib import Path

# Add project root to sys.path
ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import numpy as np
import pandas as pd

from app.weight_optimizer import (
    AHPOptimizer,
    CompositeWeights,
    SimulationWeightOptimizer,
    WeightBenchmarkEvaluator,
    get_default_optimal_weights,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("sprintiq.weight_benchmark")


def main():
    print("=" * 80)
    print("  SprintIQ: Scientific AI Resource Allocation Weight Optimization")
    print("=" * 80)

    # ---------------------------------------------------------
    # STAGE 1: Analytic Hierarchy Process (AHP)
    # ---------------------------------------------------------
    print("\n[STAGE 1] Running Analytic Hierarchy Process (AHP) Matrix Evaluation...")
    ahp = AHPOptimizer()
    pairwise_matrix = ahp.get_default_pairwise_matrix()
    
    print("\nExpert Pairwise Comparison Matrix (Saaty Scale 1-9):")
    criteria = ["Skill Match", "Workload Bal", "ML Pred", "Consistency", "Learning Rate"]
    matrix_df = pd.DataFrame(pairwise_matrix, index=criteria, columns=criteria)
    print(matrix_df.to_string())

    ahp_weights, ahp_metrics = ahp.compute_weights(pairwise_matrix)
    
    print("\nAHP Consistency Validation:")
    print(f"  • Principal Eigenvalue (λ_max) : {ahp_metrics['lambda_max']}")
    print(f"  • Consistency Index (CI)       : {ahp_metrics['consistency_index_ci']}")
    print(f"  • Random Index (RI, n=5)       : {ahp_metrics['random_index_ri']}")
    print(f"  • Consistency Ratio (CR)       : {ahp_metrics['consistency_ratio_cr']:.4f}")
    print(f"  • Status                       : {'✅ VALIDATED (CR < 0.10)' if ahp_metrics['is_consistent'] else '❌ INCONSISTENT'}")
    
    print("\nAHP Derived Priority Weights:")
    for k, v in ahp_weights.to_percentage_dict().items():
        print(f"  - {k:<18}: {v:>5.1f}%")

    # ---------------------------------------------------------
    # STAGE 2: Empirical Constrained Simulation Optimization (SLSQP)
    # ---------------------------------------------------------
    print("\n" + "-" * 80)
    print("[STAGE 2] Running Empirical SLSQP Constrained Optimization on Sprint Dataset...")
    
    dataset_path = ROOT / "training" / "data" / "resource_allocation" / "agile_resource_dataset_v2.csv"
    sim_opt = SimulationWeightOptimizer(dataset_path=dataset_path)
    
    opt_weights, opt_metrics = sim_opt.optimize()
    
    print(f"Optimizer Convergence: {opt_metrics['message']} (Iterations: {opt_metrics['iterations']})")
    print(f"Optimal Allocation Fitness Score: {opt_metrics['optimal_fitness']}")
    print("\nEmpirically Optimal Weights:")
    for k, v in opt_weights.to_percentage_dict().items():
        print(f"  - {k:<18}: {v:>5.1f}%")

    # ---------------------------------------------------------
    # STAGE 3: Comparative Benchmark
    # ---------------------------------------------------------
    print("\n" + "-" * 80)
    print("[STAGE 3] Running 4-Way Comparative Benchmark...")
    evaluator = WeightBenchmarkEvaluator(sim_opt)
    benchmark_results = evaluator.run_benchmark()

    table_rows = []
    for name, res in benchmark_results.items():
        table_rows.append({
            "Configuration": name,
            "Skill": res["skill_match_weight"],
            "Workload": res["workload_balance_weight"],
            "ML Pred": res["ml_prediction_weight"],
            "Consistency": res["consistency_weight"],
            "Learning": res["learning_rate_weight"],
            "Fitness Score": res["allocation_fitness_score"]
        })
    
    comp_df = pd.DataFrame(table_rows)
    print("\n" + comp_df.to_string(index=False))

    # ---------------------------------------------------------
    # STAGE 4: Export Artifacts
    # ---------------------------------------------------------
    models_dir = ROOT / "models"
    models_dir.mkdir(parents=True, exist_ok=True)
    out_file = models_dir / "optimal_weights.json"
    
    export_payload = {
        "ahp_weights": ahp_weights.to_percentage_dict(),
        "ahp_metrics": ahp_metrics,
        "empirical_optimal_weights": opt_weights.to_percentage_dict(),
        "empirical_metrics": opt_metrics,
        "benchmark_comparison": benchmark_results,
        "recommended_weights": opt_weights.to_dict(),
    }
    
    with open(out_file, "w") as f:
        json.dump(export_payload, f, indent=2)
        
    print(f"\n✓ Exported optimal weight configurations to: {out_file}")
    print("=" * 80)


if __name__ == "__main__":
    main()
