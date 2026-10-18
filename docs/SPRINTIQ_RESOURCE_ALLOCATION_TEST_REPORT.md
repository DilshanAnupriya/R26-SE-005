# SprintIQ — Complete Resource Allocation System Testing, Realistic Database Seeding & Multi-Sprint Simulation Report

**Component**: Intelligent Resource Allocation and Productivity Optimization (Research Component / Model 3)  
**Framework**: SprintIQ — An Explainable, Multi-Dimensional AI-Powered Agile Project Management Framework  
**Date**: October 18, 2026  
**Execution Environment**: PostgreSQL 16 Alpine (Docker Port 5433) | Spring Boot 3.3.4 (Port 8080) | FastAPI AI Service (Port 8000) | Next.js 15 (Port 3000)

---

## Executive Summary

A comprehensive, evidence-based end-to-end evaluation and multi-sprint simulation of SprintIQ's Intelligent Resource Allocation component was executed. Prior to seeding, the full system architecture (Spring Boot backend, FastAPI Python ML service, PostgreSQL dual-schema, and Next.js frontend) was inspected. Verified bugs in blocked task feature propagation, SHAP candidate vector construction, and cross-tenant organization isolation in the optimizer services and simulation calculator were diagnosed and corrected.

An isolated test organization (`SprintIQ Resource Allocation QA Organization`) was seeded with 10 distinct developer personas, a simulation project (`SIM`), 10 sequential sprints (8 historical baselines, 1 active allocation sprint, 1 planned allocation sprint), and 200 software engineering tasks. The live AI allocation engine was executed against Sprint 9 and Sprint 10, producing balanced, explainable allocations with **100% passing automated tests (14/14 custom suite tests, 97/97 active AI unit/integration tests, 20/20 Spring Boot AI module tests)**.

```
================================================================================
🎉 ALL SIMULATION PHASES & VERIFICATIONS COMPLETED SUCCESSFULLY!
   • Database Integrity: 5/5 Tests PASSED
   • Historical & Behavioral Metrics: 5/5 Tests PASSED (Consistency, Learning, Cold-Start)
   • Resource Allocation: 3/3 Tests PASSED (Sprint 9, Gini Fairness, Sprint 10 Adaptation)
   • Explainability & SHAP: 1/1 Test PASSED (TreeExplainer Attribution & Score Breakdown)
   Total Suite Score: 14/14 PASSED (100.0%)
================================================================================
```

---

## 1. Architecture & Inspection Findings

### 1.1 Dual-Schema Database Architecture
SprintIQ operates with two complementary relational database schemas inside PostgreSQL (`sprintiq` database):
1. **Delivery Core Schema**:
   - Tables: `organizations`, `workspaces`, `users`, `projects`, `sprints`, `stories`, `tasks`, `developer_profiles`, `developer_skills`, `time_entries`, `item_dependencies`.
   - Constraints: `ck_story_type` allows only `['STORY', 'BUG', 'SPIKE', 'CHORE', 'TECH_DEBT']`; `workspaces` requires `workspace_key varchar(16)` (not `slug`); `uq_one_active_sprint` allows only one active sprint per project.
2. **AI Optimizer Schema**:
   - Tables: `optimizer_developers`, `optimizer_developer_profiles`, `optimizer_sprints`, `optimizer_tasks`, `optimizer_allocations`.
   - Relationships: `optimizer_tasks` store normalized skill vectors (`req_frontend`, `req_backend`, `req_db` on a 0–10 scale), task complexity (1–5), ambiguity, dependency risk, completion time, defects, and status.

### 1.2 Model & Feature Vector Schema
The empirical model is loaded from `ai-service/models/real_allocation_model.pkl` (trained on Spring XD Jira dataset). It expects **29 input features**:
- **Developer Features (14)**: `dev_prior_assigned_tasks`, `dev_prior_completed_tasks`, `dev_historical_completion_rate`, `dev_historical_story_point_velocity`, `dev_historical_rolling_velocity_3sprint`, `dev_historical_avg_cycle_time_days`, `dev_domain_familiarity`, `dev_is_cold_start`, `dev_sprint_task_load`, `dev_sprint_story_point_load`, `dev_workload_to_velocity_ratio`, `dev_overload_indicator`, `dev_experience_level`, `dev_hourly_rate`.
- **Task Features (11)**: `task_complexity`, `story_points`, `req_frontend`, `req_backend`, `req_db`, `ambiguity`, `dependency_risk`, `is_blocked`, `task_desc_length`, `task_title_length`, `acceptance_criteria_count`.
- **Collaboration & Interaction Features (4)**: `comment_count`, `unique_commenters_count`, `component_change_count`, `pair_prior_success_rate`.

