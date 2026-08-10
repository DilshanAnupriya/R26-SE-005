# Delivery status

**As of 10 August 2026** · branch `feat/phase-1b-frontend-shell` · head `c21bc4c`

A factual account of what exists in the repository, what is partially there, and what has
not been started. Written to be checkable: every claim below corresponds to code or a test
you can run, and anything unverified is named as such.

---

## The short version

| | |
|---|---|
| **Backend** | 17 modules, 27 controllers, 9 Flyway migrations. `mvn verify` green: **64 unit + 59 integration tests**. |
| **Frontend** | 26 routes, 13 feature slices, 10 BFF auth routes. **129 unit tests + 11 Playwright specs** green. |
| **Wired end to end** | 15 of 20 app screens call real endpoints. |
| **Not backed by anything** | 5 screens render an honest "not connected yet" panel: Roadmap, Timeline, Calendar, Documents, Automation. |
| **Known debt** | `pnpm lint` reports 6 errors / 3 warnings (all React Compiler findings, listed below). |

Run the gates yourself:

```bash
cd backend && ./mvnw verify
```

```bash
cd frontend && pnpm test && pnpm typecheck && pnpm e2e
```

---

## Backend — what is built

### Foundation

**Tenancy.** `organization_id` on every tenant table, stamped by `TenantEntityListener` and
enforced by a Hibernate `@Filter` switched on around every repository call. Reads fail
closed: a missing tenant context throws rather than silently widening the query. Proven by
`TenantIsolationIT`, which uses a *non*-system context — with `systemContext(true)` the
filter is off and the test would pass regardless of whether isolation worked.

**Security.** BCrypt(12), 15-minute JWT access tokens, opaque 30-day refresh tokens stored
hashed, family-based rotation with reuse detection, RBAC through
`@PreAuthorize("@perm.has(...)")`, Redis rate limiting, strict CORS, security headers.

**Error contract.** One `ApiError` shape for every failure, a stable `ErrorCode` taxonomy,
field-level validation detail, and a correlation ID on every response and log line.

**Quality gates.** ArchUnit rules fail the build if a controller touches a repository, an
entity escapes the service layer, or a tenant-scoped entity forgets `TenantAwareEntity`.
Integration tests run against real Postgres via Testcontainers.

### Modules

| Module | What it owns |
|---|---|
| `auth` | Register, login, refresh, logout, password reset, email verification, org switching |
| `users`, `organizations` | Members, invitations, custom roles, workspaces |
| `projects` | Lifecycle, membership, overview roll-up, explained health, milestones, requirements register, per-project workflow states with WIP limits, labels |
| `backlog` | Epics, stories, LexoRank ranking, dependency graph with cycle rejection |
| `sprints` | Commitment freezing, scope creep/reduction, close-out routing, daily `sprint_metrics` snapshots |
| `tasks` | Tasks, subtasks, watchers, time tracking with a persisted timer, boards |
| `goals` | Objectives, key results, check-ins, links to work items — **new** |
| `ai` | `AiModelClient` port, deterministic mock, prediction cache, feedback loop |
| `comments`, `notifications`, `files` | Mentions, personal inbox, attachments |
| `analytics` | Velocity, burndown, burn-up, CFD, cycle/lead time percentiles, workload, cost, risk |
| `activity`, `audit`, `admin`, `settings` | Activity feed, audit trail, feature flags, model registry, recycle bin |

### Decisions worth knowing

These are the places where the obvious implementation is wrong, recorded so nobody
"simplifies" them back:

- **Backlog ranking is LexoRank**, not an integer `position`. Reordering rewrites only the
  rows that moved, not every row below the drop point.
- **Dependency edges are stored in one canonical direction** — `BLOCKED_BY` is persisted
  inverted — with cycles rejected at insert by a recursive CTE.
- **Sprint commitment freezes on start and never moves.** Work added afterwards is scope
  creep, work removed is scope reduction, and completion measures against the frozen
  number. Burndown reads the daily snapshots rather than recomputing from timestamps, so
  scope changes stay visible instead of being retconned.
- **Modules never touch each other's repositories.** Project overview is assembled through
  a `ProjectStatisticsContributor` port, board-column occupancy through
  `WorkflowStateUsageCounter`, sprint↔story moves through the backlog module's published
  service. ArchUnit enforces this.
