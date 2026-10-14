"""Offline Allocation Policy Evaluation Pipeline.

Implements the formal offline evaluation methodology for Agile developer allocation:
1. Optimizes Hungarian cost weights (w_risk, w_workload, w_familiarity) strictly on validation sprints (Sprints 36-45).
2. Freezes the optimal weights.
3. Evaluates the frozen allocation policy on the held-out test period (Sprints 46-66).

Methodologies:
- Historical Assignment Congruence: Hit Rate @ 1, Hit Rate @ 3, Mean Reciprocal Rank (MRR).
- Workload Fairness: Gini Coefficient of allocated story points across team members per sprint.
- Multi-Objective Hungarian Matching (Bipartite Linear Sum Assignment).
"""

from __future__ import annotations

import logging
from pathlib import Path
import joblib
import numpy as np
import pandas as pd
from scipy.optimize import linear_sum_assignment

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("offline_allocation_eval")

BASE_DIR = Path(__file__).resolve().parent.parent
DATA_DIR = Path(__file__).resolve().parent / "data" / "resource_allocation"
MODELS_DIR = BASE_DIR / "models"


def calculate_gini(array: np.ndarray) -> float:
    """Calculate the Gini coefficient of an array (0 = perfect equality, 1 = maximum inequality)."""
    array = np.array(array, dtype=float).flatten()
    if len(array) == 0:
        return 0.0
    if np.amin(array) < 0:
        array -= np.amin(array)
    array += 1e-7
    array = np.sort(array)
    index = np.arange(1, array.shape[0] + 1)
    n = array.shape[0]
    return float((np.sum((2 * index - n - 1) * array)) / (n * np.sum(array)))


def compute_sprint_candidate_predictions(
    sprint_tasks: pd.DataFrame,
    active_devs: list[str],
    s_id: int,
    df_full: pd.DataFrame,
    model,
    feature_columns: list[str]
) -> dict[int, list[tuple[str, float, float, float]]]:
    """Batch-computes all (task, developer) risk, load, and familiarity tuples in a sprint."""
    candidates = []
    index_map = []

    dev_stats = {}
    for d in active_devs:
        d_hist = df_full[(df_full["sprint_id"] < s_id) & (df_full["dev_id"] == d)]
        d_completed = d_hist[d_hist["task_completed_within_sprint"] == 1]
        n_comp = len(d_completed)
        sp_load = df_full[(df_full["sprint_id"] == s_id) & (df_full["dev_id"] == d)]["story_points"].sum()
        vel = float(d_completed["story_points"].sum()) / max(1, len(set(d_completed["sprint_id"]))) if n_comp > 0 else 5.0
        if np.isnan(vel) or vel < 1.0:
            vel = 1.0
        prior_rate = n_comp / max(1, len(d_hist)) if len(d_hist) > 0 else 0.78
        dev_stats[d] = {
            "n_comp": n_comp,
            "d_completed": d_completed,
            "sp_load": sp_load,
            "vel": vel,
            "prior_rate": prior_rate
        }

    for t_idx, (_, task_row) in enumerate(sprint_tasks.iterrows()):
        for d in active_devs:
            st = dev_stats[d]
            d_comp = st["d_completed"]
            fam = len(d_comp[d_comp["task_domain"] == task_row["task_domain"]]) / max(1, st["n_comp"]) if st["n_comp"] > 0 else 0.0

            cand = task_row.to_dict()
            cand["dev_id"] = d
            cand["dev_prior_completed_tasks"] = st["n_comp"]
            cand["dev_prior_completed_rate"] = st["prior_rate"]
            cand["dev_historical_velocity_avg"] = st["vel"]
            cand["dev_domain_familiarity"] = fam
            cand["dev_sprint_story_point_load"] = st["sp_load"]
            cand["dev_load_ratio"] = st["sp_load"] / st["vel"]
            cand["is_cold_start_dev"] = 1 if st["n_comp"] == 0 else 0

            candidates.append(cand)
            index_map.append((t_idx, d, fam, st["sp_load"]))

    cand_df = pd.DataFrame(candidates)
    cand_encoded = pd.get_dummies(cand_df, columns=["task_type", "task_domain"], drop_first=False)
    cand_aligned = cand_encoded.reindex(columns=feature_columns, fill_value=0.0).astype(float)

    if hasattr(model, "predict_proba"):
        p_comp = model.predict_proba(cand_aligned)[:, 1]
    else:
        p_comp = model.predict(cand_aligned)

    task_scores = {t_idx: [] for t_idx in range(len(sprint_tasks))}
    for (t_idx, d, fam, sp_load), p in zip(index_map, p_comp):
        risk = 1.0 - float(p)
        workload_pen = float(sp_load) / 15.0
        task_scores[t_idx].append((d, risk, workload_pen, fam))

    return task_scores


