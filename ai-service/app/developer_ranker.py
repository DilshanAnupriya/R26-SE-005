import pandas as pd
import numpy as np
import math
import logging
from typing import Optional, Union, Dict, Any
from scipy.optimize import linear_sum_assignment

try:
    from .weight_optimizer import CompositeWeights, get_default_optimal_weights
except ImportError:
    from app.weight_optimizer import CompositeWeights, get_default_optimal_weights

logger = logging.getLogger(__name__)


def rank_developers_for_task(
        task_profile: dict,
        dev_df: pd.DataFrame,
        model,
        feature_columns: list,
        weights: Optional[Union[CompositeWeights, Dict[str, float]]] = None
) -> pd.DataFrame:
    """
    Rank developers using ML prediction + explicit skill matching + workload balance + growth incentive
    
    CRITICAL: skill_match_score and velocity_contribution are CALCULATED HERE
    (not retrieved from a database or CSV)
    They must match exactly how they were calculated during training
    """
    if weights is None:
        active_weights = get_default_optimal_weights()
    elif isinstance(weights, CompositeWeights):
        active_weights = weights
    elif isinstance(weights, dict):
        active_weights = CompositeWeights.from_dict(weights)
    else:
        active_weights = get_default_optimal_weights()

    # Normalize column names (Spring Boot may use camelCase)
    column_mapping = {
        'devid': 'dev_id',
        'experienceinlevel': 'experience_level',
        'skillfrontend': 'skill_frontend',
        'skillbackend': 'skill_backend',
        'skilldb': 'skill_db',
        'currenttasks': 'current_tasks',
        'currentworkload': 'current_workload',
    }

    for old_name, new_name in column_mapping.items():
        if old_name in dev_df.columns:
            dev_df = dev_df.rename(columns={old_name: new_name})

    is_empirical = "dev_domain_familiarity" in feature_columns or "has_story_points" in feature_columns

    candidates = []

    if is_empirical:
        # =========================================
        # EMPIRICAL FEATURE PIPELINE (Real Agile Model)
        # =========================================
        story_points = float(task_profile.get("storyPoints", task_profile.get("story_points", 3.0)))
        has_story_points = 1 if story_points > 0 else 0

        p_raw = str(task_profile.get("priority", task_profile.get("priority_id", "3"))).lower()
        priority_map = {
            "blocker": 1, "critical": 2, "major": 3, "minor": 4, "trivial": 5,
            "high": 2, "medium": 3, "low": 4, "1": 1, "2": 2, "3": 3, "4": 4, "5": 5
        }
        priority_id = priority_map.get(p_raw, 3)

        depended_on = int(task_profile.get("dependedOn", task_profile.get("depended_on", 0)))
        is_blocked = 1 if (task_profile.get("is_blocked") or task_profile.get("isBlocked") or depended_on > 0) else 0
        dependency_count = int(task_profile.get("dependency_count", task_profile.get("dependencyCount", task_profile.get("dependedOnBy", depended_on))))

        title = str(task_profile.get("title", task_profile.get("summary", task_profile.get("name", "Task description"))))
        summary_char_length = max(10, len(title))

        raw_type = str(task_profile.get("taskType", task_profile.get("task_type", "task"))).lower()
        raw_domain = str(task_profile.get("domain", "")).lower()

        if not raw_domain:
            if "frontend" in raw_type or "ui" in raw_type:
                raw_domain = "ui"
            elif "backend" in raw_type or "core" in raw_type:
                raw_domain = "core"
            elif "db" in raw_type or "database" in raw_type:
                raw_domain = "database"
            else:
                raw_domain = "other"

        domain_analytics = 1 if "analytic" in raw_domain else 0
        domain_clustering = 1 if "cluster" in raw_domain else 0
        domain_core = 1 if ("core" in raw_domain or "backend" in raw_type or "backend" in raw_domain) else 0
        domain_database = 1 if ("database" in raw_domain or "db" in raw_domain or "db" in raw_type) else 0
        domain_stream = 1 if "stream" in raw_domain else 0
        domain_ui = 1 if ("ui" in raw_domain or "frontend" in raw_domain or "frontend" in raw_type) else 0
        domain_other = 1 if not any([domain_analytics, domain_clustering, domain_core, domain_database, domain_stream, domain_ui]) else 0

        type_bug = 1 if ("bug" in raw_type or "defect" in raw_type) else 0
        type_task = 1 if not type_bug else 0

        req_fe = task_profile.get("reqFrontend", task_profile.get("req_frontend"))
        req_be = task_profile.get("reqBackend", task_profile.get("req_backend"))
        req_db = task_profile.get("reqDb", task_profile.get("req_db"))

        for _, dev in dev_df.iterrows():
            dev_fe = float(dev.get("skill_frontend", 3))
            dev_be = float(dev.get("skill_backend", 3))
            dev_db = float(dev.get("skill_db", 3))

            if "domain_familiarity" in dev:
                domain_fam = float(dev["domain_familiarity"])
            elif domain_ui:
                domain_fam = dev_fe / 5.0
            elif domain_core:
                domain_fam = dev_be / 5.0
            elif domain_database:
                domain_fam = dev_db / 5.0
            else:
                domain_fam = (dev_fe + dev_be + dev_db) / 15.0
            domain_fam = max(0.0, min(1.0, domain_fam))

            if req_fe is not None or req_be is not None or req_db is not None:
                fe_m = max(0, 1 - (abs(dev_fe - float(req_fe or 0)) / 5.0))
                be_m = max(0, 1 - (abs(dev_be - float(req_be or 0)) / 5.0))
                db_m = max(0, 1 - (abs(dev_db - float(req_db or 0)) / 5.0))
                if domain_ui:
                    skill_match_score = fe_m * 0.6 + be_m * 0.25 + db_m * 0.15
                elif domain_core:
                    skill_match_score = be_m * 0.6 + fe_m * 0.25 + db_m * 0.15
                elif domain_database:
                    skill_match_score = db_m * 0.6 + be_m * 0.25 + fe_m * 0.15
                else:
                    skill_match_score = (fe_m + be_m + db_m) / 3.0
            else:
                skill_match_score = domain_fam
            skill_match_score = max(0.0, min(1.0, float(skill_match_score)))

            curr_tasks = int(dev.get("current_tasks", 0))
            curr_workload = float(dev.get("current_workload", 0.0))
            hist_vel = float(dev.get("historical_velocity_avg", dev.get("velocity", 12.0)))
            prior_comp = int(dev.get("prior_completed_tasks", dev.get("completed_tasks", 15)))
            init_sp = float(task_profile.get("initialStoryPoints", task_profile.get("initial_story_points", story_points)))
            scope_creep = float(task_profile.get("scopeCreep", task_profile.get("scope_creep", story_points - init_sp)))
            scope_creep_ratio = float(scope_creep / max(1.0, init_sp))
            sprint_len = float(task_profile.get("sprintLength", task_profile.get("sprint_len", 14.0)))
            sprint_devs = float(task_profile.get("sprintDevs", task_profile.get("sprint_devs", max(1, len(dev_df)))))
            sprint_total_issues = float(task_profile.get("sprintTotalIssues", task_profile.get("sprint_total_issues", 40.0)))
            comment_count = int(task_profile.get("commentCount", task_profile.get("comment_count", 0)))
            issue_link_count = int(task_profile.get("issueLinkCount", task_profile.get("issue_link_count", 0)))
            prior_rate = float(dev.get("prior_completed_rate", dev.get("completed_rate", 0.78)))
            load_ratio = float(curr_workload / max(1.0, hist_vel))

            emp_row = {
                "story_points": story_points,
                "initial_story_points": init_sp,
                "has_story_points": has_story_points,
                "scope_creep": scope_creep,
                "scope_creep_ratio": scope_creep_ratio,
                "priority_id": priority_id,
                "is_blocked": is_blocked,
                "dependency_count": dependency_count,
                "issue_link_count": issue_link_count,
                "comment_count": comment_count,
                "summary_char_length": summary_char_length,
                "sprint_len": sprint_len,
                "sprint_devs": sprint_devs,
                "sprint_total_issues": sprint_total_issues,
                "dev_prior_completed_tasks": prior_comp,
                "dev_prior_completed_rate": prior_rate,
                "dev_historical_velocity_avg": hist_vel,
                "dev_domain_familiarity": domain_fam,
                "dev_sprint_task_load": curr_tasks,
                "dev_sprint_story_point_load": curr_workload,
                "dev_load_ratio": load_ratio,
                "is_cold_start_dev": 1 if prior_comp == 0 else 0,
                "domain_analytics": domain_analytics,
                "domain_clustering": domain_clustering,
                "domain_core": domain_core,
                "domain_database": domain_database,
                "domain_other": domain_other,
                "domain_stream": domain_stream,
                "domain_ui": domain_ui,
                "type_bug": type_bug,
                "type_task": type_task,
                "task_type_Bug": type_bug,
                "task_type_Story": 1 if not type_bug and "story" in raw_type else (1 if not type_bug else 0),
                "task_type_Improvement": 1 if "improve" in raw_type else 0,
                "task_domain_backend": 1 if ("backend" in raw_domain or "core" in raw_domain or raw_domain == "backend") else 0,
                "task_domain_database": 1 if ("database" in raw_domain or "db" in raw_domain) else 0,
                "task_domain_devops": 1 if ("devops" in raw_domain or "ci" in raw_domain) else 0,
                "task_domain_frontend": 1 if ("frontend" in raw_domain or "ui" in raw_domain) else 0,

                "_dev_id": dev["dev_id"],
                "skill_match_score": skill_match_score,
                "current_tasks": curr_tasks,
                "current_workload": curr_workload,
                "experience_level": dev.get("experience_level", 2),
                "availability": dev.get("availability", 1.0),
                "consistency": float(dev.get("consistency", 0.5)),
                "learning_rate": float(dev.get("learning_rate", 0.1)),
            }
            candidates.append(emp_row)

    else:
        # =========================================
        # SYNTHETIC BENCHMARK FEATURE PIPELINE (Lspgacy)
        # =========================================
        for _, dev in dev_df.iterrows():
            frontend_match = max(0, 1 - (abs(dev["skill_frontend"] - task_profile.get("reqFrontend", task_profile.get("req_frontend", 0))) / 5))
            backend_match = max(0, 1 - (abs(dev["skill_backend"] - task_profile.get("reqBackend", task_profile.get("req_backend", 0))) / 5))
            db_match = max(0, 1 - (abs(dev["skill_db"] - task_profile.get("reqDb", task_profile.get("req_db", 0))) / 5))

            task_type = task_profile.get("taskType", "").lower()
            if task_type == "frontend":
                skill_match_score = frontend_match * 0.6 + backend_match * 0.25 + db_match * 0.15
            elif task_type == "backend":
                skill_match_score = backend_match * 0.6 + frontend_match * 0.25 + db_match * 0.15
            elif task_type == "db":
                skill_match_score = db_match * 0.6 + backend_match * 0.25 + frontend_match * 0.15
            else:
                skill_match_score = (frontend_match + backend_match + db_match) / 3

            skill_match_score = max(0, min(1, skill_match_score))

            current_workload = dev["current_workload"]
            burnout = np.tanh(current_workload / 40)
            story_points = task_profile.get("storyPoints", task_profile.get("story_points", 3))
            velocity_contribution = story_points * skill_match_score * (1 - burnout * 0.5)

            skill_exp_inter = float(skill_match_score * dev["experience_level"])
            workload_density = float(dev["current_workload"] / (dev["availability"] + 1))
            task_complexity = int(task_profile.get("taskComplexity", task_profile.get("task_complexity", 5)))
            story_points = int(task_profile.get("storyPoints", task_profile.get("story_points", 3)))
            complexity_sp_ratio = float(task_complexity / (story_points + 1))

            row = {
                "sprint_id": 0,
                "experience_level": int(dev["experience_level"]),
                "skill_frontend": int(dev["skill_frontend"]),
                "skill_backend": int(dev["skill_backend"]),
                "skill_db": int(dev["skill_db"]),
                "task_complexity": task_complexity,
                "story_points": story_points,
                "req_frontend": int(task_profile.get("reqFrontend", task_profile.get("req_frontend", 0))),
                "req_backend": int(task_profile.get("reqBackend", task_profile.get("req_backend", 0))),
                "req_db": int(task_profile.get("reqDb", task_profile.get("req_db", 0))),
                "current_tasks": int(dev["current_tasks"]),
                "current_workload": int(dev["current_workload"]),
                "availability": float(dev["availability"]),
                "skill_match_score": float(skill_match_score),
                "velocity_contribution": float(velocity_contribution),
                "skill_exp_inter": skill_exp_inter,
                "workload_density": workload_density,
                "complexity_sp_ratio": complexity_sp_ratio,
                "consistency": float(dev.get("consistency", 0.5)),
                "learning_rate": float(dev.get("learning_rate", 0.1)),
                "task_type_backend": 1 if task_type == "backend" else 0,
                "task_type_frontend": 1 if task_type == "frontend" else 0,
                "task_type_db": 1 if task_type == "db" else 0,
                "_dev_id": dev["dev_id"],
            }
            candidates.append(row)

    # =========================================
    # CREATE DATAFRAME AND ALIGN FEATURES
    # =========================================
    candidates_df = pd.DataFrame(candidates)
    dev_ids = candidates_df["_dev_id"].values
    candidates_df = candidates_df.drop(columns=["_dev_id"])

    # Align to model's expected features for prediction
    model_input_df = candidates_df.reindex(columns=feature_columns, fill_value=0)

    logger.info(f"✓ Prepared {len(candidates_df)} candidates for prediction")
    logger.debug(f"  Columns: {list(model_input_df.columns)}")

    # =========================================
    # ML PREDICTION
    # =========================================
    try:
        if hasattr(model, "predict_proba"):
            proba = model.predict_proba(model_input_df)[:, 1]
            predicted = np.round(proba * 100.0, 2)
            logger.info(f"✓ ML completion prob: min={predicted.min():.2f}%, max={predicted.max():.2f}%, mean={predicted.mean():.2f}%")
        else:
            predicted = model.predict(model_input_df)
            logger.info(f"✓ ML predictions: min={predicted.min():.2f}, max={predicted.max():.2f}, mean={predicted.mean():.2f}")
    except Exception as e:
        logger.error(f"Error in model prediction: {e}")
        raise

    # =========================================
    # EXTRACT FEATURES FOR FINAL SCORING
    # =========================================
    skill_match_array = candidates_df["skill_match_score"].values if "skill_match_score" in candidates_df.columns else np.zeros(len(candidates_df))
    raw_workloads = candidates_df["current_workload"].values.astype(float) if "current_workload" in candidates_df.columns else np.zeros(len(candidates_df))

    # ✅ WORKLOAD BALANCE & FAIRNESS:
    # 1. Capacity load ratio relative to developer's velocity / standard sprint capacity (>= 10 SP)
    if "dev_historical_velocity_avg" in candidates_df.columns:
        ref_capacities = np.maximum(10.0, candidates_df["dev_historical_velocity_avg"].values.astype(float))
    else:
        ref_capacities = np.full(len(candidates_df), 12.0)
    load_ratios = raw_workloads / ref_capacities
    workload_balance_array = np.exp(-1.5 * load_ratios)

    # 2. Task count fairness (smooth progressive capacity penalty)
    current_tasks_array = candidates_df["current_tasks"].values.astype(float)
    task_fairness = 1.0 / (1.0 + 0.25 * current_tasks_array)

    combined_workload_balance = np.clip(workload_balance_array * task_fairness, 0.0, 1.0)
    mean_workload = float(raw_workloads.mean()) if len(raw_workloads) > 0 else 0.0

    logger.info(
        f"  Workload balance — mean={mean_workload:.1f}, "
        f"min={combined_workload_balance.min():.3f}, max={combined_workload_balance.max():.3f}"
    )

    # =========================================
    # NORMALIZE ML PREDICTIONS
    # =========================================
    if is_empirical:
        pred_norm = np.clip(predicted, 0.0, 100.0)
    else:
        max_pred = predicted.max()
        if max_pred > 0:
            pred_norm = (predicted / max_pred) * 100
        else:
            pred_norm = np.zeros_like(predicted)

    # =========================================
    # BEHAVIORAL & GROWTH INCENTIVES (Option 3 Logic)
    # =========================================
    consistency_array = candidates_df["consistency"].values if "consistency" in candidates_df.columns else np.array([0.5] * len(candidates_df))
    learning_rate_array = candidates_df["learning_rate"].values if "learning_rate" in candidates_df.columns else np.array([0.1] * len(candidates_df))

    # Growth Incentive: Enable junior (exp=1) and mid-level (exp=2) developers for manageable tasks (complexity <= 6)
    # when they meet the baseline requirement threshold (skill_match >= 0.30)
    task_complexity = int(task_profile.get("taskComplexity", task_profile.get("task_complexity", 5)))
    growth_bonuses = []
    for _, dev_row in candidates_df.iterrows():
        exp = dev_row.get("experience_level", 1)
        lr = dev_row.get("learning_rate", 0.1)
        sm = dev_row.get("skill_match_score", 0.0)
        
        if task_complexity <= 6 and sm >= 0.30:
            # Junior (1) gets 1.0x, Mid (2) gets 0.5x, Senior (3) gets 0.0x
            growth_mult = max(0.0, (3 - min(exp, 3)) / 2.0)
            complexity_fit = max(0.0, 1.0 - (task_complexity / 8.0))
            bonus = lr * growth_mult * complexity_fit * 12.0
        else:
            bonus = 0.0
        growth_bonuses.append(bonus)

    growth_bonus_array = np.array(growth_bonuses)

    # =========================================
    # FINAL COMPOSITE SCORE
    # =========================================
    final_score = (
        pred_norm * active_weights.ml_prediction +
        skill_match_array * (active_weights.skill_match * 100.0) +
        combined_workload_balance * (active_weights.workload_balance * 100.0) +
        consistency_array * (active_weights.consistency * 100.0) +
        learning_rate_array * (active_weights.learning_rate * 100.0) +
        growth_bonus_array
    )
    final_score = np.clip(final_score, 0, 100)

    # =========================================
    # BUILD RESULTS
    # =========================================
    results = pd.DataFrame({
        "dev_id": dev_ids,
        "skill_frontend": dev_df["skill_frontend"].values if "skill_frontend" in dev_df.columns else np.zeros(len(dev_df)),
        "skill_backend": dev_df["skill_backend"].values if "skill_backend" in dev_df.columns else np.zeros(len(dev_df)),
        "skill_db": dev_df["skill_db"].values if "skill_db" in dev_df.columns else np.zeros(len(dev_df)),
        "experience_level": dev_df["experience_level"].values if "experience_level" in dev_df.columns else np.full(len(dev_df), 2),
        "current_tasks": dev_df["current_tasks"].values if "current_tasks" in dev_df.columns else np.zeros(len(dev_df)),
        "current_workload": dev_df["current_workload"].values if "current_workload" in dev_df.columns else np.zeros(len(dev_df)),
        "availability": dev_df["availability"].values if "availability" in dev_df.columns else np.ones(len(dev_df)),
        "skill_match_score": np.round(skill_match_array, 3),
        "predicted_performance": np.round(predicted, 2),
        "workload_balance": np.round(combined_workload_balance, 3),
        "learning_rate": np.round(learning_rate_array, 2),
        "consistency": np.round(consistency_array, 2),
        "growth_bonus": np.round(growth_bonus_array, 2),
        "final_score": np.round(final_score, 2)
    })

    # =========================================
    # SORT AND RANK
    # =========================================
    results = results.sort_values("final_score", ascending=False).reset_index(drop=True)
    results.insert(0, "rank", range(1, len(results) + 1))

    logger.info(f"✓ Ranked {len(results)} developers")
    if len(results) > 0:
        top = results.iloc[0]
        logger.info(f"  Top: {top['dev_id']} | skill_match={top['skill_match_score']:.3f} | final_score={top['final_score']:.2f}")

    return results


