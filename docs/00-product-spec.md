# SprintIQ — AI-Powered Agile Project Management SaaS
## 00 · Product Specification & Assumptions

### 1. Positioning
SprintIQ is a multi-tenant Agile delivery platform. It covers the full Agile loop
(plan → execute → track → retrospect) and layers four AI models on top so that every
prediction is **attached to a decision the user is about to make**, never a floating number.

Personas: Product Owner, Scrum Master, Developer, Engineering Manager, Org Admin.

---

### 2. Explicit assumptions (SaaS-grade defaults)

| # | Area | Assumption |
|---|------|-----------|
| A1 | Tenancy | **Shared database, shared schema, discriminator column** (`organization_id`) enforced by a Hibernate filter + a mandatory `TenantContext`. Postgres RLS policies ship as an optional hardening migration. Organization = billing/tenant root; Workspace = sub-container; Project lives in a Workspace. |
| A2 | Identity | `users` is a **global identity** (one email = one login). Access is granted per-organization via `org_members`. This enables inviting the same person to multiple orgs. |
| A3 | Auth | Stateless **JWT access token (15 min) + rotating refresh token (30 d, stored hashed in DB, revocable)**. MFA (TOTP) tables and flow states exist and are wired but enrollment is disabled behind a feature flag. |
| A4 | RBAC | Role → Permission mapping, permissions are `resource:action` strings. 5 system roles seeded per org (`OWNER`, `ADMIN`, `MANAGER`, `MEMBER`, `VIEWER`) + custom roles per org. Enforced at method level via `@PreAuthorize("@perm.has('project:update', #projectId)")`. |
| A5 | AI models | The 4 models live **outside** the monolith and are reached over HTTP (`/v1/predict`) via a `AiModelClient` port. A `MockAiProvider` implementation ships so the whole product is runnable with zero Python services. Switching is a config flag (`app.ai.provider=mock\|http`). |
| A6 | AI contract | Every prediction returns `value`, `confidence`, `lowerBound`, `upperBound`, `modelVersion`, `drivers[] {feature, contribution, direction}`, `explanation`. Persisted to `ai_predictions` with the exact feature vector for later retraining. |
| A7 | AI UX | Every AI output is rendered as **Accept / Override / Compare**. Overrides are written to `ai_feedback` + `audit_logs`. |
| A8 | Money | Cost forecasting is **planning-only**. No billing, invoicing, profit, or payment code exists anywhere in the system. Currency is per-organization, amounts are `NUMERIC(14,2)`. |
| A9 | Files | Attachments use a `StorageProvider` port; `LocalDiskStorage` ships, S3 impl is a drop-in. Pre-signed style download URLs via short-lived tokens. |
| A10 | Realtime | STOMP over WebSocket (`/ws`), topics are tenant-scoped: `/topic/org.{orgId}.project.{projectId}`. Handshake authenticates with the same JWT. |
| A11 | Deletes | Soft delete (`deleted_at`) for Project, Epic, Story, Task, Sprint, Comment, Attachment. Hard delete only for tokens/sessions. 30-day recovery window surfaced in Admin → Recycle Bin. |
| A12 | Repo | Polyrepo-in-a-monorepo: `/backend` (Gradle-free Maven) and `/frontend` (pnpm) with a root `docker-compose.yml`. |
| A13 | IDs | UUID v4 primary keys everywhere; human-readable keys (`PROJ-142`) generated per project via a sequence table. |
| A14 | Time | All timestamps `TIMESTAMPTZ`, stored UTC, rendered in user timezone. |

---

### 3. Complete feature list

#### 3.1 Platform
- Email/password registration, login, logout-all-devices
- Email verification, password reset (single-use hashed tokens)
- Refresh-token rotation + reuse detection
- MFA-ready (TOTP secret, recovery codes, challenge state machine)
- RBAC: system + custom roles, permission matrix editor
- Multi-tenancy with hard isolation, tenant guard on every query
- Organization CRUD, branding, timezone, currency, working days
- Workspaces, workspace settings, per-workspace AI feature flags
- Member invitations (token link, expiry, resend, revoke)
- Rate limiting (Redis sliding window, per-IP + per-user)
- Correlation IDs, structured JSON logging, Actuator + Prometheus
- Global error contract, OpenAPI 3 docs, seeded demo data

