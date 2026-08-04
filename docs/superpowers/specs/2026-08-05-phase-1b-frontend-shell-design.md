# Phase 1b — Frontend Shell & Marketing Surface

**Date:** 2026-08-05
**Status:** Approved, ready for implementation planning
**Depends on:** Phase 1 (foundation) and Phase 2 backend (delivery core), both merged

---

## 1. Goal

Stand up the Next.js application: a marketing landing page, the authentication
pages, the signed-in app shell, and the client infrastructure (API client, token
refresh, query cache, design system) that every later feature slice is built on.

**Exit criterion:** register → log in → land on a dashboard skeleton → switch
organization → log out, against the live backend, proven by an end-to-end test.

**Out of scope.** No delivery-core feature UI. Projects, backlog, boards, sprints,
tasks, reports and the AI panels arrive in later phases against the endpoints the
Phase 2 backend already exposes. Of the `(app)` routes listed in
`docs/04-folder-structure.md`, only `dashboard` gets a page in this phase —
stubbing the rest now would produce a dozen files whose only content is a `TODO`.

---

## 2. Decisions

| # | Decision | Reasoning |
|---|---|---|
| D1 | Next.js 14, App Router, TypeScript | Fixed by `docs/02-architecture.md` and `docs/04-folder-structure.md`. Next 15 is available; staying on 14 keeps the docs honest. Revisit deliberately, not by drift. |
| D2 | Refresh token in an httpOnly cookie, access token in memory | An XSS that can read `localStorage` can steal a 30-day refresh token and use it from anywhere. A cookie JavaScript cannot read reduces that to the lifetime of one 15-minute access token. |
| D3 | Thin BFF: Next route handlers own `/auth/*` only | The cookie has to be read somewhere server-side, and only auth needs it. Proxying data through Next as well would add a hop to every board refresh and buy nothing — CORS is already configured for direct calls. |
| D4 | shadcn/ui — Radix primitives, Tailwind styling, vendored | Accessible overlays, comboboxes and focus management are hard to get right and are exactly what this shell needs. Vendored rather than depended on, so restyling is editing our own files. |
| D5 | Client-first data: TanStack Query owns all server state | The product is drag-to-rank, boards, optimistic status changes and — from Phase 4 — live WebSocket updates. All of that wants one client cache a socket message can invalidate. RSC-first would leave `revalidatePath` and Query as two caches over the same rows. |
| D6 | Dense, keyboard-first product UI | People scan hundred-row backlogs and stand at a board during standup. Restraint in the chrome also keeps saturated colour free for status, risk and AI confidence, which is where attention belongs. |
| D7 | Marketing surface is expressive, from the same tokens | A landing page has three seconds to convince a stranger; the app has eight hours not to tire someone out. Different register, shared tokens, so they never look like different products. |
| D8 | Two motion budgets: CSS in the app, GSAP on marketing | Radix exposes `data-state`, so app transitions cost no runtime. Scroll pinning and scrubbing are what ScrollTrigger exists for. GSAP is dynamically imported inside the marketing route group and never reaches the app bundle. |

### Assumptions

- **A1** `pnpm` is not installed on the dev machine; the Makefile's `frontend`
  target already assumes it. Enable it via Corepack rather than switching the
  documented package manager.
- **A2** The backend runs at `http://localhost:8080`; `docker-compose.yml` already
  passes `NEXT_PUBLIC_API_URL` and `NEXT_PUBLIC_WS_URL`.
- **A3** ~~Verify CORS~~ **Confirmed:** `application.yml` already defaults
  `app.cors.allowed-origins` to `http://localhost:3000`
  (`${CORS_ALLOWED_ORIGINS:http://localhost:3000}`), so direct browser calls to
  `:8080` work in development with no backend change.
- **A4** All BFF-wrapped endpoints exist as assumed:
  `/auth/{register,login,refresh,switch-organization,logout,logout-all,
  password/forgot,password/reset,password/change,email/verify}` plus `GET /auth/me`.

---

## 3. Architecture

```
Browser ──▶ /api/auth/*  (Next route handlers)  ──▶ :8080/api/v1/auth/*
   │          sets / reads the httpOnly refresh cookie
   └──────▶ :8080/api/v1/*   (direct, Authorization: Bearer <in-memory token>)
```

### 3.1 BFF route handlers

