# 04 · Folder Structure

```
sprintiq/
├── docker-compose.yml         postgres, redis, backend, frontend, (ai-mock)
├── Makefile                   up / down / seed / test / lint
├── .env.example
├── docs/                      these specs + ADRs
├── backend/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/com/sprintiq/pms/
│       ├── SprintIqApplication.java
│       ├── common/
│       │   ├── domain/        BaseEntity, TenantAwareEntity, SoftDeletable, DomainEvent
│       │   ├── error/         ApiError, ErrorCode, exceptions, GlobalExceptionHandler
│       │   ├── web/           PageResponse, PageQuery, SortParser, CorrelationIdFilter
│       │   ├── util/          KeyGenerator, LexoRank, Json
│       │   └── validation/    custom constraints
│       ├── config/            Security, Jpa, Redis, Cache, Async, OpenApi, WebSocket, RateLimit
│       ├── tenancy/           TenantContext, TenantResolutionFilter, TenantEntityListener,
│       │                      TenantFilterAspect, TenantTaskDecorator
│       └── modules/
│           ├── auth/          {domain,application,infrastructure,web}
│           ├── users/
│           ├── organizations/     (org, workspace, membership, role, invitation)
│           ├── projects/          (project, milestone, requirement, workflow, label)
│           ├── backlog/           (epic, story, dependency, ranking)
│           ├── sprints/           (sprint, capacity, metrics, retrospective)
│           ├── tasks/             (task, subtask, timer, board)
│           ├── comments/
│           ├── notifications/     (+ websocket publisher)
│           ├── files/
│           ├── analytics/         (report queries, chart DTOs)
│           ├── ai/                (ports, clients, orchestrator, 4 model services)
│           ├── audit/
│           └── settings/
│   ├── src/main/resources/
│   │   ├── application.yml, application-{dev,docker,prod,test}.yml
│   │   └── db/migration/V1..V9__*.sql
│   └── src/test/java/...       unit / integration (Testcontainers) / arch
└── frontend/
    ├── package.json, next.config.mjs, tailwind.config.ts, tsconfig.json
    └── src/
        ├── app/
        │   ├── (marketing)/page.tsx
        │   ├── (auth)/login | register | forgot-password | reset-password
        │   └── (app)/[orgSlug]/
        │       ├── layout.tsx        shell: sidebar + org switcher + command palette
        │       ├── dashboard/
        │       ├── projects/[projectId]/{overview,backlog,board,timeline,settings}
        │       ├── sprints/[sprintId]/{planning,board,dashboard,retro}
        │       ├── stories/[storyId]/ · tasks/[taskId]/
        │       ├── team/{workload,members}
        │       ├── ai-insights/
        │       ├── reports/
        │       ├── notifications/ · activity/
        │       └── settings/{profile,organization,members,roles,integrations,audit}
        ├── components/{ui,layout,charts,data-table,forms,feedback}
        ├── features/{auth,projects,backlog,sprints,tasks,board,team,ai,reports,admin}
        │        └── <feature>/{api.ts,hooks.ts,components/,schema.ts,types.ts}
        ├── hooks/   lib/   stores/   types/   validators/
        └── tests/   (vitest + playwright)
```
