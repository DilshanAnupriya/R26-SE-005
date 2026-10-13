"""Real Data Preprocessor for Empirical Agile Resource Allocation.

Ingests raw Jira sprint data from public open-source Agile repositories
(Randula Koralage Agile Scrum Sprint Velocity Dataset: Spring XD and Apache Mesos).

Enforces:
1. Strict temporal chronological splitting (Sprints 1-45 for training, Sprints 46-66 for held-out test).
2. Historical features computed strictly from past sprints (s < S_current).
3. No fabrication of human skill/experience; uses observable domain familiarity and completed task counts.
4. Objective blocker definition (only true dependency links, not general issue links).
5. Ground-truth target: task_completed_within_sprint.
"""

from __future__ import annotations

import io
import logging
import os
from pathlib import Path
import urllib.request
import numpy as np
import pandas as pd

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("real_preprocessor")

SPRING_XD_ISSUES_URL = (
    "https://raw.githubusercontent.com/RandulaKoralage/AgileScrumSprintVelocityDataSet/"
    "master/Agile%20Scrum%20Dataset/Finalized%20Datasets%20for%20Spring%20XD%20Project%20by%20Randula/"
    "Spring%20XD%20Issues%201992.csv"
)

SPRING_XD_SUMMARY_URL = (
    "https://raw.githubusercontent.com/RandulaKoralage/AgileScrumSprintVelocityDataSet/"
    "master/Agile%20Scrum%20Dataset/Finalized%20Datasets%20for%20Spring%20XD%20Project%20by%20Randula/"
    "Spring%20XD%20Issues%20Summary%202861.csv"
)

SPRING_XD_SPRINTS_URL = (
    "https://raw.githubusercontent.com/RandulaKoralage/AgileScrumSprintVelocityDataSet/"
    "master/Agile%20Scrum%20Dataset/Finalized%20Datasets%20for%20Spring%20XD%20Project%20by%20Randula/"
    "Spring%20XD%20Sprints%2067.csv"
)

DATA_DIR = Path(__file__).resolve().parent


def download_or_load_csv(url: str, local_filename: str) -> pd.DataFrame:
    """Download CSV if not cached locally, else read from disk."""
    local_path = DATA_DIR / local_filename
    if local_path.exists():
        logger.info("Loading cached %s", local_filename)
        return pd.read_csv(local_path)

    logger.info("Downloading %s from %s", local_filename, url)
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        content = resp.read().decode("utf-8", errors="replace")
        local_path.write_text(content, encoding="utf-8")
        return pd.read_csv(io.StringIO(content))


def classify_task_domain(summary: str, issue_type: str) -> str:
    """Deterministic, keyword-based domain classifier over task text.
    
    Categories: frontend, database, devops, backend.
    """
    text = (str(summary) + " " + str(issue_type)).lower()
    if any(k in text for k in ["ui", "css", "html", "web", "frontend", "console", "view", "gui", "dashboard"]):
        return "frontend"
    if any(k in text for k in ["sql", "db", "database", "redis", "schema", "table", "store", "repository", "jpa"]):
        return "database"
    if any(k in text for k in ["ci", "build", "gradle", "maven", "docker", "deploy", "release", "bamboo", "jenkins"]):
        return "devops"
    return "backend"