def run_offline_allocation_benchmark() -> dict:
    """Run validation weight search, freeze weights, and benchmark allocation on test sprints."""
    model_bundle = joblib.load(MODELS_DIR / "real_allocation_model.pkl")
    model = model_bundle["model"]
    feature_columns = model_bundle["feature_columns"]

    df_full = pd.read_csv(DATA_DIR / "real_agile_dataset.csv")

    val_sprints = list(range(36, 46))
    test_sprints = sorted(df_full[df_full["sprint_id"] > 45]["sprint_id"].unique())

    logger.info("Validation sprints for weight search: %s", val_sprints)
    logger.info("Held-out test sprints for final evaluation: %s", test_sprints)

    # =========================================================================
    # STEP 1: PRECOMPUTE VALIDATION CANDIDATE SCORES AND GRID SEARCH
    # =========================================================================
    logger.info("\n--- Phase 1: Hyperparameter Grid Search on Validation Sprints (36-45) ---")

    weight_candidates = [
        {"w_risk": 0.50, "w_workload": 0.30, "w_familiarity": 0.20},
        {"w_risk": 0.40, "w_workload": 0.40, "w_familiarity": 0.20},
        {"w_risk": 0.35, "w_workload": 0.35, "w_familiarity": 0.30},
        {"w_risk": 0.60, "w_workload": 0.20, "w_familiarity": 0.20},
    ]

    df_val = df_full[df_full["sprint_id"].isin(val_sprints)].copy()

    val_sprint_cache = {}
    for s_id in val_sprints:
        sprint_tasks = df_val[df_val["sprint_id"] == s_id].reset_index(drop=True)
        if len(sprint_tasks) == 0:
            continue
        active_devs = sorted(sprint_tasks["dev_id"].unique())
        if len(active_devs) < 2:
            continue
        cache = compute_sprint_candidate_predictions(sprint_tasks, active_devs, s_id, df_full, model, feature_columns)
        val_sprint_cache[s_id] = (sprint_tasks, cache)

    best_weights = weight_candidates[0]
    best_val_mrr = -1.0

    for w in weight_candidates:
        mrrs = []
        for s_id, (sprint_tasks, cache) in val_sprint_cache.items():
            for t_idx, task_row in sprint_tasks.iterrows():
                hist_dev = task_row["dev_id"]
                candidate_tuples = cache[t_idx]
                scored = []
                for d, risk, workload_pen, fam in candidate_tuples:
                    cost = w["w_risk"] * risk + w["w_workload"] * workload_pen - w["w_familiarity"] * fam
                    scored.append((d, cost))

                scored.sort(key=lambda x: x[1])
                ranked_devs = [x[0] for x in scored]
                if hist_dev in ranked_devs:
                    rank = ranked_devs.index(hist_dev) + 1
                    mrrs.append(1.0 / rank)

        mean_val_mrr = float(np.mean(mrrs)) if mrrs else 0.0
        logger.info("Weights %s -> Validation MRR: %.4f", w, mean_val_mrr)
        if mean_val_mrr > best_val_mrr:
            best_val_mrr = mean_val_mrr
            best_weights = w

    logger.info("Selected and FROZEN optimal weights: %s (Validation MRR: %.4f)", best_weights, best_val_mrr)

    # =========================================================================
    # STEP 2: EVALUATE ON HELD-OUT TEST SPRINTS (Sprints 46-66)
    # =========================================================================
    logger.info("\n--- Phase 2: Evaluation on Held-Out Test Sprints (46-66) ---")

    df_test = df_full[df_full["sprint_id"].isin(test_sprints)].copy()
    test_tasks = len(df_test)
    logger.info("Evaluating %d test task assignments across %d sprints", test_tasks, len(test_sprints))

    mrr_list = []
    hit1_list = []
    hit3_list = []

    test_sprint_cache = {}
    for s_id in test_sprints:
        sprint_tasks = df_test[df_test["sprint_id"] == s_id].reset_index(drop=True)
        if len(sprint_tasks) == 0:
            continue
        active_devs = sorted(sprint_tasks["dev_id"].unique())
        if len(active_devs) < 2:
            continue
        cache = compute_sprint_candidate_predictions(sprint_tasks, active_devs, s_id, df_full, model, feature_columns)
        test_sprint_cache[s_id] = (sprint_tasks, active_devs, cache)

    for s_id, (sprint_tasks, active_devs, cache) in test_sprint_cache.items():
        for t_idx, task_row in sprint_tasks.iterrows():
            hist_dev = task_row["dev_id"]
            candidate_tuples = cache[t_idx]
            scored = []
            for d, risk, workload_pen, fam in candidate_tuples:
                cost = best_weights["w_risk"] * risk + best_weights["w_workload"] * workload_pen - best_weights["w_familiarity"] * fam
                scored.append((d, cost))

            scored.sort(key=lambda x: x[1])
            ranked_devs = [x[0] for x in scored]
            if hist_dev in ranked_devs:
                rank = ranked_devs.index(hist_dev) + 1
                mrr_list.append(1.0 / rank)
                hit1_list.append(1 if rank == 1 else 0)
                hit3_list.append(1 if rank <= 3 else 0)

    # =========================================================================
    # STEP 3: WORKLOAD FAIRNESS (GINI COEFFICIENT EVALUATION)
    # =========================================================================
    gini_historical = []
    gini_hungarian = []
    gini_greedy = []

    for s_id, (sprint_tasks, active_devs, cache) in test_sprint_cache.items():
        if len(active_devs) < 2 or len(sprint_tasks) < 2:
            continue

        # 1. Historical Gini
        hist_sp = sprint_tasks.groupby("dev_id")["story_points"].sum().reindex(active_devs, fill_value=0.0).values
        gini_historical.append(calculate_gini(hist_sp))

        # 2. Hungarian Multi-Objective Cost Matrix
        n_tasks = len(sprint_tasks)
        n_devs = len(active_devs)
        slots_per_dev = max(1, int(np.ceil(n_tasks / n_devs)))

        cost_matrix = np.zeros((n_tasks, n_devs * slots_per_dev))
        for t_idx in range(n_tasks):
            t_candidates = {d: (risk, wl, fam) for d, risk, wl, fam in cache[t_idx]}
            for d_idx, d in enumerate(active_devs):
                risk, wl, fam = t_candidates.get(d, (0.5, 0.5, 0.0))
                for slot in range(slots_per_dev):
                    col_idx = d_idx * slots_per_dev + slot
                    slot_pen = slot * 0.35
                    cost = best_weights["w_risk"] * risk + slot_pen - best_weights["w_familiarity"] * fam
                    cost_matrix[t_idx, col_idx] = cost

        row_ind, col_ind = linear_sum_assignment(cost_matrix)
        hungarian_sp = np.zeros(n_devs)
        for r, c in zip(row_ind, col_ind):
            d_idx = c // slots_per_dev
            sp = float(sprint_tasks.iloc[r]["story_points"])
            hungarian_sp[d_idx] += sp
        gini_hungarian.append(calculate_gini(hungarian_sp))

        # 3. Greedy Workload Balancer
        greedy_sp = np.zeros(n_devs)
        for _, task_row in sprint_tasks.iterrows():
            min_dev_idx = int(np.argmin(greedy_sp))
            greedy_sp[min_dev_idx] += float(task_row["story_points"])
        gini_greedy.append(calculate_gini(greedy_sp))

    allocation_results = {
        "frozen_weights": best_weights,
        "congruence_hit1": round(float(np.mean(hit1_list)), 4) if hit1_list else 0.0,
        "congruence_hit3": round(float(np.mean(hit3_list)), 4) if hit3_list else 0.0,
        "congruence_mrr": round(float(np.mean(mrr_list)), 4) if mrr_list else 0.0,
        "mean_gini_historical": round(float(np.mean(gini_historical)), 4),
        "mean_gini_hungarian": round(float(np.mean(gini_hungarian)), 4),
        "mean_gini_greedy": round(float(np.mean(gini_greedy)), 4),
    }

    logger.info("\n" + "=" * 80)
    logger.info("OFFLINE ALLOCATION EVALUATION RESULTS (SPRINTS 46-66)")
    logger.info("=" * 80)
    logger.info("Congruence with Historical Positive Assignments:")
    logger.info("  - Hit Rate @ 1 (Top-1 Accuracy) : %.2f%%", allocation_results["congruence_hit1"] * 100)
    logger.info("  - Hit Rate @ 3 (Top-3 Accuracy) : %.2f%%", allocation_results["congruence_hit3"] * 100)
    logger.info("  - Mean Reciprocal Rank (MRR)    : %.4f", allocation_results["congruence_mrr"])
    logger.info("Team Workload Distribution Fairness (Gini Index, lower = more balanced):")
    logger.info("  - Observed Historical Jira Gini : %.4f", allocation_results["mean_gini_historical"])
    logger.info("  - Hungarian Multi-Objective Gini: %.4f", allocation_results["mean_gini_hungarian"])
    logger.info("  - Pure Greedy Balancer Gini     : %.4f", allocation_results["mean_gini_greedy"])

    joblib.dump(allocation_results, MODELS_DIR / "offline_allocation_results.pkl")
    return allocation_results


if __name__ == "__main__":
    run_offline_allocation_benchmark()