- **AI predictions are cached on a hash of the feature vector**, not the entity id, so
  editing a story invalidates its estimate automatically with no eviction logic to forget.
- **The CSV writer is hand-rolled.** A story titled `=HYPERLINK("http://evil","click")` is
  a legal title and a working attack on whoever opens the export; most CSV libraries do not
  defuse it.
- **OKR progress is distance travelled**, not the fraction of target reached. Defects going
  40 → 10 against a target of 0 is 75% done; `current / target` would report that genuine
  improvement as failure.
- **OKR status is paced against the calendar.** Every objective is 0% on day one, and
  marking that OFF_TRACK paints every new goal red until people stop reading the colour.

---

## Frontend — what is built

Next.js 16 (App Router) · React 19 · Tailwind 4 · shadcn/ui on Radix · TanStack Query ·
Zustand · Zod · GSAP on the marketing page only.

### Screens

| Screen | State |
|---|---|
| Home / dashboard | Wired — projects |
| My Work | Wired |
| Projects, project detail, project board, project sprints | Wired — projects, backlog, board, sprints, comments, AI |
| Board | Wired |
| Backlog & Sprints | Wired |
| **Goals & OKRs** | **Wired — new** |
| Reports | Wired |
| Team | Wired |
| AI Assistant | Wired |
| Notifications | Wired |
| Settings | Wired — admin, AI model registry |
| Roadmap, Timeline, Calendar, Documents, Automation | **Placeholder** — nothing in the database backs them |
| Login, register, forgot/reset password, verify email | Wired |
| Marketing landing page | Built |

The five placeholders render a `NotConnectedYet` panel that says so plainly rather than
showing plausible numbers. A mockup figure that reads as real is worse than an obvious
gap — someone will quote it in a meeting.

### Decisions worth knowing

- **Auth is a thin BFF.** The refresh token lives in an httpOnly `SameSite=Lax` cookie that
  JavaScript cannot read; the access token is in memory only and re-minted from the cookie
  on boot. Only `/api/auth/*` goes through Next — everything else calls `:8080` directly,
  so a board refresh does not pay for an extra hop.
- **Token refresh is single-flight.** The backend revokes the whole token family on reuse,
  so six parallel 401s firing six refreshes would look like theft and sign the user out of
  every device. One refresh, queued replays, asserted by 18 tests.
- **Two motion budgets.** CSS on Radix `data-state` in the app — nothing over ~200ms,
  because the user is working — and GSAP + ScrollTrigger on the marketing page, dynamically
  imported so the signed-in bundle never carries it. `prefers-reduced-motion` is honoured
  and tested.
- **The board is drag-and-drop *and* keyboard-operable.** Focus a card's handle, Enter to
  pick it up, arrows to choose a column, Enter to drop. Drag that only works with a mouse
  makes the product's primary interface unusable for anyone who cannot use one.
- **Realtime invalidates query keys rather than patching the cache** with the pushed
  payload. A push says *that* something changed; making it also say *what it now is* would
  let the pushed shape drift from the REST one.
- **AI suggestions are visibly suggestions** — dashed badge, "not yet accepted" for screen
  readers — and never become the story's estimate until someone presses Accept.

---

## Recently completed: Goals / OKRs

The most recent capability, done end to end rather than as another placeholder.

**Backend** (`8819ca3`) — migration `V9`, the `goals` module (objectives, key results,
check-ins, links to epics and stories), `goal:read` / `goal:manage` permissions.

**Frontend** (`c21bc4c`) — summary header, cycle and status filters, objectives with their
key results visible rather than behind a disclosure, a check-in dialog showing recent
history beside the input, and a create form that takes key results in the same pass. An
objective saved without numbers has no progress and no status to review.

**Three defects found by driving the real app, all fixed:**

1. **Status was frozen at the last write.** Status is a function of elapsed time as much as
   of the numbers, so a goal nobody checked into kept whatever verdict it held at its last
   check-in — the exact failure mode the calendar pacing existed to prevent.
   `Goal.statusOn` is now pure, and a nightly sweep re-paces every open goal.
   `GoalPacingIT` covers the cross-tenant read, which runs outside the Hibernate filter.