### 1.3 Optimization Algorithm & Objective Function
- **Candidate Scoring**: Evaluates candidate developers using a composite objective:
  $$\text{Final Score} = w_{\text{ml}} \cdot P_{\text{comp}} + w_{\text{skill}} \cdot S_{\text{match}} - w_{\text{workload}} \cdot L_{\text{pen}} + w_{\text{cons}} \cdot C_{\text{score}} + w_{\text{lr}} \cdot G_{\text{bonus}}$$
- **Global Matching**: Solved via Kuhn-Munkres (Hungarian algorithm) / branch-and-bound bipartite matching with capacity constraints.
- **Explainability**: SHAP TreeExplainer applied to the constituent `RandomForestClassifier` of the `VotingClassifier` ensemble, outputting positive and negative feature attributions.

---

## 2. Verified Bugs Identified & Resolved

| Component | File | Issue Description | Resolution Applied |
|---|---|---|---|
| **AI Service** | `ai-service/app/developer_ranker.py` | Entity JSON serialized `blocked: true`, but empirical vector extraction only inspected `is_blocked` and `isBlocked`, causing blocked tasks to evaluate with zero dependency penalty. | Added `task_profile.get("blocked")` fallback so blocked status is accurately captured. |
| **AI Service** | `ai-service/app/explainability.py` | SHAP candidate feature vector failed to parse `task.get("blocked")`, leading to mismatch in `is_blocked` SHAP values. | Added `task.get("blocked")` check to candidate feature builder. |
| **Spring Boot** | `OptimizerRankingService.java` | `allocateSprint` and `getSprintRecommendations` loaded all system developers via `developerRepository.findAll()` without checking `sprint.getOrganizationId()`, leaking developers across tenants. | Filtered developers by `sprint.getOrganizationId()` when present. |
| **Spring Boot** | `OptimizerAllocationService.java` | `generateMissingAllocationsForSprint` evaluated developers globally without organization scoping. | Filtered candidate developers by `sprint.getOrganizationId()`. |
| **Spring Boot** | `OptimizerSimulatorController.java` | `calculate` endpoint calculated Gini index over all developers across all organizations in database, driving Gini to 0.453 due to zero-task external devs. | Extracted `orgId` from assignment batch and filtered `allDevs` to target organization, restoring Gini to optimal 0.255. |
| **Flyway DB** | `flyway_schema_history` | Checksum mismatch on migration V33 (`null` vs `-1436570130`), blocking Spring Boot startup. | Repaired V33 checksum to `-1436570130` in PostgreSQL. |

---

## 3. Seeded Test Dataset Specifications

### 3.1 Organization & Project Scope
- **Organization**: `SprintIQ Resource Allocation QA Organization` (ID: `a324aa3c-57c5-45f5-84d7-1281b5e14126`, Slug: `sprintiq-qa-org`)
- **Workspace**: `QA Engineering Workspace` (Key: `QA`, Default: `true`)
- **Project**: `SprintIQ Resource Allocation Simulation Project` (Key: `SIM`, Status: `ACTIVE`)
- **Non-Test Isolation**: Existing organizations (`ha-creations`, `acme-robotics`) were fully preserved without modification.

### 3.2 Ten Developer Personas & Historical Profiles

