# SprintIQ — System Design Brief

A complete description of what this product does, so it can be designed from scratch.
Written for a designer who has never seen the codebase. Everything here reflects what
is actually built and running, not what is planned.

---

## 1. What it is

An agile delivery tool — projects, backlogs, sprints, boards — with four machine
learning models wired into the planning workflow. Jira-shaped, but where Jira gives you
an empty "Story points" field, this gives you an estimate, a range, and the reasons.

The distinctive thing is not that it has AI. It is that **every model output is
presented with its own uncertainty and its own limits**, and the interface is built to
stop a number being trusted more than it deserves. That principle is the hardest part
to design and the easiest to lose in a redesign. Section 4 is the important one.

**Scale:** multi-tenant. Organization → Workspace → Project → Sprint → Story → Task.

---

## 2. Who uses it

Five roles, org-scoped: **Owner, Admin, Manager, Member, Viewer**.

Permissions are fine-grained (37 of them) in families: `project:*`, `backlog:*`,
`sprint:*`, `task:*`, `ai:predict`, `ai:manage`, `report:*`, `member:*`, `settings:*`,
`audit:read`. The UI hides what a role cannot do rather than showing a disabled control
it can never enable.

Three practical personas:

| Persona | Lives in | Wants |
|---|---|---|
| Delivery lead / Scrum master | Sprints, Reports, AI Assistant | Is this sprint going to land? |
| Developer | My Work, Board, Backlog | What am I doing, how big is it |
| Stakeholder / PM | Roadmap, Goals, Timeline, Reports | Are we on track, what changed |

---

## 3. Information architecture

The sidebar has three groups. This grouping is deliberate: **plan** the work, **do** the
work, and everything **about** the workspace.

**PLAN** — Roadmap · Goals & OKRs · Timeline · Calendar
**WORK** — Board · Backlog & Sprints · Reports
**WORKSPACE** — Resource Allocation · What-If Simulator · AI Assistant · Automation · Team · Documents · Settings

Above the groups: Home, My Work (carries an unread count), Projects.

### Every screen

| Screen | Purpose | Key content |
|---|---|---|
| **Home / dashboard** | Where the day starts | Assigned work, sprint burndown, recent activity, alerts |
| **My Work** | One person's queue across all projects | Assigned items grouped by state, due dates |
| **Projects** | All projects with health | Cards: name, key, status pill, % complete, member count |
| **Project detail** | Backlog + story sizing | Story list, story detail panel, **AI estimate panel** |
| **Project board** | Kanban for one project | Columns from workflow states, drag to move |
| **Project sprints** | Sprint planning and review | Capacity, burndown, **velocity + both risk panels** |
| **Roadmap** | Initiatives across quarters | Swimlanes, progress vs expected pace, confidence |
| **Goals & OKRs** | Objectives with key results | Progress, pacing status, check-ins |
| **Timeline** | Gantt-style delivery view | Bars per initiative/sprint, dependencies |
| **Calendar** | Dated things | Sprint boundaries, milestones, due dates, leave |
| **Board** | Cross-project kanban | Same as project board, org-wide |
| **Backlog & Sprints** | Project picker → backlog | Ranked backlog, sprint assignment |
| **Reports** | Delivery analytics | Velocity trend, burndown, throughput, estimation coverage |
| **Resource Allocation** | Who should do what | Ranked developer suggestions per task, skills match |
| **What-If Simulator** | Try a change before making it | Adjust capacity/scope, see projected outcome |
| **AI Assistant** | All model output in one place | Velocity, both risks, cost, estimation coverage |
| **Automation** | Rules (not yet backed) | Trigger → condition → action |
| **Team** | People and capacity | Members, roles, skills, allocation % |
| **Documents** | Project docs | Rich text, versions |
| **Settings** | Org/workspace config | Members, roles, feature flags, integrations |
| **Notifications** | Activity feed | Mentions, assignments, risk alerts |

**Auth flow:** login · register · verify email · forgot/reset password · accept
invitation · developer onboarding.

---

## 4. The AI layer — the part that matters most

Four models are live. Each is a real trained model served over HTTP, not a heuristic.

### The rule that governs all four

> **A model output is never shown as a bare number.**

Every prediction carries four things, and the design must have a home for all of them:

1. **The value** — the estimate/probability
2. **A range or confidence** — how precise this is
3. **Drivers** — the specific inputs that moved it, *with their values*, each marked as
   pushing up or holding down
4. **A plain-language explanation** — including what the model could *not* see

Point 4 is the unusual one. These models say things like:

> *"None of this story's wording matches the vocabulary the model was trained on, and 6
> inputs were left at their defaults, so this is the model's baseline rather than a
> reading of this story."*

> *"The team pattern 'slow_stable' was inferred from rework and velocity swings rather
> than recorded, so it is a reading of behaviour, not a fact about the team."*