#### 3.2 Delivery
- Projects: create, archive, health (`ON_TRACK/AT_RISK/OFF_TRACK`), progress %, key
- Project members with per-project role, stakeholders, watchers
- Milestones + timeline (Gantt-lite)
- Epics → Stories → Tasks → Subtasks hierarchy
- Requirements register linked to epics/stories, traceability matrix
- Backlog: ordered (LexoRank-style `rank` string), bulk edit, quick filters
- Definition of Ready / Definition of Done checklists per project
- Sprints: draft → active → completed, goal, capacity, commitment
- Sprint planning (drag from backlog, capacity meter, AI panels)
- Sprint board + Kanban board with WYSIWYG drag/drop, WIP limits
- Custom status workflow per project with transition rules
- Priority, severity, story points, estimates, due dates
- Assignees, watchers, labels, components
- Dependencies (`blocks`, `blocked_by`, `relates_to`) + cycle detection
- Blocked flag + blocker reason + auto-escalation
- Retrospectives (what went well / didn't / actions), action item tracking
- Time tracking (start/stop timer + manual entries), timesheet view
- Capacity & availability (per-member per-sprint hours, absences)
- Workload planner with over-allocation detection

#### 3.3 Collaboration
- Threaded comments with `@mentions` and rich text
- Activity feed (per project / per item / per user), field-level change history
- Notifications: in-app, WebSocket push, digest-ready email hooks, per-type prefs
- Attachments with type/size validation and virus-scan hook

#### 3.4 Analytics
- Velocity chart (committed vs completed, N-sprint rolling)
- Burndown & burnup (points and count, scope-change line)
- Cumulative flow diagram
- Lead time / cycle time + control chart
- Sprint completion rate, spillover, scope volatility
- Team productivity, defect rate, reopen rate
- Workload balance / Gini coefficient
- Budget & cost forecast trend
- Risk indicator board

#### 3.5 AI capabilities (see §4)
1. Story point estimation + sprint velocity prediction
2. Sprint risk prediction (over-budget, requirement-change)
3. Intelligent resource allocation & productivity optimization
4. Sprint cost forecasting (initial + mid-sprint)

Cross-cutting: prediction history, explainability payloads, model registry &
versioning, per-workspace feature flags, accept/override feedback loop, async
execution with Redis caching, batch prediction for backlog grooming.

#### 3.6 Governance
- Audit trail (actor, action, entity, before/after diff, IP, correlation ID)
- User & role management console
- Configuration settings, integrations registry
- Data export (CSV/JSON per entity, async job)
- Soft delete + recycle bin restore

---

### 4. AI capability contracts

| Model | Endpoint | Anchored UI action |
|-------|----------|--------------------|
| **M1a** Story points | `POST /api/v1/ai/estimation/story-points` | Story create/edit panel → Accept sets `story_points` |
| **M1b** Velocity | `POST /api/v1/ai/estimation/velocity` | Sprint planning capacity meter → sets commitment target |
| **M2a** Over-budget risk | `POST /api/v1/ai/risk/over-budget` | Sprint dashboard risk widget → creates mitigation task |
| **M2b** Req-change risk | `POST /api/v1/ai/risk/requirement-change` | Planning risk card → flags volatile epics |
| **M3** Assignee ranking | `POST /api/v1/ai/allocation/rank` | Task assignment assistant → sets `assignee_id` |
| **M4a** Initial cost | `POST /api/v1/ai/cost/initial` | Planning cost widget → stores baseline forecast |
| **M4b** Mid-sprint cost | `POST /api/v1/ai/cost/mid-sprint` | Dashboard cost trend → cost-awareness alert |

Every response conforms to `PredictionEnvelope<T>`; every acceptance/override writes
`ai_feedback` and an audit entry.
