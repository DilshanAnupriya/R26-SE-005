# 01 · Domain Model

## Aggregates & ownership

```
Organization (TENANT ROOT)
├── OrgMember ──► User (global identity)
├── Role ──► Permission
├── Invitation
├── Workspace
│   ├── WorkspaceSettings / FeatureFlag
│   ├── Team ── TeamMember ──► DeveloperProfile ── DeveloperSkill ──► Skill
│   └── Project
│       ├── ProjectMember
│       ├── Milestone
│       ├── Requirement
│       ├── WorkflowState (custom statuses)
│       ├── Epic
│       │   └── Story ──── Task ──── Subtask(Task with parent)
│       ├── Sprint ── SprintItem ── SprintMetrics ── Retrospective ── RetroActionItem
│       ├── Label / ItemLabel
│       ├── Dependency / Watcher / Comment / Attachment / TimeEntry
│       └── ActivityLog
├── CapacityPlan (member × sprint)
├── Notification
├── AuditLog
└── AI: AiModel ── AiPrediction ── AiFeedback
        ├── RiskAssessment
        ├── ResourceAllocationSuggestion
        └── CostForecast
```

## Core entities

**User** — global identity. `id, email, passwordHash, fullName, avatarUrl, timezone, locale,
status(PENDING|ACTIVE|SUSPENDED), emailVerifiedAt, mfaEnabled, lastLoginAt`.

**Organization** — tenant root. `id, name, slug, plan, currency, timezone, workingDays,
hourlyRateDefault, status`.

**Workspace** — grouping inside a tenant. `id, orgId, name, key, description`.

**OrgMember** — `orgId, userId, roleId, status, invitedBy, joinedAt`. Unique `(orgId,userId)`.

**Role / Permission** — `Role(orgId nullable → null = system role, name, systemRole bool)`,
`Permission(code 'project:update', resource, action)`, join `role_permissions`.

**Project** — `id, orgId, workspaceId, key, name, description, status(PLANNING|ACTIVE|ON_HOLD|
COMPLETED|ARCHIVED), health, startDate, targetDate, budgetAmount, leadUserId, progressPct`.

**Epic** — `id, orgId, projectId, key, title, description, status, color, priority, startDate,
targetDate, milestoneId`.

**Story** (backlog item) — `id, orgId, projectId, epicId, key, title, description, type(STORY|BUG|
SPIKE|CHORE), status, priority, severity, storyPoints, aiStoryPoints, aiConfidence, estimateSource
(MANUAL|AI_ACCEPTED|AI_OVERRIDDEN), rank, assigneeId, reporterId, sprintId, dueDate,
acceptanceCriteria, readyChecklist, doneChecklist, blocked, blockedReason, deletedAt`.

**Task** — `id, orgId, projectId, storyId, parentTaskId, key, title, status, priority,
assigneeId, estimateMinutes, spentMinutes, dueDate, complexityScore, rank, blocked, deletedAt`.

**Sprint** — `id, orgId, projectId, name, goal, status(PLANNED|ACTIVE|COMPLETED|CANCELLED),
startDate, endDate, durationDays, capacityPoints, committedPoints, completedPoints,
plannedCostAmount, forecastCostAmount, actualEffortMinutes, sequenceNo`.

**SprintMetrics** — one row per sprint per snapshot day: `completedPoints, remainingPoints,
scopeAddedPoints, scopeRemovedPoints, blockedCount, reopenedCount, defectCount,
requirementChanges, workloadIndex, velocity`.

**DeveloperProfile** — `userId, orgId, seniority(JUNIOR|MID|SENIOR|LEAD), yearsExperience,
hourlyRate, weeklyCapacityHours, availabilityRate, consistencyScore, learningRate,
avgVelocity, defectRate, reopenRate, blockedFrequency, lastComputedAt`.

**AiModel** — registry: `code(M1_STORY_POINTS…), name, version, endpointUrl, mode(ZERO_SHOT|
FEW_SHOT|FINE_TUNED), enabled, latencyBudgetMs`.

**AiPrediction** — `id, orgId, modelCode, modelVersion, entityType, entityId, requestPayload jsonb,
predictedValue numeric, predictedLabel, confidence, lowerBound, upperBound, drivers jsonb,
explanation, mode, latencyMs, cacheHit, createdBy, createdAt`.

**AiFeedback** — `predictionId, action(ACCEPTED|OVERRIDDEN|DISMISSED), finalValue, reason, userId`.

**RiskAssessment** — `sprintId, riskType(OVER_BUDGET|REQUIREMENT_CHANGE), score, label,
severity, drivers jsonb, mitigations jsonb, predictionId`.

**ResourceAllocationSuggestion** — `taskId, candidateUserId, rank, fitScore, predictedPerformance,
consistencyScore, learningRate, currentLoadPct, reasoning jsonb, predictionId, chosen bool`.

**CostForecast** — `sprintId, stage(INITIAL|MID_SPRINT), forecastAmount, errorMarginAmount,
currency, trend(UP|FLAT|DOWN), inputs jsonb, predictionId, snapshotAt`.

**AuditLog** — `orgId, actorUserId, action, entityType, entityId, before jsonb, after jsonb,
ip, userAgent, correlationId, createdAt`.

## Key invariants
1. Every tenant-scoped row carries `organization_id`; repositories are unreachable without a
   populated `TenantContext` (fail-closed).
2. A Story may belong to at most one active Sprint (`sprint_id`); moving it writes an ActivityLog.
3. `committed_points` freezes when a Sprint transitions `PLANNED → ACTIVE`; later additions are
   recorded as `scope_added_points`.
4. Dependency graph must remain acyclic — validated on insert with a recursive CTE.
5. Accepting an AI estimate sets `estimate_source = AI_ACCEPTED` and stores `prediction_id`;
   editing it afterwards flips it to `AI_OVERRIDDEN` and writes `ai_feedback`.
6. Soft-deleted rows are excluded by a global Hibernate `@Where` clause; restore is an admin action.