def allocate_sprint(
        tasks: list,
        dev_df: pd.DataFrame,
        model,
        feature_columns: list,
        weights: Optional[Union[CompositeWeights, Dict[str, float]]] = None
) -> list:
    """
    Allocate an entire sprint using GLOBAL LINEAR ASSIGNMENT (Hungarian Algorithm).
    
    Mathematically optimizes total sprint score while strictly balancing workload
    across the entire team and preventing single-developer task hoarding.
    """
    if not tasks or dev_df.empty:
        return []

    # Normalize column names immediately
    current_dev_df = dev_df.copy()
    column_mapping = {
        'devid': 'dev_id',
        'experienceinlevel': 'experience_level',
        'skillfrontend': 'skill_frontend',
        'skillbackend': 'skill_backend',
        'skilldb': 'skill_db',
        'currenttasks': 'current_tasks',
        'currentworkload': 'current_workload',
    }
    for old_name, new_name in column_mapping.items():
        if old_name in current_dev_df.columns:
            current_dev_df = current_dev_df.rename(columns={old_name: new_name})

    n_tasks = len(tasks)
    n_devs = len(current_dev_df)
    dev_ids = current_dev_df["dev_id"].tolist()

    logger.info(f"🚀 Starting GLOBAL LINEAR ASSIGNMENT for {n_tasks} tasks and {n_devs} developers...")

    # Calculate capacity slots per developer
    # If tasks > devs (e.g. 7 tasks, 3 devs), each developer gets ceil(7/3) = 3 slots
    max_slots_per_dev = max(1, math.ceil(n_tasks / n_devs))
    
    # Build candidate slot mapping: (dev_idx, dev_id, slot_num)
    slots = []
    for dev_idx, dev_id in enumerate(dev_ids):
        for slot_num in range(max_slots_per_dev):
            slots.append({
                "dev_idx": dev_idx,
                "dev_id": dev_id,
                "slot_num": slot_num
            })
            
    total_slots = len(slots)

    # Build Cost Matrix (N_tasks x Total_Slots)
    # Higher final_score -> Lower Cost in Hungarian minimization
    cost_matrix = np.zeros((n_tasks, total_slots))
    rankings_cache = {}

    for i, task in enumerate(tasks):
        # Rank developers for this task
        ranking_df = rank_developers_for_task(
            task_profile=task,
            dev_df=current_dev_df,
            model=model,
            feature_columns=feature_columns,
            weights=weights
        )
        rankings_cache[i] = ranking_df

        for j, slot in enumerate(slots):
            dev_id = slot["dev_id"]
            slot_num = slot["slot_num"]
            
            dev_row = ranking_df[ranking_df["dev_id"] == dev_id]
            if not dev_row.empty:
                base_score = float(dev_row.iloc[0]["final_score"])
            else:
                base_score = 0.0

            # Progressive slot penalty:
            # Slot 0 (1st task for dev) = base cost
            # Slot 1 (2nd task for dev) = +25 cost penalty
            # Slot 2 (3rd task for dev) = +50 cost penalty
            # This mathematically ensures the solver fills 1st slots across ALL devs before giving any dev a 2nd slot!
            slot_penalty = slot_num * 25.0
            cost = (100.0 - base_score) + slot_penalty
            cost_matrix[i, j] = cost

    # Solve Global Minimum Cost Bipartite Matching
    row_ind, col_ind = linear_sum_assignment(cost_matrix)

    # Build final allocations
    allocations = []
    for task_idx, slot_col in zip(row_ind, col_ind):
        assigned_slot = slots[slot_col]
        dev_id = assigned_slot["dev_id"]
        task = tasks[task_idx]
        
        ranking_df = rankings_cache[task_idx]
        dev_row = ranking_df[ranking_df["dev_id"] == dev_id].iloc[0]

        allocations.append({
            "task_id": task.get("id", task.get("taskId", f"task-{task_idx}")),
            "dev_id": dev_id,
            "allocated_to": dev_id,
            "match_score": float(dev_row["final_score"]),
            "skill_match": float(dev_row["skill_match_score"])
        })
        logger.info(f"✅ Globally Assigned Task {task.get('id')} to {dev_id} (Score: {dev_row['final_score']:.1f})")

    return allocations


