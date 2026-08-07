# 05 · Phased Implementation Plan

| Phase | Scope | Exit criteria |
|---|---|---|
| **1 — Foundation** *(this delivery)* | Repo layout, Maven build, Docker Compose, full Flyway schema, tenancy engine, JWT + RBAC security, global error contract, pagination/filtering/sorting primitives, correlation IDs, audit infrastructure, OpenAPI, auth + users + organizations + workspaces modules | `docker compose up` → register → login → create org/workspace → invite member, all tenant-isolated |
| **1b — Frontend shell** *(delivered)* | Next.js app, design system, auth pages, app shell, org switcher, React Query + Zustand + Zod wiring, API client with token refresh | Login → dashboard skeleton against live backend |
| **2 — Delivery core** *(backend delivered)* | projects, milestones, requirements, epics, stories, tasks, subtasks, backlog ranking, sprints, capacity, boards, workflow states, labels, dependencies, time tracking | Full CRUD + Kanban drag/drop + sprint start/complete |
| **3 — AI layer** *(delivered)* | `AiModelClient` port + mock + HTTP client, feature assemblers, 4 model services, prediction persistence, caching, feedback loop, explainability payloads, feature flags, all AI UI panels | Every AI endpoint returns an envelope; every panel offers Accept/Override/Compare |
| **4 — Collaboration & analytics** *(backend delivered)* | comments, mentions, notifications + WebSocket, activity feed, attachments, all report endpoints + charts, admin console, audit UI, recycle bin, export | Reports render live data; realtime updates on board |
| **5 — Hardening** | Unit + integration (Testcontainers) + ArchUnit + Playwright e2e, seed/demo data, rate limiting, observability, Dockerfiles, CI workflow, deployment docs | `make test` green; demo tenant loads with 3 projects, 6 sprints, 40 stories |

Delivery order inside each phase: migration → domain entity → repository → service →
DTO/mapper → controller → tests → frontend feature slice.

## Where the build actually is

Phases 1, 1b, 2, 3 and the Phase 4 backend are complete, with 49 backend tests and 106
frontend unit tests green. The frontend has caught up as far as projects, backlog and
the AI panels; the board, sprint and reporting screens are the next slices, against
endpoints that already exist. `README.md` carries the full list of what is not built.

Two things were added to the plan while building phase 3, because the schema needed
them and the alternative was a growing list of exceptions:

- `AppendOnlyTenantEntity` for immutable tenant-scoped records (predictions, risk
  assessments, cost forecasts, notifications). They carry the tenant filter rather than
  relying on every future query author to remember an `organizationId` predicate.
- `@GlobalReferenceEntity` for platform reference data that is deliberately not
  tenant-scoped — currently just the AI model registry. The architecture test refuses
  any entity that is neither, so the exemption has to be stated on the class where a
  reviewer will see it.
