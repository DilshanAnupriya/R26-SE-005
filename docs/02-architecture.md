# 02 · High-Level Architecture

## Style
**Modular monolith, clean architecture per module**, packaged so each module can be lifted into a
service later. Modules communicate through published application services and Spring events — never
by reaching into another module's repositories.

```
                       ┌──────────────────────────────┐
   Browser ─── HTTPS ──►  Next.js 14 (App Router)     │  RSC + React Query + Zustand
                       │  server actions / route BFF  │
                       └──────────┬───────────────────┘
                                  │ REST + STOMP/WS (JWT)
                       ┌──────────▼───────────────────┐
                       │  Spring Boot 3 / Java 21     │
                       │  ┌────────────────────────┐  │
                       │  │ web  (controllers,DTO) │  │
                       │  │ application (services) │  │
                       │  │ domain (entities,rules)│  │
                       │  │ infrastructure (repos, │  │
                       │  │   clients, adapters)   │  │
                       │  └────────────────────────┘  │
                       │  cross-cutting: tenancy,     │
                       │  security, audit, error,     │
                       │  cache, events, observability│
                       └───┬───────────┬──────────┬───┘
                           │           │          │
                    ┌──────▼───┐ ┌─────▼────┐ ┌───▼─────────────┐
                    │PostgreSQL│ │  Redis   │ │ AI Model Gateway│
                    │ (Flyway) │ │cache/rate│ │  (HTTP port)    │
                    └──────────┘ │ limit/WS │ └───┬─────────────┘
                                 └──────────┘     │
                              ┌──────────────────┴──────────────────┐
                              │ M1 estimation │ M2 risk │ M3 alloc │ M4 cost │
                              │  (Python FastAPI services, or Mock) │
                              └─────────────────────────────────────┘
```

## Layering rules (enforced by ArchUnit tests)
- `web` → `application` → `domain`; `infrastructure` implements `domain` ports.
- Controllers contain **zero** business logic: validate, delegate, map, return.
- Entities never leave the service layer — MapStruct maps to DTOs.
- No module imports another module's `domain.*` or `infrastructure.*`.

## Multi-tenancy
1. `TenantResolutionFilter` reads `organizationId` from the JWT claim (or `X-Organization-Id`
   header for multi-org users) and validates membership.
2. `TenantContext` (ThreadLocal + TaskDecorator for async) holds `orgId`, `userId`, `roles`.
3. `TenantEntityListener` stamps `organization_id` on persist.
4. Hibernate `@FilterDef("tenantFilter")` is enabled per session → every query gets
   `AND organization_id = :orgId`. Missing context = `TenantNotResolvedException` (fail-closed).
5. Optional `V90__row_level_security.sql` adds Postgres RLS as defence in depth.

## Security
- BCrypt(12) passwords, JWT HS512 (RSA-ready), 15-min access / 30-day rotating refresh.
- Refresh-token reuse detection → revoke entire family.
- Method security via a custom `PermissionService` bean: `@PreAuthorize("@perm.has('sprint:manage', #projectId)")`.
- Redis sliding-window rate limiting: 5/min on auth endpoints, 600/min authenticated.
- Security headers, strict CORS allow-list, request-size caps, input validation on every DTO.

## AI integration layer
```
ai/
 ├─ domain/port/AiModelClient        (interface)
 ├─ infrastructure/http/HttpAiModelClient   (WebClient, retry, timeout, circuit breaker)
 ├─ infrastructure/mock/MockAiModelClient   (deterministic heuristics — default profile)
 ├─ application/
 │   ├─ FeatureAssembler        builds the model feature vector from the DB
 │   ├─ PredictionOrchestrator  cache → call → persist → publish event
 │   ├─ StoryPointService / VelocityService / RiskService /
 │   │   AllocationService / CostForecastService
 │   └─ FeedbackService         accept / override / dismiss
 └─ web/                        7 endpoints + history + feedback
```
- Cache key: `ai:{model}:{version}:{sha256(featureVector)}`, TTL 6 h, invalidated on entity update.
- Async: `@Async("aiExecutor")` for batch backlog grooming; results streamed over WebSocket.
- Circuit breaker → graceful degradation banner in UI, never a 500 to the user.

## Observability
Correlation ID filter (`X-Correlation-Id`) → MDC → JSON logs; Actuator health/metrics;
Micrometer timers on every AI call (`ai.prediction.latency{model,cacheHit}`).