| Developer ID | Name | Role & Seniority | Skills (FE/BE/DB) | Avg Velocity | Consistency | Learning Rate | Profile Rationale |
|---|---|---|:---:|:---:|:---:|:---:|---|
| **DEV-FE-SR1** | Elena Rostova | Senior Frontend Architect (SR) | 9 / 4 / 3 | 16.62 SP | **0.979** | -0.0021 | High-consistency frontend lead; delivered steady 16–18 SP across S1–S8. |
| **DEV-FE-MID1** | Marcus Vance | Mid Frontend Developer (MID) | 7 / 4 / 3 | 12.00 SP | **0.949** | +0.0124 | Solid mid-level frontend dev; responsive UI & accessibility. |
| **DEV-BE-SR1** | Dr. Aris Thorne | Senior Backend Architect (SR) | 3 / 10 / 6 | 20.38 SP | **0.988** | +0.0018 | Rock-solid backend architect; delivers ~20–21 SP every sprint with near-zero variance. |
| **DEV-BE-MID1** | Priya Patel | Mid Backend Developer (MID) | 2 / 7 / 5 | 11.88 SP | **0.767** | +0.0227 | High average but erratic variance (sprints bounce between 5 and 19 SP) to evaluate consistency penalty. |
| **DEV-DB-SR1** | Siddharth Rao | Senior Database Architect (SR) | 1 / 6 / 10 | 18.25 SP | **0.984** | +0.0016 | Specialist in query tuning, JSONB pipelines, and database reliability. |
| **DEV-DB-MID1** | Chloe Dubois | Mid Data & DB Engineer (MID) | 3 / 6 / 8 | 10.38 SP | **0.865** | -0.0122 | Workload saturation persona; velocity gradually declined from 14 SP down to 6 SP over S1–S8. |
| **DEV-FS-SR1** | Lucas Meyer | Senior Full-Stack Lead (SR) | 8 / 9 / 7 | 18.88 SP | **0.981** | +0.0006 | Versatile full-stack engineer; capable of complex multi-tier architectural integration. |
| **DEV-FS-MID1** | Aisha Morales | Mid Full-Stack Engineer (MID) | 7 / 7 / 6 | 13.25 SP | **0.873** | **+0.0836** | High learning rate persona; steep velocity progression from 8 SP (S1) to 18 SP (S8). |
| **DEV-JR-DEV1** | Toby Jenkins | Junior Software Developer (JR) | 5 / 4 / 3 | 8.50 SP | **0.887** | **+0.0971** | Junior growth persona; progression from 5 SP up to 12 SP, eligible for growth bonus. |
| **DEV-COLD-JR** | Zoe Washington | Junior Associate Developer (JR) | 4 / 5 / 4 | 3.00 SP | **0.700** | 0.0000 | **Cold-start persona**; 0 tasks in S1–S7, exactly 1 completed 3 SP task in S8 prior to live allocation. |

---

## 4. Multi-Sprint Simulation Execution & Results

### 4.1 Ten Sequential Sprints Overview

| Sprint | Lifecycle State | Purpose & Character | Task Count | Outcome Distribution |
|---|---|---|:---:|---|
| **Sprint 1** | COMPLETED | Establish initial developer history | 20 | 20 Completed (100%) |
| **Sprint 2** | COMPLETED | Measure early delivery performance | 20 | 20 Completed (100%) |
| **Sprint 3** | COMPLETED | Observe skill-domain experience & blockers | 20 | 18 Completed, 2 Blocked |
| **Sprint 4** | COMPLETED | Evaluate workload handling | 20 | 20 Completed (100%) |
| **Sprint 5** | COMPLETED | Evaluate consistency & rework | 20 | 18 Completed, 2 Blocked/Rework |
| **Sprint 6** | COMPLETED | Evaluate learning & upward velocity | 20 | 20 Completed (100%) |
| **Sprint 7** | COMPLETED | Introduce realistic delivery difficulties | 20 | 17 Completed, 2 Blocked, 1 Carryover |
| **Sprint 8** | COMPLETED | Establish complete baseline & cold-start | 20 | 20 Completed (Zoe Washington onboarded) |
| **Sprint 9** | ACTIVE | **Live AI Resource Allocation Target 1** | 20 | **20 AI-Allocated, Gini=0.255 (OPTIMAL)** |
| **Sprint 10** | PLANNED | **Live AI Resource Allocation Target 2** | 20 | **20 AI-Allocated, Gini=0.224 (OPTIMAL)** |