> *"It is close between High and Medium, so treat the level as a leaning rather than a
> verdict."*

These sentences are generated by the models themselves and are often longer than the
number they accompany. **The design must give this text real space** — it is not fine
print, it is the safeguard that stops a 41%-accurate estimator being treated as an
oracle. Do not shrink it to a tooltip.

---

### Model 1 — Story point estimation

**Answers:** how big is this story?
**Where:** project detail, story detail panel. Also batch ("Suggest estimates for N unsized").

**Input:** story title, description, acceptance criteria, type; project domain, tech
stack, team size, experience level, velocity, completion rate; components touched,
external APIs, comparable past work, backlog age.

**Output the UI renders:**
- `value` — points, snapped to Fibonacci **1, 2, 3, 5, 8, 13** (never 21)
- `lowerBound` / `upperBound` — e.g. "likely 5–13"
- `confidence` — 0..1, shown as a bar with a band label
- `drivers[]` — `{feature, contribution, direction: INCREASE|DECREASE}`
- `explanation` — prose
- `modelCode` / `modelVersion`, and whether it was a cache hit

**Real accuracy:** MAE 1.94 points · exactly right **41%** of the time · within one
Fibonacci step **89%** · R² 0.59. A constant "always 5" baseline scores MAE 3.11, so it
beats guessing — but 41% means this is a **conversation starter for planning poker, not
an estimator to accept unread**. The design should feel like a suggestion, not a verdict.

**User actions:** Accept · Override (type your own number) · Dismiss · Re-estimate.
Accepting writes the number to the story and records the verdict for model feedback.
Nothing changes the story's points until a human acts.

**Also needs:** a way to set points **manually with no AI involved** — Fibonacci quick
picks plus a free number field. The model must never be a gatekeeper for the team's own
number.

---

### Model 2 — Sprint velocity prediction

**Answers:** how many points will this team deliver next sprint?
**Where:** project sprints page, AI Assistant.

**Input:** history of the last 3 and 5 closed sprints, team size, sprint length,
availability, planned leave, planned points, defects, experience, project domain.