| Handler | Behaviour |
|---|---|
| `POST /api/auth/login` | Calls backend login. Writes `refreshToken` to an httpOnly, `Secure`, `SameSite=Lax`, `Path=/` cookie. Returns access token + `AuthenticatedUser` in the body. |
| `POST /api/auth/session` | Called on app boot and after a hard refresh. Exchanges the cookie for a fresh access token so memory-only storage survives a reload. Rotates the cookie. |
| `POST /api/auth/refresh` | Rotation on expiry. Writes the new cookie. |
| `POST /api/auth/switch-org` | Wraps `/auth/switch-organization`, which needs the refresh token the client cannot see. Writes the new cookie. |
| `POST /api/auth/logout` | Revokes server-side, then clears the cookie. |
| `POST /api/auth/register`, `/password/forgot`, `/password/reset`, `/email/verify` | Passthroughs. No cookie until login succeeds. |

Cookie name `sq_rt`. `Secure` is set outside development.

### 3.2 Routing

```
frontend/src/app/
├── (marketing)/
│   ├── layout.tsx                  transparent-on-scroll nav, footer
│   ├── page.tsx                    landing
│   └── _sections/                  hero, models, loop, explainability,
│                                   analytics, pricing, faq, cta
├── (auth)/
│   ├── layout.tsx                  centred card, product mark
│   ├── login/  register/
│   ├── forgot-password/  reset-password/
│   └── verify-email/
├── (app)/[orgSlug]/
│   ├── layout.tsx                  the shell
│   └── dashboard/page.tsx          skeleton — the 1b exit criterion
├── api/auth/*/route.ts
└── middleware.ts
```

`middleware.ts` checks only that the refresh cookie **exists** before letting an
`(app)` route render. It cannot validate the token without a backend call, but
presence is enough to prevent the logged-out flash; real validation is the first
`/api/auth/session` call.

`[orgSlug]` resolves to the `organizationId` the backend wants by looking it up in
the session's `organizations[]` — `AuthenticatedUser` already returns
`{id, name, slug, logoUrl, roleCode}` per organization, so this costs no extra
request. An unknown slug renders a not-found state offering the orgs the user does
belong to.

### 3.3 Directory layout

Follows `docs/04-folder-structure.md`:

```
frontend/src/
├── app/            routes, as above
├── components/
│   ├── ui/         vendored shadcn primitives
│   ├── layout/     Sidebar, Topbar, OrgSwitcher, CommandPalette, UserMenu
│   ├── feedback/   EmptyState, ErrorState, LoadingSkeleton, Toast
│   └── marketing/  section components and their motion
├── features/auth/  {api.ts, hooks.ts, schema.ts, components/}
├── lib/            api-client.ts, query-keys.ts, session.ts, cn.ts, motion.ts
├── stores/         ui-store.ts (Zustand)
├── types/          generated + hand-written API types
└── tests/          vitest unit tests, playwright e2e
```

---

## 4. Design system

**Tokens.** CSS custom properties in `globals.css`, consumed by Tailwind. OKLCH
colours so light and dark stay perceptually even. A 4px spacing grid. Type scale
12/13/14/16/20/24/32/48. Three radii, two shadow levels.

Semantic tokens sit on top of the palette — `--status-done`, `--risk-high`,
`--ai-confidence` — so colour that carries meaning is never written the same way
as colour that is decoration. Both themes are designed, not derived; dark mode
uses the `class` strategy.

**Primitives for this phase.** Button, Input, Select, Dialog, DropdownMenu,
Popover, Tooltip, Avatar, Badge, Skeleton, Toast, Command, Tabs, Separator,
ScrollArea. Nothing speculative — this is the set the auth pages and shell use.

**Shell composition.** Collapsible sidebar (project nav, persisted collapsed
state), topbar with breadcrumb and search trigger, organization switcher fed by
`organizations[]`, user menu, and a `⌘K` command palette that is a first-class
navigation path rather than a shortcut for power users.

---

## 5. Marketing surface

Content is grounded in what the product does, not invented copy.

| Section | Content |
|---|---|
| Hero | Headline, subhead, `Start free` / `See it work`, product shot of a board with an AI estimate panel open. Grid + gradient wash. |
| Four models | M1 estimation, M2 risk, M3 allocation, M4 cost — each shown attached to the decision it informs, per the positioning in `docs/00-product-spec.md`. |
| Accept / Override / Compare | Interactive mock of the AI panel. The most distinctive thing in the product and far better demonstrated than described. |
| The agile loop | plan → execute → track → retrospect, tied to real screens. |
| Analytics | Velocity, burndown and CFD as real charts with plausible data. |
| Pricing | Four tiers taken from the `OrganizationPlan` enum: Free, Starter, Pro, Enterprise. |
| FAQ, closing CTA, footer | |

