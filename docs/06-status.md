# Delivery status

**As of 10 August 2026** · branch `feat/phase-1b-frontend-shell` · head `954901e`

A factual account of what exists in the repository, what is partially there, and what has
not been started. Written to be checkable: every claim below corresponds to code or a test
you can run, and anything unverified is named as such.

---

## The short version

| | |
|---|---|
| **Backend** | 19 modules, 31 controllers, 11 Flyway migrations. `mvn verify` green: **83 unit + 82 integration tests**. |
| **Frontend** | 26 routes, 17 feature slices, 10 BFF auth routes. **167 unit tests + 11 Playwright specs** green. `pnpm lint` clean. |
| **Wired end to end** | 19 of 20 app screens call real endpoints. |
| **Not backed by anything** | 1 screen: Automation. |

Run the gates yourself:

```bash
cd backend && ./mvnw verify
```

```bash
cd frontend && pnpm test && pnpm typecheck && pnpm lint && pnpm e2e
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
| `goals` | Objectives, key results, check-ins, links to work items |
| `roadmap` | Initiatives, the epics they are made of, quarter lanes |
| `documents` | Folder/page tree, markdown bodies, full version history |
| `ai` | `AiModelClient` port, deterministic mock, prediction cache, feedback loop |
| `comments`, `notifications`, `files` | Mentions, personal inbox, attachments |
| `analytics` | Velocity, burndown, burn-up, CFD, cycle/lead time percentiles, workload, cost, risk — and the Timeline and Calendar read models |
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
| **Roadmap** | Wired — initiatives |
| **Timeline** | Wired — assembled from epics, sprints and milestones |
| **Calendar** | Wired — everything already dated |
| **Documents** | Wired — tree and version history |
| Automation | **Placeholder** — nothing in the database backs it |
| Login, register, forgot/reset password, verify email | Wired |
| Marketing landing page | Built |

The one remaining placeholder renders a `NotConnectedYet` panel that says so plainly
rather than showing plausible numbers. A mockup figure that reads as real is worse than an
obvious gap — someone will quote it in a meeting.

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

## Recently completed

Five slices since the last revision of this document, each verified against the running
app rather than only against tests.

**Goals / OKRs** — objectives, key results, check-ins, calendar-paced status. Driving it
found that status was frozen at the last write: it is a function of elapsed time as much
as of the numbers, so a goal nobody checked into kept whatever verdict it held months ago.
`Goal.statusOn` is pure now and a nightly sweep re-paces every open goal.

**Roadmap** — initiatives with progress read off the linked epics, never typed in; quarter
lanes derived from target dates so a slip moves the card by itself. The backlog reaches it
through `InitiativeWorkSource`, which an ArchUnit rule keeps as the only route.

**Timeline** — a Gantt view with no timeline table. Every bar reports where its dates came
from, and only a genuinely inferred span is drawn as uncertain: a story carrying its
sprint's window is a commitment somebody made, and dashing it would get it ignored.

**Calendar** — a month of everything already dated. Building it surfaced a bug in the
roadmap query: its optional date window was written as `:from IS NULL OR …`, which hands
Postgres an untyped parameter and gets the whole statement refused. The roadmap screen
never passed a window, so nothing had run that path. A query parameter no caller exercises
is a query parameter nobody has run.

**Documents** — a folder/page tree where every title and body change is kept, restores
append rather than rewind, and deleting a folder takes its contents rather than orphaning
them somewhere no tree walk reaches.

**Defect fixes** — the session boot exchange fired twice under StrictMode with the same
refresh cookie, which the backend treats as theft; it is latched now, and the test caught a
wrong first attempt that left the app stuck on "loading". All nine React Compiler lint
findings are cleared, including a `ProgressBar` that claimed `role="progressbar"` with no
accessible name everywhere it was used.

---

## What remains

### 1. Automation — the last unbacked screen

The only screen still showing a placeholder. It needs more than a table: a rule is a
trigger, some conditions and some actions, and something has to fire it when a story
moves. There is no event bus in the codebase yet, so this is a rule engine plus the
plumbing to feed it, plus a run log so a rule that silently did nothing is visible.

*Not started. Deliberately left as an honest placeholder rather than a rules screen whose
rules never fire, which is worse than nothing — it would look finished.*

### 2. Screens whose interiors predate the design

The shell, tokens and typography are adopted, and the design primitives exist in
`frontend/src/components/design/`. These screens work but still look like the pre-design
build inside their frames: Reports charts, Team roster, Settings panels, project detail
header, board card chrome.

*Effort: mechanical. No new endpoints, no new decisions.*

### 3. Gaps inside built features

| Area | Status |
|---|---|
| Issue detail screen | Board cards and backlog rows exist; no dedicated item view |
| Backlog planning view with capacity meter | Endpoints exist; the planning layout does not |
| Sprint retrospective UI | `GET/POST /sprints/{id}/retrospective` exists; the close-out dialog captures a summary, the full retro board does not |
| Attachment upload UI | Endpoints and storage exist; no picker is wired into the item view |
| Developer-profile metric recomputation | Implemented via hybrid engine: deterministic statistical post-sprint recomputation + ML sync loop |
| Malware scanning of attachments | `scan_status` is honestly `PENDING` on every row |
| Calendar events | The calendar shows commitments the system already tracks. Meetings and time off are not modelled, and the screen says so |

### 4. Known debt

| Item | Detail |
|---|---|
| Async audit writes fail in integration tests | `AuditService.record` is `@Async`, so it runs outside the test's rolled-back transaction and its insert hits a foreign key that has not committed. Tests pass; the logs are full of stack traces that train everyone to ignore them |
| Roadmap and initiative progress are recomputed nightly | The backlog cannot notify a roadmap card when a story closes without the two modules knowing about each other, so a card is at most a day stale. An event bus — the same one Automation needs — would close this |
| README | Stale: still says Next.js 14, "106 unit tests", "Phases 1–4 complete", and has a duplicated `## Frontend` heading. Superseded by this document |

### 5. Phase 5 — not started

Seed/demo data, Dockerfiles for the app images, and a CI workflow. The seeding scripts used
to exercise these features live in the session scratchpad, not the repository — turning
them into a committed seeder is part of this.

---

## Test inventory

| Suite | Count | Command |
|---|---|---|
| Backend unit | 83 | `./mvnw test` |
| Backend integration (Testcontainers + real Postgres) | 82 | `./mvnw verify` |
| Frontend unit | 167 | `pnpm test` |
| Frontend e2e (Playwright, needs the backend up) | 11 | `pnpm e2e` |

Notable coverage: `TenantIsolationIT` (cross-tenant reads), `GoalPacingIT` (the sweep's
cross-tenant write), `TimelineIT` (every date-source rule), `CalendarIT` (the roadmap
window that broke), `DocumentsIT` (what counts as a version, and that a restore appends),
`KeyResultProgressTest` and `InitiativeProgressTest` (the pacing arithmetic in both
directions), `token-store.test.ts` and `session-provider.test.tsx` (single-flight refresh
and the boot latch, including the concurrency defects that would sign a user out
everywhere).

**What the tests do not cover:** colour contrast is checked in Playwright, not in the axe
unit tests — jsdom has no canvas, so axe silently skips the contrast rule there. Treating
the unit suite as proof of contrast would be reading a green tick that was never earned.