**Output:**
- `predictedVelocity` + range (± the model's own typical miss)
- `committedPoints` vs `predictedVelocity` **side by side** — this comparison is the
  actual decision, more important than the prediction alone
- `commitmentVerdict` — **OVER_COMMITTED · STRETCHED · BALANCED · UNDER_COMMITTED · UNKNOWN**
- confidence, drivers, explanation

**Distinctive:** it is a *transfer learning* model. It routes to a domain-specific
fine-tuned model when one exists, otherwise the universal model, and **says which**:

> *"No Finance history has been fine-tuned yet, so this is the universal model — it lands
> within 3.3 points on average across domains."*

**Real accuracy:** zero-shot MAE 3.31, R² 0.79. Fine-tuned 2.92. (Honest caveat: a model
trained only on one domain reaches 2.59, so transfer is behind in-domain training when
lots of data exists; its value is with very few sprints.)

---

### Model 3 — Over-budget risk

**Answers:** will this sprint's effort blow past what was planned?
**Where:** project sprints page, AI Assistant.

**Input (planning-time only):** team archetype, size, baseline velocity, complexity,
hours per point, capacity, planned hours, committed points, recent velocity and trend,
previous sprint's carry-over/rework/blocked/reopened, fatigue, scope added.

**Output:**
- `riskScore` as a **percentage** (0–100%)
- `severity` — **LOW · MEDIUM · HIGH · CRITICAL**
- `atRisk` — boolean flag driving the icon (warning vs shield)
- drivers, explanation, class probabilities across Low/Medium/High
- Acknowledge action — records that a human has seen it, so a read warning stops shouting

**Real accuracy:** AUC 0.896 · accuracy 0.828 · grouped CV AUC **0.912**. This one is
genuinely good and worth acting on.

**Design note:** severity and `atRisk` are *different questions* — severity is a band for
colour, `atRisk` is the model's binary call. A sprint can be MEDIUM and flagged. What must
never happen is a severe band with a reassuring icon.

---

### Model 4 — Requirement change risk

**Answers:** how likely is this sprint's scope to churn?
**Where:** project sprints page, AI Assistant.

**Input:** project domain and phase (discovery/growth/maturity), team seniority, whether
there's a dedicated BA, requirements quality, stakeholder count, stories committed,
**average story length, acceptance criteria per story, stories with none, count of
hedging words** ("should", "maybe", "TBD", "?"), previous sprint's change requests and
volatility, whether it was flagged high last time.

**Output:** same shape as Model 3.

**Real accuracy:** AUC 0.786 · accuracy 0.664 · **F1 0.459** · 3-class accuracy 0.499
against a 0.33 base rate. **This one is weak** — it finds signal but misses more
high-change sprints than it catches. A "Low" from this model is weak evidence, not an
all-clear, and the design should not give it the same visual authority as Model 3.

---

### Cross-model behaviour the design must accommodate

- **Predictions are cached 6 hours.** A repeated request replays a stored answer. The UI
  shows a "cached" marker next to the model version. A redesign needs somewhere for that.
- **Models can fail.** If the model service is down, the backend records a FAILED
  prediction and the UI shows the item unestimated. **It never invents a number.** Design
  the failure state.
- **Degraded predictions** — answered but too slowly to be trusted as fresh — are marked.
- **Three more model slots exist** but run on a mock today: resource allocation ranking,
  and two cost forecasts (initial + mid-sprint). Design should leave room.

---

## 5. Vocabularies the UI renders

Design a treatment for each value, not just the happy one.

| Concept | Values |
|---|---|
| Project status | PLANNING · ACTIVE · ON_HOLD · COMPLETED · ARCHIVED |
| Sprint status | PLANNED · ACTIVE · COMPLETED |
| Story type | STORY · BUG · SPIKE · CHORE |
| Priority | LOWEST · LOW · MEDIUM · HIGH · CRITICAL |
| Risk severity | LOW · MEDIUM · HIGH · CRITICAL |
| Commitment verdict | OVER_COMMITTED · STRETCHED · BALANCED · UNDER_COMMITTED · UNKNOWN |
| Driver direction | INCREASE · DECREASE |
| Estimate source | UNESTIMATED · MANUAL · AI_ACCEPTED · AI_OVERRIDDEN |
| Prediction status | SUCCESS · DEGRADED · FAILED |

`estimateSource` matters: "8, because someone accepted a model's suggestion" carries
different weight in a planning argument than "8, because the team argued about it". The
UI says which.

---

## 6. Existing design system

A redesign can replace this, but it shows the current level of detail.

**Type scale** — unusually small and tight, tuned for information density:
`2xs 10.5px · xs 11.5px · sm 12.5px · base 13.5px · md 14px · lg 14.5px · xl 17px · 2xl 20px · 3xl 23px · 4xl 27px · 5xl 34px`. Geist sans + Geist mono.

**Colour tokens** — semantic, not literal, and all dual-mode (light/dark):
- Surfaces: `canvas · surface · surface-2 · surface-3`
- Lines: `line · line-strong`
- Text: `ink · ink-2 · ink-3` (primary/secondary/tertiary)
- Accent: `accent · accent-hover · accent-soft · accent-text`
- Semantic: `green · amber · red · blue · violet · teal`, each with a `-soft` variant for backgrounds

**Motion** — custom easing, named transition properties (never `transition-all`),
`scale(0.97)` on press, reduced-motion respected. Durations are short: press 100–160ms,
popovers 125–200ms, modals 200–500ms. Nothing over 300ms for routine UI.

**Density:** this is a professional tool used all day. Compact beats airy. Tables and
lists over cards where there is real volume.

---

## 7. Design principles to preserve

1. **Uncertainty is content, not decoration.** Ranges, confidence and caveats get real
   estate. A design that shows "8 points" in 48px type and the explanation in 10px grey
   has inverted the priority.
2. **Nothing changes by itself.** No model output is written to a story, sprint or
   estimate without a human action. The suggestion sits *beside* the team's number, never
   replaces it.
3. **Say what the model could not see.** Missing inputs, unrecognised wording, inferred
   values — all surfaced.
4. **Comparison over assertion.** "Committed 20 vs Predicted 26" beats "Predicted 26".
   "Your team said 13, the model says 5" beats either alone.
5. **Every action is reversible or acknowledged.** Accept can be changed. Warnings can be
   acknowledged, and then stop shouting.
6. **Empty states explain the cause.** "No completed sprints yet, so velocity has nothing
   to read" — not "No data".

---

## 8. Screen states to design

For each data screen: **loading** (skeletons matching final layout) · **empty** (with the
reason) · **error** (with retry) · **populated** · **partial** (some models answered,
others failed).

The AI panels specifically need: **not yet run** (offer to run it) · **running** ·
**answered** · **answered but degraded** · **failed** · **cached** · **verdict recorded**
(accepted/overridden/dismissed).

---

## 9. Honest state of the build

- Fully working: projects, backlog, sprints, boards, tasks, goals, roadmap, timeline,
  calendar, documents, comments, notifications, reports, team, settings, auth.
- All four AI models live and verified end to end.
- **Automation** screen has no backend — needs an event bus that does not exist.
- **Resource allocation / simulator / cost** run on a mock model.
- A second, older dashboard surface exists at `/(dashboard)/*` from a branch merge; its
  pages call the API without auth and crash. Treat those routes as legacy — the real app
  is `/(app)/app/[orgSlug]/*`.

---

## 10. What to produce

A design for the whole system: visual language, component library, and the screens in
section 3 — with the AI panels of section 4 as the centrepiece, since they are what makes
this product different from every other agile tool.