2. **`ProgressBar` claimed `role="progressbar"` with no accessible name** — everywhere it
   was used, not only on Goals. It now takes the role only when given a label.
3. **Key result rows announced as one unpunctuated run** (`Activation rate31%/60%0%`). They
   now carry an explicit label opening with the visible title, so voice control still
   matches what is on screen.

---

## What remains

### 1. Screens whose interiors predate the design

The shell, tokens and typography are adopted, and the design primitives exist in
`frontend/src/components/design/`. These screens work but still look like the pre-design
build inside their frames:

- Reports — charts not yet on the design's chart primitives
- Team — roster layout
- Settings — panel chrome
- Project detail — header and tab strip
- Board cards — item chrome

*Effort: mechanical. No new endpoints, no new decisions.*

### 2. Five screens with no backend at all

Each needs its own schema, module and endpoints before the screen can be wired — the same
one-capability-at-a-time shape Goals just followed:

| Screen | What it needs |
|---|---|
| Roadmap | Initiatives spanning quarters, with the epics that ladder into them |
| Timeline | A Gantt-shaped read model over existing epics, stories and sprints — possibly derivable from what exists rather than new tables |
| Calendar | Events, ceremonies, and dated items pulled from several modules |
| Documents | Document tree, versions, and the permission model that goes with it |
| Automation | Trigger/condition/action rules and an execution log |

*Effort: substantial. Roadmap and Timeline are the highest value — they are the two most
people expect a delivery tool to have.*

### 3. Gaps inside built features

| Area | Status |
|---|---|
| Issue detail screen | Board cards and backlog rows exist; no dedicated item view |
| Backlog planning view with capacity meter | Endpoints exist; the planning layout does not |
| Sprint retrospective UI | `GET/POST /sprints/{id}/retrospective` exists; the close-out dialog captures a summary, the full retro board does not |
| Attachment upload UI | Endpoints and storage exist; no picker is wired into the item view |
| Developer-profile metric recomputation | Columns and API exist; the nightly derivation does not, so measured metrics stay at their defaults |
| Malware scanning of attachments | `scan_status` is honestly `PENDING` on every row |

### 4. Known defects and debt

| Item | Detail |
|---|---|
| **Session boot fires twice** | Under React 19 StrictMode the boot exchange runs twice and both calls present the same refresh cookie. The backend has no grace window on reuse, so this can revoke the whole token family. The refresh path is protected by the single-flight latch; the boot path bypasses it. |
| `pnpm lint` | 6 errors / 3 warnings, all React Compiler findings: `react-hooks/set-state-in-effect` ×2, `react-hooks/refs` ×2, `react-hooks/incompatible-library` ×2 (react-hook-form's `watch()` cannot be memoized safely), `immutability`, `preserve-manual-memoization`, and one unused variable. Files: `session-provider.tsx`, `work-timer.tsx`, `topbar.tsx`, `charts.tsx`, and two auth forms. |
| `backend/data/attachments/` | Runtime upload directories accumulating in the working tree, not gitignored |
| README | Stale — still says Next.js 14, "106 unit tests", "Phases 1–4 complete", and has a duplicated `## Frontend` heading |

### 5. Phase 5 — not started

Seed/demo data, Dockerfiles for the app images, and a CI workflow.

---

## Test inventory

| Suite | Count | Command |
|---|---|---|
| Backend unit | 64 | `./mvnw test` |
| Backend integration (Testcontainers + real Postgres) | 59 | `./mvnw verify` |
| Frontend unit | 129 | `pnpm test` |
| Frontend e2e (Playwright, needs the backend up) | 11 | `pnpm e2e` |

Notable coverage: `TenantIsolationIT` (cross-tenant reads), `GoalPacingIT` (the sweep's
cross-tenant write), `KeyResultProgressTest` (21 cases on the OKR arithmetic in both
directions), `token-store.test.ts` (18 cases on single-flight refresh, including the
concurrency defects that would sign a user out everywhere).

**What the tests do not cover:** colour contrast is checked in Playwright, not in the axe
unit tests — jsdom has no canvas, so axe silently skips the contrast rule there. Treating
the unit suite as proof of contrast would be reading a green tick that was never earned.