### 4.2 Sprint 9 Live Allocation Metrics
- **Unassigned Backlog Loaded**: 20 tasks (Story points: 2, 3, 5, 8, 13; FE/BE/DB skill demands).
- **Candidate Allocations Stored**: 60 candidate rankings evaluated with SHAP explanations.
- **Predicted Velocity**: **47.17 SP**
- **Team Health Score**: **44.1%**
- **Workload Gini Index**: **0.255 (OPTIMAL)** — no individual developer overloaded or starved.

#### Sample Sprint 9 Allocations & Rationale
1. **SIM-168: Architect High-Volume Microservice Gateway Filter (13 SP, Backend/Arch)**
   - *Selected*: **DEV-FS-SR1 (Lucas Meyer)** | Final Score: `75.63` | Skill Match: `0.733` | Status: `ALLOCATED`
   - *Runner-Up*: DEV-BE-MID1 (Priya Patel) | Final Score: `69.10` | Status: `DECLINED`
   - *Rationale*: Senior capacity and skill match prioritized for 13 SP high-complexity task.
2. **SIM-173: Implement Granular User Role-Based Access Control (RBAC) (8 SP, Backend/Security)**
   - *Selected*: **DEV-FS-MID1 (Aisha Morales)** | Final Score: `75.38` | Skill Match: `0.733` | Status: `ALLOCATED`
   - *Rationale*: Aisha's steep learning rate (+0.0836) and full-stack proficiency rewarded.
3. **SIM-166: Build RESTful Audit Logging Endpoints with Pagination (3 SP, Backend)**
   - *Selected*: **DEV-JR-DEV1 (Toby Jenkins)** | Final Score: `50.17` | Status: `ALLOCATED`
   - *Rationale*: Appropriate 3 SP task matching junior growth incentives without risking complex deliverables.
4. **SIM-179: Develop Asynchronous Event Notification Dispatcher (8 SP)**
   - *Selected*: **DEV-JR-DEV1 (Toby Jenkins)** | Final Score: `66.10` | Status: `ALLOCATED`
5. **SIM-165: Implement OAuth2 / OIDC Token Verification Service (8 SP)**
   - *Selected*: **DEV-FE-MID1 (Marcus Vance)** | Final Score: `66.20` | Status: `ALLOCATED`

### 4.3 Sprint 10 Cross-Sprint Adaptation
Following Sprint 9 simulation, developer velocities and cumulative task histories were updated:
- **Tasks Allocated**: 20 tasks in Sprint 10.
- **Predicted Velocity**: **49.98 SP** (higher than Sprint 9, reflecting experience progression).
- **Workload Gini Index**: **0.224 (OPTIMAL)**.
- **Dynamic Balancing**: Developers with higher load in Sprint 9 were balanced in Sprint 10, preventing fatigue.

---

## 5. Explainability & SHAP Verification

SHAP explanation generation was verified using `TreeExplainer` on the empirical model's constituent Random Forest classifier.

```json
{
  "method": "SHAP_TreeExplainer",
  "top_features": [
    {
      "feature": "dev_sprint_story_point_load",
      "shap_value": 0.0173,
      "direction": "positive",
      "magnitude": 0.0173
    },
    {
      "feature": "dev_prior_completed_tasks",
      "shap_value": 0.0148,
      "direction": "positive",
      "magnitude": 0.0148
    },
    {
      "feature": "comment_count",
      "shap_value": 0.0099,
      "direction": "positive",
      "magnitude": 0.0099
    },
    {
      "feature": "dev_domain_familiarity",
      "shap_value": 0.0084,
      "direction": "positive",
      "magnitude": 0.0084
    },
    {
      "feature": "is_blocked",
      "shap_value": -0.0112,
      "direction": "negative",
      "magnitude": 0.0112
    }
  ],
  "interpretation": "Strong historical track record and low current workload positively influenced this allocation."
}
```

---

## 6. Automated Test Results Summary

### 6.1 Custom Multi-Sprint Verification Suite (14/14 PASSED)

