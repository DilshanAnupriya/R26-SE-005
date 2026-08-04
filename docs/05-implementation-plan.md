# 05 · Phased Implementation Plan

| Phase | Scope | Exit criteria |
|---|---|---|
| **1 — Foundation** *(this delivery)* | Repo layout, Maven build, Docker Compose, full Flyway schema, tenancy engine, JWT + RBAC security, global error contract, pagination/filtering/sorting primitives, correlation IDs, audit infrastructure, OpenAPI, auth + users + organizations + workspaces modules | `docker compose up` → register → login → create org/workspace → invite member, all tenant-isolated |
| **1b — Frontend shell** | Next.js app, design system, auth pages, app shell, org switcher, React Query + Zustand + Zod wiring, API client with token refresh | Login → dashboard skeleton against live backend |
| **2 — Delivery core** | projects, milestones, requirements, epics, stories, tasks, subtasks, backlog ranking, sprints, capacity, boards, workflow states, labels, dependencies, time tracking | Full CRUD + Kanban drag/drop + sprint start/complete |
| **3 — AI layer** | `AiModelClient` port + mock + HTTP client, feature assemblers, 4 model services, prediction persistence, caching, feedback loop, explainability payloads, feature flags, all AI UI panels | Every AI endpoint returns an envelope; every panel offers Accept/Override/Compare |
| **4 — Collaboration & analytics** | comments, mentions, notifications + WebSocket, activity feed, attachments, all report endpoints + charts, admin console, audit UI, recycle bin, export | Reports render live data; realtime updates on board |
| **5 — Hardening** | Unit + integration (Testcontainers) + ArchUnit + Playwright e2e, seed/demo data, rate limiting, observability, Dockerfiles, CI workflow, deployment docs | `make test` green; demo tenant loads with 3 projects, 6 sprints, 40 stories |

Delivery order inside each phase: migration → domain entity → repository → service →
DTO/mapper → controller → tests → frontend feature slice.