Dark hero, larger display type, gradient and glow treatments, scroll-triggered
motion — all from the same Tailwind tokens as the app.

---

## 6. Motion

| | App shell | Marketing |
|---|---|---|
| Tool | CSS + `tailwindcss-animate` on Radix `data-state` | GSAP + ScrollTrigger, dynamically imported |
| Duration | 120–180ms feedback, 200–300ms entrance | 400–900ms reveals, scrubbed sequences |
| Purpose | Confirm an action happened; show where a thing came from | Pace the story, reward scrolling |

**Rules.**

- Transform and opacity only. Nothing that triggers layout — a board that reflows
  mid-animation feels broken even when it is fast.
- Easing is directional: `ease-out` arriving, `ease-in` leaving, spring only under
  direct manipulation.
- Motion follows causality: a dropdown grows from its trigger; a toast enters from
  the edge it occupies.
- `prefers-reduced-motion: reduce` removes movement everywhere and keeps opacity
  fades. Tested, not stubbed.
- Anything over ~200ms in the app is a defect. Marketing may be slower because the
  visitor is reading; the app may not because the user is working.

**Marketing beats.** Staggered hero entrance; the four model cards revealing on
scroll with a connecting line drawing between them; the Accept/Override/Compare
panel pinned while its three states cycle; chart series animating in on entry;
counters easing to value.

---

## 7. Data flow

**API client.** One typed `fetch` wrapper that attaches the bearer token, sets
`X-Organization-Id` for the active organization, generates `X-Correlation-Id`, and
parses responses through the backend's two envelopes — `PageResponse<T>` and
`ApiError`. Responses are Zod-validated at the boundary, so a contract change
surfaces as one loud parse error rather than `undefined` three components deep.

**Silent refresh must be single-flight.** The backend rotates refresh tokens *and*
revokes the whole family on reuse. If six parallel queries 401 simultaneously, six
concurrent refreshes would present the same rotated token six times, the backend
would correctly read that as theft, and the user would be signed out everywhere.
So: the first 401 starts one refresh; every other in-flight request parks on that
same promise and replays when it settles. On refresh failure — clear session,
redirect to `/login?next=…`.

**State ownership.** TanStack Query owns everything that came from the server; no
server data in Zustand under any circumstance. Zustand holds only what the server
has no opinion about: command palette open, sidebar collapsed, theme, active org
slug. Query keys come from one factory (`keys.projects.list(filters)`) so
invalidation is greppable and Phase 4's WebSocket handler has an obvious place to
invalidate from.

---

## 8. Error handling

| Case | Treatment |
|---|---|
| `fieldErrors[]` (422) | Mapped onto `react-hook-form` field errors. The 12-character password rule appears under the password input, not in a toast. |
| `403 ACCESS_DENIED`, `404 NOT_FOUND` | In-place empty state. A permission failure is a state, not an event. |
| `TENANT_NOT_RESOLVED` | Force a re-resolve of the active organization rather than showing a generic error. |
| Everything else | Toast carrying the message, plus the `correlationId` in small copyable type — it matches the backend's structured logs exactly. |

Route-level error boundaries, so one failed panel cannot blank the shell.

---

## 9. Testing

- **Vitest + Testing Library** — the refresh queue including the concurrent-401
  case, Zod round-trips, the org-slug resolver, field-error mapping.
- **MSW** — handlers built from the real response shapes.
- **Playwright** — one end-to-end test that *is* the exit criterion: register → log
  in → dashboard → switch org → log out, against the live backend. Plus an axe
  pass over the auth pages, marketing page and shell, and a run with reduced motion
  forced.

---

## 10. Risks

| Risk | Mitigation |
|---|---|
| Refresh storm trips the backend's reuse detection and signs users out | Single-flight refresh queue, with a unit test that fires concurrent 401s and asserts exactly one refresh call |
| ~~CORS origin missing~~ | Closed — verified already configured (A3) |
| Marketing motion hurts Lighthouse or feels heavy on low-end hardware | GSAP dynamically imported and code-split out of the app bundle; reduced-motion path tested; performance budget checked before merge |
| Scope creep from marketing into feature UI | Only `dashboard` gets an `(app)` route this phase; everything else is explicitly deferred |
