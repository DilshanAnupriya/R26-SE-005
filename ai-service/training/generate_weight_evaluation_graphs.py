"""Generate publication-ready evaluation charts and run practical testing
simulations comparing the weight configurations for AI Resource Allocation.

Generates:
1. weight_distribution_comparison.png (Grouped Bar Chart & Radar Chart)
2. practical_testing_metrics.png (Skill Alignment, Gini Index, Overload Rate, Fitness)
3. team_workload_balance_comparison.png (Per-developer workload & Lorenz Curve)
4. weight_sensitivity_analysis.png (Sensitivity curves showing stability & optimality)
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

# Add project root to sys.path
ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

from app.weight_optimizer import (
    AHPOptimizer,
    CompositeWeights,
    SimulationWeightOptimizer,
    WeightBenchmarkEvaluator,
    get_default_optimal_weights,
)


def set_plot_style():
    plt.rcParams.update({
        'font.sans-serif': 'DejaVu Sans',
        'font.family': 'sans-serif',
        'figure.dpi': 300,
        'axes.labelsize': 11,
        'axes.titlesize': 12,
        'xtick.labelsize': 9,
        'ytick.labelsize': 9,
        'legend.fontsize': 9,
        'figure.titlesize': 14,
        'axes.grid': True,
        'grid.alpha': 0.3,
        'grid.linestyle': '--'
    })


def generate_weight_distribution_chart(output_dir: Path, artifacts_dir: Path):
    """Generates a comparison of criteria weights across all 4 configurations."""
    configs = {
        "Uniform Baseline": [20.0, 20.0, 20.0, 20.0, 20.0],
        "Skill-Centric":    [60.0, 20.0, 20.0,  0.0,  0.0],
        "AHP Multi-Criteria": [35.0, 26.2, 19.4, 10.6,  8.8],
        "Proposed Optimal": [35.0, 30.0, 15.0, 10.0, 10.0],
    }

    criteria = ["Skill Match", "Workload Bal", "ML Pred", "Consistency", "Learning Rate"]
    x = np.arange(len(criteria))
    width = 0.20

    fig, ax = plt.subplots(figsize=(10, 5.5), constrained_layout=True)

    colors = ["#94a3b8", "#ef4444", "#3b82f6", "#10b981"]
    
    for i, (name, weights) in enumerate(configs.items()):
        bars = ax.bar(x + (i - 1.5) * width, weights, width, label=name, color=colors[i], alpha=0.9, edgecolor="black", linewidth=0.6)
        # Add value labels on top of bars
        for bar in bars:
            h = bar.get_height()
            if h > 0:
                ax.annotate(f"{h:.1f}%" if h % 1 != 0 else f"{int(h)}%",
                            xy=(bar.get_x() + bar.get_width() / 2, h),
                            xytext=(0, 3), textcoords="offset points",
                            ha="center", va="bottom", fontsize=7.5, weight="bold")

    ax.set_ylabel("Weight Percentage (%)", fontweight="bold")
    ax.set_title("Weight Allocation Strategy Comparison Across Criteria", fontweight="bold", pad=12)
    ax.set_xticks(x)
    ax.set_xticklabels(criteria, fontweight="bold")
    ax.set_ylim(0, 70)
    ax.legend(frameon=True, facecolor="white", edgecolor="#cbd5e1", loc="upper right")

    out_path = output_dir / "weight_distribution_comparison.png"
    plt.savefig(out_path, dpi=300)
    plt.close()

    # Also save to artifact directory
    plt.figure(figsize=(10, 5.5))
    fig.savefig(artifacts_dir / "weight_distribution_comparison.png", dpi=300, bbox_inches='tight')
    plt.close()
    print(f"✓ Saved: {out_path}")


def run_comprehensive_simulation(output_dir: Path, artifacts_dir: Path):
    """Simulates 50 agile sprint tasks across a team of 10 developers under all 4 weight configurations."""
    np.random.seed(42)

    # 10 Developer team with realistic attributes
    devs = pd.DataFrame({
        "dev_id": [f"DEV-{i:02d}" for i in range(1, 11)],
        "skill_frontend": [5, 4, 2, 1, 5, 3, 2, 4, 1, 5],
        "skill_backend":  [3, 2, 5, 4, 2, 4, 5, 3, 4, 1],
        "skill_db":       [2, 2, 4, 5, 1, 4, 4, 2, 5, 2],
        "experience_level": [3, 2, 3, 1, 2, 3, 1, 2, 3, 1],
        "base_workload":  [20, 25, 30, 10, 35, 15, 10, 25, 40, 10],
        "availability":   [0.85, 0.75, 0.70, 0.95, 0.65, 0.85, 0.90, 0.75, 0.60, 0.90],
        "consistency":    [0.92, 0.85, 0.88, 0.70, 0.82, 0.90, 0.68, 0.84, 0.78, 0.80],
        "learning_rate":  [0.08, 0.12, 0.09, 0.19, 0.11, 0.10, 0.20, 0.12, 0.07, 0.18]
    })

    # Generate 50 realistic tasks across sprints
    tasks = []
    task_types = ["frontend", "backend", "db"]
    for i in range(50):
        ttype = np.random.choice(task_types, p=[0.4, 0.4, 0.2])
        tasks.append({
            "task_id": i + 1,
            "task_type": ttype,
            "req_frontend": np.random.randint(3, 6) if ttype == "frontend" else np.random.randint(1, 3),
            "req_backend":  np.random.randint(3, 6) if ttype == "backend" else np.random.randint(1, 3),
            "req_db":       np.random.randint(3, 6) if ttype == "db" else np.random.randint(1, 3),
            "story_points": np.random.choice([2, 3, 5, 8], p=[0.3, 0.4, 0.2, 0.1]),
            "complexity":   np.random.randint(3, 8)
        })

    configs = {
        "Uniform Baseline":   CompositeWeights(0.20, 0.20, 0.20, 0.20, 0.20),
        "Skill-Centric":      CompositeWeights(0.60, 0.20, 0.20, 0.00, 0.00),
        "AHP Multi-Criteria": CompositeWeights(0.35, 0.26, 0.19, 0.11, 0.09),
        "Proposed Optimal":   CompositeWeights(0.35, 0.30, 0.15, 0.10, 0.10),
    }

    metrics_results = {}
    workload_histories = {}

    for cfg_name, cw in configs.items():
        w = cw.to_array()
        w_skill, w_workload, w_ml, w_cons, w_lr = w

        current_workloads = devs["base_workload"].values.astype(float).copy()
        tasks_assigned_per_dev = np.zeros(len(devs))
        skill_matches_list = []
        burnout_incidents = 0
        points_assigned = np.zeros(len(devs))

        for t in tasks:
            # Skill match
            diff = (
                np.abs(devs["skill_frontend"].values - t["req_frontend"]) +
                np.abs(devs["skill_backend"].values - t["req_backend"]) +
                np.abs(devs["skill_db"].values - t["req_db"])
            )
            sm = np.clip(1.0 - (diff / 15.0), 0.0, 1.0)
            
            # Workload balance factor
            max_wl = max(np.max(current_workloads), 1.0)
            wb = np.clip(1.0 - (current_workloads / max_wl), 0.0, 1.0)
            
            # ML predicted performance proxy
            ml_pred = np.clip(devs["experience_level"].values * 25.0 * devs["availability"].values, 10.0, 95.0) / 100.0
            
            # Growth bonus for junior devs on manageable tasks
            growth_bonus = np.zeros(len(devs))
            for d_idx, exp in enumerate(devs["experience_level"].values):
                if t["complexity"] <= 6 and sm[d_idx] >= 0.30:
                    g_mult = max(0.0, (3 - min(exp, 3)) / 2.0)
                    fit = max(0.0, 1.0 - (t["complexity"] / 8.0))
                    growth_bonus[d_idx] = devs["learning_rate"].values[d_idx] * g_mult * fit * 12.0

            # Total score
            scores = (
                w_skill * sm * 100.0 +
                w_workload * wb * 100.0 +
                w_ml * ml_pred * 100.0 +
                w_cons * devs["consistency"].values * 100.0 +
                w_lr * devs["learning_rate"].values * 100.0 +
                growth_bonus
            )

            chosen_dev_idx = int(np.argmax(scores))
            skill_matches_list.append(sm[chosen_dev_idx])
            
            # Update state
            sp = t["story_points"]
            current_workloads[chosen_dev_idx] += (sp * 3.5)
            tasks_assigned_per_dev[chosen_dev_idx] += 1
            points_assigned[chosen_dev_idx] += sp

            if current_workloads[chosen_dev_idx] > 85.0:
                burnout_incidents += 1

        # Compute Gini coefficient
        sorted_points = np.sort(points_assigned)
        n = len(sorted_points)
        index = np.arange(1, n + 1)
        gini = float((np.sum((2 * index - n - 1) * sorted_points)) / (n * np.sum(sorted_points))) if np.sum(sorted_points) > 0 else 0.0

        avg_skill_pct = float(np.mean(skill_matches_list) * 100.0)
        overload_pct = float((burnout_incidents / len(tasks)) * 100.0)
        workload_std = float(np.std(points_assigned))
        fairness_score = float((1.0 - gini) * 100.0)
        overall_fitness = float((avg_skill_pct * 0.45) + (fairness_score * 0.35) - (overload_pct * 0.20))

        metrics_results[cfg_name] = {
            "avg_skill_match_pct": round(avg_skill_pct, 1),
            "gini_index": round(gini, 3),
            "fairness_score_pct": round(fairness_score, 1),
            "overload_rate_pct": round(overload_pct, 1),
            "workload_std_pts": round(workload_std, 1),
            "overall_fitness": round(overall_fitness, 1),
        }
        workload_histories[cfg_name] = points_assigned

    # Save metrics JSON
    with open(output_dir / "practical_simulation_metrics.json", "w") as f:
        json.dump(metrics_results, f, indent=2)

    # -------------------------------------------------------------
    # PLOT 1: Practical Metrics Comparison Bar Chart
    # -------------------------------------------------------------
    fig, ((ax1, ax2), (ax3, ax4)) = plt.subplots(2, 2, figsize=(11, 8), constrained_layout=True)
    cfg_names = list(configs.keys())
    colors = ["#94a3b8", "#ef4444", "#3b82f6", "#10b981"]

    # 1. Skill Match
    vals1 = [metrics_results[c]["avg_skill_match_pct"] for c in cfg_names]
    bars1 = ax1.bar(cfg_names, vals1, color=colors, alpha=0.9, edgecolor="black", linewidth=0.6)
    ax1.set_ylabel("Skill Match (%)", fontweight="bold")
    ax1.set_title("(A) Technical Skill Alignment", fontweight="bold")
    ax1.set_ylim(60, 100)
    for b in bars1:
        ax1.annotate(f"{b.get_height():.1f}%", (b.get_x() + b.get_width()/2, b.get_height()),
                     xytext=(0, 3), textcoords="offset points", ha="center", va="bottom", weight="bold")

    # 2. Workload Fairness (1 - Gini)
    vals2 = [metrics_results[c]["fairness_score_pct"] for c in cfg_names]
    bars2 = ax2.bar(cfg_names, vals2, color=colors, alpha=0.9, edgecolor="black", linewidth=0.6)
    ax2.set_ylabel("Fairness Score (%) [1 - Gini]", fontweight="bold")
    ax2.set_title("(B) Team Workload Fairness (Higher = More Equal)", fontweight="bold")
    ax2.set_ylim(50, 100)
    for b in bars2:
        ax2.annotate(f"{b.get_height():.1f}%", (b.get_x() + b.get_width()/2, b.get_height()),
                     xytext=(0, 3), textcoords="offset points", ha="center", va="bottom", weight="bold")

    # 3. Developer Overload / Burnout Rate
    vals3 = [metrics_results[c]["overload_rate_pct"] for c in cfg_names]
    bars3 = ax3.bar(cfg_names, vals3, color=colors, alpha=0.9, edgecolor="black", linewidth=0.6)
    ax3.set_ylabel("Overload Incident Rate (%)", fontweight="bold")
    ax3.set_title("(C) Developer Burnout & Overload Incidents (Lower is Better)", fontweight="bold")
    ax3.set_ylim(0, 45)
    for b in bars3:
        ax3.annotate(f"{b.get_height():.1f}%", (b.get_x() + b.get_width()/2, b.get_height()),
                     xytext=(0, 3), textcoords="offset points", ha="center", va="bottom", weight="bold")

    # 4. Overall Multi-Objective Allocation Fitness
    vals4 = [metrics_results[c]["overall_fitness"] for c in cfg_names]
    bars4 = ax4.bar(cfg_names, vals4, color=colors, alpha=0.9, edgecolor="black", linewidth=0.6)
    ax4.set_ylabel("Composite Fitness Score", fontweight="bold")
    ax4.set_title("(D) Overall Sprint Allocation Fitness", fontweight="bold")
    ax4.set_ylim(50, 80)
    for b in bars4:
        ax4.annotate(f"{b.get_height():.1f}", (b.get_x() + b.get_width()/2, b.get_height()),
                     xytext=(0, 3), textcoords="offset points", ha="center", va="bottom", weight="bold")

    for ax in [ax1, ax2, ax3, ax4]:
        ax.tick_params(axis='x', rotation=15)

    fig.suptitle("50-Task Agile Sprint Simulation: Practical Performance Evaluation", fontweight="bold", fontsize=14)
    out_metrics_chart = output_dir / "practical_testing_metrics.png"
    fig.savefig(out_metrics_chart, dpi=300)
    fig.savefig(artifacts_dir / "practical_testing_metrics.png", dpi=300)
    plt.close()
    print(f"✓ Saved: {out_metrics_chart}")

    # -------------------------------------------------------------
    # PLOT 2: Team Workload Distribution & Lorenz Inequality Curve
    # -------------------------------------------------------------
    fig, (ax_bar, ax_lorenz) = plt.subplots(1, 2, figsize=(12, 5), constrained_layout=True)

    # Developer-by-Developer points assigned
    dev_labels = [f"D{i}" for i in range(1, 11)]
    x_devs = np.arange(10)
    width = 0.22

    for i, (name, points) in enumerate(workload_histories.items()):
        ax_bar.bar(x_devs + (i - 1.5) * width, points, width, label=name, color=colors[i], alpha=0.9, edgecolor="black", linewidth=0.5)

    ax_bar.set_ylabel("Assigned Story Points", fontweight="bold")
    ax_bar.set_xlabel("Team Developers (DEV-01 to DEV-10)", fontweight="bold")
    ax_bar.set_title("(A) Workload Distribution Across Team", fontweight="bold")
    ax_bar.set_xticks(x_devs)
    ax_bar.set_xticklabels(dev_labels)
    ax_bar.legend(loc="upper right", frameon=True)

    # Lorenz Curves
    ax_lorenz.plot([0, 1], [0, 1], 'k--', label="Perfect Equality (Gini = 0.0)", alpha=0.7)

    for i, (name, points) in enumerate(workload_histories.items()):
        sorted_p = np.sort(points)
        cum_points = np.cumsum(sorted_p) / np.sum(sorted_p)
        cum_points = np.insert(cum_points, 0, 0)
        cum_pop = np.linspace(0, 1, len(cum_points))
        gini = metrics_results[name]["gini_index"]
        ax_lorenz.plot(cum_pop, cum_points, color=colors[i], label=f"{name} (Gini = {gini})", linewidth=2.0)

    ax_lorenz.set_xlabel("Cumulative Share of Developers", fontweight="bold")
    ax_lorenz.set_ylabel("Cumulative Share of Assigned Points", fontweight="bold")
    ax_lorenz.set_title("(B) Lorenz Inequality Curves", fontweight="bold")
    ax_lorenz.legend(loc="upper left", frameon=True)

    fig.suptitle("Team Workload Balance & Fairness Analysis", fontweight="bold", fontsize=14)
    out_balance_chart = output_dir / "team_workload_balance_comparison.png"
    fig.savefig(out_balance_chart, dpi=300)
    fig.savefig(artifacts_dir / "team_workload_balance_comparison.png", dpi=300)
    plt.close()
    print(f"✓ Saved: {out_balance_chart}")


def generate_sensitivity_analysis_chart(output_dir: Path, artifacts_dir: Path):
    """Performs One-At-A-Time Sensitivity Analysis on weights to prove stability."""
    sim_opt = SimulationWeightOptimizer()
    base_weights = np.array([0.35, 0.30, 0.15, 0.10, 0.10])
    criteria_names = ["Skill Match", "Workload Balance", "ML Prediction", "Consistency", "Learning Rate"]

    perturbations = np.linspace(-0.15, 0.15, 11)  # -15% to +15%
    results_sensitivity = {name: [] for name in criteria_names}

    for crit_idx, name in enumerate(criteria_names):
        for delta in perturbations:
            w_mod = base_weights.copy()
            w_mod[crit_idx] += delta
            # Rebalance remaining weights proportionally
            other_indices = [i for i in range(5) if i != crit_idx]
            other_sum = np.sum(w_mod[other_indices])
            if other_sum > 0:
                w_mod[other_indices] = w_mod[other_indices] * ((1.0 - w_mod[crit_idx]) / other_sum)
            
            # Clip bounds and normalize
            w_mod = np.clip(w_mod, 0.02, 0.70)
            w_mod = w_mod / np.sum(w_mod)
            
            fitness = sim_opt.evaluate_allocation_fitness(w_mod)
            results_sensitivity[name].append(fitness)

    fig, ax = plt.subplots(figsize=(9, 5.2), constrained_layout=True)
    colors = ["#2563eb", "#10b981", "#f59e0b", "#8b5cf6", "#ec4899"]
    markers = ['o', 's', '^', 'D', 'v']

    delta_pct = perturbations * 100.0

    for i, name in enumerate(criteria_names):
        ax.plot(delta_pct, results_sensitivity[name], label=name, color=colors[i], marker=markers[i], linewidth=1.8, markersize=5)

    ax.axvline(0, color='gray', linestyle=':', label='Proposed Baseline (Δ = 0%)')
    ax.set_xlabel("Weight Perturbation Shift (Δ %)", fontweight="bold")
    ax.set_ylabel("Allocation Fitness Score", fontweight="bold")
    ax.set_title("Sensitivity Analysis: System Robustness vs. Weight Shift", fontweight="bold", pad=12)
    ax.legend(frameon=True, facecolor="white", edgecolor="#cbd5e1", loc="lower left")

    out_sens_chart = output_dir / "weight_sensitivity_analysis.png"
    fig.savefig(out_sens_chart, dpi=300)
    fig.savefig(artifacts_dir / "weight_sensitivity_analysis.png", dpi=300)
    plt.close()
    print(f"✓ Saved: {out_sens_chart}")


def main():
    set_plot_style()
    output_dir = ROOT / "models" / "graphs"
    output_dir.mkdir(parents=True, exist_ok=True)
    
    # Artifact directory for embedding in conversation
    artifacts_dir = Path("/Users/hirunbhathiya/.gemini/antigravity-ide/brain/c88e5d8e-9e44-4365-ac6e-4df5d278c13e")
    artifacts_dir.mkdir(parents=True, exist_ok=True)

    print("=" * 80)
    print("  SprintIQ: Generating Research Comparison Graphs & Simulation Testing")
    print("=" * 80)

    print("\n1. Generating Weight Distribution Strategy Chart...")
    generate_weight_distribution_chart(output_dir, artifacts_dir)

    print("\n2. Running 50-Task Sprint Practical Simulation & Plotting Performance Metrics...")
    run_comprehensive_simulation(output_dir, artifacts_dir)

    print("\n3. Running Sensitivity Analysis & Plotting Robustness Curves...")
    generate_sensitivity_analysis_chart(output_dir, artifacts_dir)

    print("\n" + "=" * 80)
    print(f"✓ All publication-ready charts successfully generated in:")
    print(f"   - {output_dir}")
    print(f"   - {artifacts_dir}")
    print("=" * 80)


if __name__ == "__main__":
    main()
