# SprintIQ — AI-Powered Agile Project Management SaaS

Multi-tenant Agile delivery platform. Spring Boot 3 / Java 21 backend, Next.js 14 frontend,
PostgreSQL + Redis, with four AI models integrated as first-class product capabilities.

> **Delivery status — Phases 1, 1b, 2, 3 and 4 backend complete; frontend partially.**
> The full backend is built and tested: schema, tenancy, security, the delivery core,
> the AI layer with all four models behind a swappable client, and the collaboration
> and analytics modules. On the frontend, the marketing site, auth, app shell, and the
> projects / backlog / AI-insights slices are wired to live endpoints.
> What remains is listed under [Not built yet](#not-built-yet).

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

## Frontend

Next.js 16 (App Router) · React 19 · Tailwind 4 · shadcn/ui on Radix · TanStack Query ·
Zustand · Zod.

```bash
make infra && make backend   # Postgres, Redis, Mailpit, then the API on :8080
cd frontend && pnpm dev      # http://localhost:3000
```

**Auth is a thin BFF.** The refresh token lives in an httpOnly `SameSite=Lax` cookie
that JavaScript cannot read; the access token is held in memory only and re-minted from
the cookie on boot. Only `/api/auth/*` goes through Next — everything else calls
`:8080` directly, so a board refresh does not pay for an extra hop.

**Token refresh is single-flight.** The backend rotates refresh tokens and revokes the
whole family on reuse, so six parallel 401s firing six refreshes would look like theft
and sign the user out of every device. One refresh, queued replays, asserted by test.

**Two motion budgets.** CSS on Radix `data-state` in the app (nothing over ~200ms — the
user is working); GSAP + ScrollTrigger on the marketing page, dynamically imported so
the signed-in bundle never carries it. `prefers-reduced-motion` is honoured and tested.

```bash
cd frontend
pnpm test        # 106 unit tests
pnpm typecheck
pnpm e2e         # 14 Playwright tests — needs the backend running
```

**AI layer** — an `AiModelClient` port with two implementations. The mock is
deterministic and feature-driven rather than a stub: a story with four acceptance
criteria and two dependencies really does come back bigger than a one-liner, and adding
scope to a running sprint really does move its risk score — so every panel, explanation
and Accept/Override interaction is demonstrable before any Python service exists.
Switch to the real services with `AI_PROVIDER=http`; endpoints and versions live in the
`ai_models` registry table, so swapping a model in is a configuration change.

Predictions are cached on a hash of the *feature vector*, not the entity id, so editing
a story invalidates its estimate automatically with no eviction logic to forget. Cache
hits are written as their own rows, which keeps usage countable and lets feedback attach
to the reader's own view of the suggestion.

**Collaboration** — comments with id-based mentions (`@[Name](uuid)`, so a rename cannot
misdirect a notification), a personal inbox with preference-aware delivery, attachments
with an allow-list and forced `Content-Disposition: attachment`, and a STOMP channel
interceptor that authenticates the CONNECT frame and refuses cross-tenant subscriptions —
the Hibernate filter cannot help there, because the broker never touches the database.

**Analytics** — velocity with its standard deviation, burndown, burn-up, CFD, cycle and
lead time as percentiles rather than means, completion rate, workload balance, cost trend
and risk summary. Assembled from the modules' published services, never their tables.

---

## Not built yet

Stated plainly so the gap is visible rather than discovered:

| Area | Status |
|---|---|
| Frontend: board, sprint planning/board/retro, team, reports, admin console | Endpoints exist; UI not built |
| Frontend: comments, notification centre, WebSocket subscription | Endpoints exist; UI not built |
| Report export (`POST /reports/export`) | Documented in the API map; not implemented |
| Developer-profile metric recomputation job | Columns and API exist; the nightly derivation does not |
| Malware scanning of attachments | `scan_status` is honestly `PENDING` on every row |
| Seed/demo data, Dockerfiles, CI workflow | Phase 5 |
| `pnpm lint` | 3 errors / 2 warnings, all pre-existing React Compiler findings in the Phase 1b auth code |

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