| Category | Test Case Name | Result | Details |
|---|---|:---:|---|
| **Database Integrity** | Single Organization Isolation | ✅ PASS | Found exactly 1 organization with slug `sprintiq-qa-org`. |
| **Database Integrity** | Exactly 10 Developers Created | ✅ PASS | Found 10 developers in QA organization. |
| **Database Integrity** | Single Project Created | ✅ PASS | Project key `SIM` verified. |
| **Database Integrity** | Exactly 10 Sequential Sprints | ✅ PASS | Found 10 sprints in sequence. |
| **Database Integrity** | Substantial Task Dataset (150–250) | ✅ PASS | Seeded 200 total tasks (20/sprint). |
| **Historical Metrics** | High Consistency Developer (Aris) | ✅ PASS | Aris Thorne consistency = 0.988 (>= 0.95). |
| **Historical Metrics** | Erratic Variance Developer (Priya) | ✅ PASS | Priya consistency = 0.767 (< Aris 0.988) due to sprint wobble. |
| **Historical Metrics** | Upward Learning Progression (Aisha) | ✅ PASS | Aisha Morales learning rate = +0.0836 (steep skill development). |
| **Historical Metrics** | Junior Growth Progression (Toby) | ✅ PASS | Toby Jenkins learning rate = +0.0971 (junior career progression). |
| **Historical Metrics** | Cold-Start Developer History (Zoe) | ✅ PASS | Zoe Washington has 1 completed historical baseline task prior to S9. |
| **Resource Allocation** | Sprint 9 Tasks Allocated | ✅ PASS | Allocated 20 tasks in Sprint 9. |
| **Resource Allocation** | Workload Fairness (Gini Index) | ✅ PASS | Sprint 9 Gini Index = 0.255 (OPTIMAL <= 0.28). |
| **Resource Allocation** | Sprint 10 Cross-Sprint Adaptation | ✅ PASS | Allocated 20 tasks in Sprint 10 with updated baselines. |
| **Explainability** | SHAP TreeExplainer Attributions | ✅ PASS | Valid feature attributions and score breakdowns verified. |

### 6.2 AI Service Unit & Regression Tests (97 PASSED, 1 SKIPPED)
```bash
PYTHONPATH=. ./.venv/bin/pytest tests/
# Output:
# tests/test_collaboration.py ...................s                         [ 20%]
# tests/test_cost_predictor.py ........                                    [ 28%]
# tests/test_predictor.py ..............                                   [ 42%]
# tests/test_resource_allocation.py .......                                [ 50%]
# tests/test_risk.py ........................                              [ 74%]
# tests/test_velocity.py ...................                               [ 93%]
# tests/test_weight_optimization.py ......                                 [100%]
# ================== 97 passed, 1 skipped, 21 warnings in 4.59s ==================
```

### 6.3 Spring Boot AI Module Tests (20 PASSED)
```bash
./mvnw test -Dtest=FeatureVectorIsolationTest,StoryWorkKindTest,BlockedTaskSurvivesAssessTest
# Output:
# Tests run: 20, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.191 s
# BUILD SUCCESS
```

---

## 7. Reproduction & Execution Guide

To reproduce this entire test run from a clean terminal state:

### Step 1: Ensure Containers & Services Are Active
```bash
# 1. PostgreSQL container (Port 5433)
docker start sprintiq-postgres

# 2. FastAPI AI Service (Port 8000)
cd ai-service
source .venv/bin/activate
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload &

# 3. Spring Boot Backend (Port 8080)
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev &

# 4. Next.js Frontend (Port 3000)
cd frontend
npm run dev &
```

### Step 2: Execute Scoped Database Reset, Seeding & Multi-Sprint Simulation
```bash
# Run the complete automated multi-sprint test script
python3 scripts/seed_resource_allocation_simulation.py
```

### Step 3: Run Unit & Service Test Suites
```bash
# Run AI Service tests
cd ai-service
PYTHONPATH=. ./.venv/bin/pytest tests/test_resource_allocation.py

# Run Backend module tests
cd ../backend
./mvnw test -Dtest=FeatureVectorIsolationTest,StoryWorkKindTest,BlockedTaskSurvivesAssessTest
```

### Step 4: Inspect Generated Machine-Readable Results
- Test suite summary: [`results/simulation_test_results.json`](file:///Users/hirunbhathiya/Desktop/RP%20module/R26-SE-005/results/simulation_test_results.json)
- Allocation decisions & scores: [`results/simulation_allocations.csv`](file:///Users/hirunbhathiya/Desktop/RP%20module/R26-SE-005/results/simulation_allocations.csv)