def build_empirical_dataset() -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    """Preprocess real Spring XD Agile data into chronologically valid ML datasets."""
    df_issues = download_or_load_csv(SPRING_XD_ISSUES_URL, "raw_spring_xd_issues_1992.csv")
    df_summary = download_or_load_csv(SPRING_XD_SUMMARY_URL, "raw_spring_xd_summary_2861.csv")
    df_sprints = download_or_load_csv(SPRING_XD_SPRINTS_URL, "raw_spring_xd_sprints_67.csv")

    logger.info("Raw shapes: issues=%s, summary=%s, sprints=%s", df_issues.shape, df_summary.shape, df_sprints.shape)

    # Clean story points: numeric conversion
    df_issues["storyPoint"] = pd.to_numeric(df_issues["storyPoint"], errors="coerce")
    df_issues["has_story_points"] = df_issues["storyPoint"].notna().astype(int)

    # Exclude records without valid assignee
    df_valid_issues = df_issues[df_issues["assignee"].notna() & (df_issues["assignee"] != "null")].copy()
    logger.info("Issues after filtering missing assignees: %d", len(df_valid_issues))

    # Merge on (key, sprint) to obtain exact sprint completion status
    merged = pd.merge(
        df_valid_issues,
        df_summary[["issueKey", "sprintId", "status", "initialStoryPoint", "currentStoryPoint"]],
        left_on=["key", "sprint"],
        right_on=["issueKey", "sprintId"],
        how="inner",
        suffixes=("", "_summary")
    )
    logger.info("Matched task assignments across issues and sprint summary: %d", len(merged))

    # Ground-truth target: 1 = Completed within sprint, 0 = Delayed/Not completed/Punted
    merged["task_completed_within_sprint"] = (merged["status_summary"] == "Completed").astype(int)
    merged["task_type"] = merged["issueType"]

    # Classify domain
    merged["task_domain"] = [
        classify_task_domain(s, t) for s, t in zip(merged["summary"], merged["issueType"])
    ]

    # Blockers and dependencies: strict separation
    # In Spring XD, dependedOn is the real dependency count
    depended_on = pd.to_numeric(merged.get("dependedOn", 0), errors="coerce").fillna(0)
    subtasks = pd.to_numeric(merged.get("subtasks", 0), errors="coerce").fillna(0)
    issue_links = pd.to_numeric(merged.get("issueLinks", 0), errors="coerce").fillna(0)

    merged["is_blocked"] = (depended_on > 0).astype(int)
    merged["dependency_count"] = (depended_on + subtasks).astype(int)
    merged["issue_link_count"] = issue_links.astype(int)
    merged["summary_char_length"] = merged["summary"].astype(str).str.len()

    # Normalize priority (Jira priorities typically 1 to 5)
    merged["priority_id"] = pd.to_numeric(merged.get("priority", 3), errors="coerce").fillna(3).astype(int)

    # Project and Developer Anonymization with project namespace
    project_id = "SPRING_XD"
    unique_assignees = sorted(merged["assignee"].unique())
    dev_id_map = {name: f"{project_id}_D{i+1}" for i, name in enumerate(unique_assignees)}
    merged["dev_id"] = merged["assignee"].map(dev_id_map)
    merged["project_id"] = project_id
    merged["sprint_id"] = merged["sprint"].astype(int)
    merged["task_id"] = merged["key"].astype(str)

    # Sort strictly chronologically by sprint and task
    merged = merged.sort_values(by=["sprint_id", "task_id"]).reset_index(drop=True)

    # =========================================================================
    # POINT-IN-TIME FEATURE EXTRACTION (STRICTLY HISTORICAL: s < S_current)
    # =========================================================================
    logger.info("Computing point-in-time historical signals across %d sprints...", merged["sprint_id"].nunique())

    dev_prior_completed_tasks = []
    dev_historical_velocity_avg = []
    dev_domain_familiarity = []
    is_cold_start_dev = []

    # Calculate retrospective sprint commitment load (committed backlog per sprint)
    dev_sprint_task_load_map = merged.groupby(["sprint_id", "dev_id"]).size().to_dict()
    dev_sprint_sp_load_map = merged.groupby(["sprint_id", "dev_id"])["storyPoint"].sum(min_count=1).fillna(0).to_dict()

    all_sprints = sorted(merged["sprint_id"].unique())

    for idx, row in merged.iterrows():
        cur_sprint = row["sprint_id"]
        cur_dev = row["dev_id"]
        cur_domain = row["task_domain"]

        # Historical records strictly before current sprint
        prior_records = merged[(merged["sprint_id"] < cur_sprint) & (merged["dev_id"] == cur_dev)]
        prior_completed = prior_records[prior_records["task_completed_within_sprint"] == 1]

        n_completed = len(prior_completed)
        dev_prior_completed_tasks.append(n_completed)

        if n_completed == 0:
            is_cold_start_dev.append(1)
            dev_domain_familiarity.append(0.0)
            # Impute rolling velocity from team mean up to sprint S-1
            team_prior = merged[(merged["sprint_id"] < cur_sprint) & (merged["task_completed_within_sprint"] == 1)]
            if len(team_prior) > 0:
                team_mean_vel = float(team_prior.groupby(["sprint_id", "dev_id"])["storyPoint"].sum().mean())
            else:
                team_mean_vel = 0.0
            dev_historical_velocity_avg.append(round(team_mean_vel, 2))
        else:
            is_cold_start_dev.append(0)
            # Domain familiarity: proportion of prior completed tasks in target domain
            domain_prior_count = len(prior_completed[prior_completed["task_domain"] == cur_domain])
            dev_domain_familiarity.append(round(domain_prior_count / n_completed, 4))

            # Rolling 3-sprint velocity average
            lookback_sprints = [s for s in all_sprints if s < cur_sprint][-3:]
            recent_prior = prior_completed[prior_completed["sprint_id"].isin(lookback_sprints)]
            if len(lookback_sprints) > 0:
                vel = float(recent_prior["storyPoint"].sum()) / len(lookback_sprints)
            else:
                vel = float(prior_completed["storyPoint"].sum())
            dev_historical_velocity_avg.append(round(vel, 2))

    merged["dev_prior_completed_tasks"] = dev_prior_completed_tasks
    merged["dev_historical_velocity_avg"] = dev_historical_velocity_avg
    merged["dev_domain_familiarity"] = dev_domain_familiarity
    merged["is_cold_start_dev"] = is_cold_start_dev

    # Assign sprint backlog commitment load
    merged["dev_sprint_task_load"] = [
        dev_sprint_task_load_map.get((s, d), 1) for s, d in zip(merged["sprint_id"], merged["dev_id"])
    ]
    merged["dev_sprint_story_point_load"] = [
        dev_sprint_sp_load_map.get((s, d), 0.0) for s, d in zip(merged["sprint_id"], merged["dev_id"])
    ]

    # =========================================================================
    # SELECT FINAL COLUMNS FOR REVISED SCHEMA
    # =========================================================================
    final_cols = [
        "project_id",
        "sprint_id",
        "dev_id",
        "task_id",
        "task_type",
        "task_domain",
        "story_points",
        "has_story_points",
        "priority_id",
        "is_blocked",
        "dependency_count",
        "issue_link_count",
        "summary_char_length",
        "dev_prior_completed_tasks",
        "dev_historical_velocity_avg",
        "dev_domain_familiarity",
        "dev_sprint_task_load",
        "dev_sprint_story_point_load",
        "is_cold_start_dev",
        "task_completed_within_sprint",
    ]

    # Rename storyPoint to story_points for consistency
    merged["story_points"] = merged["storyPoint"]
    df_clean = merged[final_cols].copy()

    # Fill NaN story points with 0 for model consumption, while keeping has_story_points=0 indicator
    df_clean["story_points"] = df_clean["story_points"].fillna(0.0)

    # =========================================================================
    # CHRONOLOGICAL SPLIT (Sprints 1-45 Train, Sprints 46-66 Held-Out Test)
    # =========================================================================
    SPLIT_SPRINT = 45
    df_clean["split"] = np.where(df_clean["sprint_id"] <= SPLIT_SPRINT, "train", "test")

    df_train = df_clean[df_clean["split"] == "train"].copy().drop(columns=["split"])
    df_test = df_clean[df_clean["split"] == "test"].copy().drop(columns=["split"])

    logger.info(
        "Split summary: Train (Sprints <= %d) = %d rows (%.1f%% completed); Test (Sprints > %d) = %d rows (%.1f%% completed)",
        SPLIT_SPRINT,
        len(df_train),
        df_train["task_completed_within_sprint"].mean() * 100,
        SPLIT_SPRINT,
        len(df_test),
        df_test["task_completed_within_sprint"].mean() * 100,
    )

    # Save to disk
    full_path = DATA_DIR / "real_agile_dataset.csv"
    train_path = DATA_DIR / "real_agile_train.csv"
    test_path = DATA_DIR / "real_agile_test.csv"

    df_clean.to_csv(full_path, index=False)
    df_train.to_csv(train_path, index=False)
    df_test.to_csv(test_path, index=False)

    logger.info("Saved clean datasets:\n  - %s\n  - %s\n  - %s", full_path, train_path, test_path)
    return df_clean, df_train, df_test


if __name__ == "__main__":
    build_empirical_dataset()
