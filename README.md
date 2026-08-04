# SprintIQ — AI-Powered Agile Project Management SaaS

Multi-tenant Agile delivery platform. Spring Boot 3 / Java 21 backend, Next.js 14 frontend,
PostgreSQL + Redis, with four AI models integrated as first-class product capabilities.

> **Delivery status — Phase 1 (Foundation) and the Phase 2 backend (Delivery core) complete.**
> Design specs, full database schema, tenancy engine, security, error contract, the
> auth / users / organizations / workspaces / audit modules, and the projects / backlog /
> sprints / tasks / activity modules are implemented.
> Phase 1b (Next.js shell) and Phases 3–5 (AI layer, collaboration & analytics, hardening)
> follow the plan in `docs/05-implementation-plan.md`.

---

## Read the design first

| Document | Contents |
|---|---|
| [`docs/00-product-spec.md`](docs/00-product-spec.md) | Feature list, personas, and the 14 explicit assumptions behind every design decision |
| [`docs/01-domain-model.md`](docs/01-domain-model.md) | Aggregates, entity fields, invariants |
| [`docs/02-architecture.md`](docs/02-architecture.md) | Layering, tenancy, security, AI integration layer, observability |
| [`docs/03-api-map.md`](docs/03-api-map.md) | Every v1 endpoint with filters and conventions |
| [`docs/04-folder-structure.md`](docs/04-folder-structure.md) | Backend and frontend tree |
| [`docs/05-implementation-plan.md`](docs/05-implementation-plan.md) | Phased plan with exit criteria |

---

## Quick start

```bash
cp .env.example .env
# generate a signing key — the app refuses to start without one in production
openssl rand -base64 64 | tr -d '\n' | sed -i '' "s|^JWT_SECRET=.*|JWT_SECRET=$(cat)|" .env

make up            # postgres + redis + mailpit + backend (+ frontend once Phase 1b lands)
make logs          # tail the backend
```

| Service | URL |
|---|---|
| API | http://localhost:8080/api/v1 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |
| Mail catcher | http://localhost:8025 |

Local development without Docker for the app itself:

```bash
make infra         # just postgres, redis, mailpit
make backend       # Spring Boot on :8080 with the dev profile
```

### First run

```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"Str0ng!Passw0rd#2026",
       "fullName":"Your Name","organizationName":"Acme Robotics"}'
```

This creates the user, provisions the tenant with a default workspace, assigns the OWNER
role and returns an access/refresh token pair. The verification email lands in Mailpit.

---

## What is implemented

**Tenancy** — `organization_id` on every tenant table, stamped by `TenantEntityListener`,
enforced by a Hibernate filter switched on around every repository call. Reads fail closed:
a missing tenant context throws rather than widening the query. Proven by `TenantIsolationIT`.

**Security** — BCrypt(12), 15-minute JWT access tokens, opaque 30-day refresh tokens stored
hashed with family-based rotation and reuse detection, RBAC via `@PreAuthorize("@perm.has(...)")`,
Redis rate limiting (tight on auth, generous on API), strict CORS, security headers.

**Error contract** — one `ApiError` shape for every failure, stable `ErrorCode` taxonomy,
field-level validation detail, correlation ID on every response and log line.

**Schema** — 5 Flyway migrations covering identity, tenancy, RBAC, the full agile hierarchy,
sprints and metrics, collaboration, the AI prediction/feedback tables, and governance.
Seeded with 37 permissions, 5 system roles and the 7-entry AI model registry.

**Modules** — auth (register, login, refresh, logout, password reset, email verification,
org switching), users, organizations, workspaces, members, invitations, custom roles, audit.

**Delivery core** — projects (lifecycle, membership, overview roll-up, explained health),
milestones, a requirements register that counts post-approval churn as an M2 feature,
per-project workflow states with WIP limits, labels, epics, stories, tasks and subtasks,
watchers, time tracking with a persisted start/stop timer, sprint boards and a project
kanban board, and a workload planner.

**Backlog ranking** — LexoRank-style string ranks, so reordering rewrites only the rows
that moved rather than every row below the drop point.

**Dependency graph** — edges stored in one canonical direction (`BLOCKED_BY` is persisted
inverted), with blocking cycles rejected at insert by a recursive CTE.

**Sprints** — commitment freezes on start and never moves again; work added afterwards is
recorded as scope creep, work removed as scope reduction. Completion measures delivery
against the frozen commitment and routes unfinished items to the backlog, the next sprint,
or a nominated one — never leaving them attached to a closed sprint. Burndown reads daily
`sprint_metrics` snapshots rather than recomputing from timestamps, so scope changes stay
visible.

**Cross-module boundaries** — the delivery modules never touch each other's repositories.
The project overview is assembled through a `ProjectStatisticsContributor` port that each
module implements, board-column occupancy through `WorkflowStateUsageCounter`, and
sprint↔story moves through the backlog module's published `SprintBacklogService`.

**Quality gates** — ArchUnit rules that fail the build if a controller touches a repository,
an entity escapes the service layer, or a tenant-scoped entity forgets to extend
`TenantAwareEntity`. Testcontainers integration tests run against real Postgres.

---

## Next: Phase 1b + Phase 3

1. Next.js app shell: design system, auth pages, org switcher, API client with silent refresh,
   then the delivery-core feature slices against the endpoints above.
2. Phase 3 wires the four AI models — `AiModelClient` port with a mock implementation, so
the AI features are demonstrable before any Python service exists.

## AI models at a glance

| Model | Capability | Where it appears |
|---|---|---|
| M1 | Story-point estimation + sprint velocity | Story editor, sprint planning capacity meter |
| M2 | Over-budget + requirement-change risk | Sprint dashboard widgets, planning risk cards |
| M3 | Developer ranking, consistency & learning scores | Task assignment assistant, workload board |
| M4 | Initial + mid-sprint cost forecast (planning only) | Planning cost widget, cost trend chart |

Every prediction returns a value, uncertainty bounds, ranked contributing drivers and a
plain-language explanation, and every panel offers **Accept / Override / Compare** with the
outcome written to `ai_feedback` and the audit trail.