def rank_sprint_tasks(
        tasks: list,
        dev_df: pd.DataFrame,
        model,
        feature_columns: list,
        top_n: int = 3,
        weights: Optional[Union[CompositeWeights, Dict[str, float]]] = None
) -> list:
    """
    Rank developers for each task in a sprint without final allocation.
    This provides top-N recommendations for an admin to choose from.

    KEY DESIGN: We maintain a LIVE copy of dev_df and update the virtual
    workload of the #1-ranked developer after each task. This ensures
    the recommendations promote other developers as loads accumulate.
    """
    logger.info(f"📋 Generating recommendations for {len(tasks)} tasks...")

    current_dev_df = dev_df.copy()

    # Normalise column names once
    column_mapping = {
        'devid': 'dev_id',
        'experienceinlevel': 'experience_level',
        'skillfrontend': 'skill_frontend',
        'skillbackend': 'skill_backend',
        'skilldb': 'skill_db',
        'currenttasks': 'current_tasks',
        'currentworkload': 'current_workload',
    }
    for old_name, new_name in column_mapping.items():
        if old_name in current_dev_df.columns:
            current_dev_df = current_dev_df.rename(columns={old_name: new_name})

    sorted_tasks = sorted(
        tasks,
        key=lambda x: x.get("storyPoints", x.get("story_points", 0)),
        reverse=True
    )

    sprint_recommendations = []

    for task in sorted_tasks:
        story_points = task.get("storyPoints", task.get("story_points", 3))

        # Rank all developers against current workload snapshot
        ranking_df = rank_developers_for_task(
            task_profile=task,
            dev_df=current_dev_df,
            model=model,
            feature_columns=feature_columns,
            weights=weights
        )

        top_recommendations = ranking_df.head(top_n).to_dict(orient="records")

        sprint_recommendations.append({
            "task_id": task.get("id", task.get("taskId", "unknown")),
            "task_title": task.get("title", task.get("name", "Unnamed Task")),
            "recommendations": top_recommendations
        })

        # Virtual workload update for top developer
        if not ranking_df.empty:
            top_dev_id = ranking_df.iloc[0]["dev_id"]
            mask = current_dev_df['dev_id'] == top_dev_id
            current_dev_df.loc[mask, "current_tasks"] += 1
            current_dev_df.loc[mask, "current_workload"] += story_points
            current_avail = current_dev_df.loc[mask, "availability"].values[0]
            new_avail = max(0.0, float(current_avail) - (story_points * 5))
            current_dev_df.loc[mask, "availability"] = new_avail
            logger.info(
                f"  📌 Virtual assign: {top_dev_id} → task {task.get('id')} "
                f"(+{story_points} SP | workload now {current_dev_df.loc[mask, 'current_workload'].values[0]:.0f})"
            )

    return sprint_recommendations