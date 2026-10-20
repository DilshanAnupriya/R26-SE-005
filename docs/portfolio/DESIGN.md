# SprintIQ Research Portfolio — Design Specification

> The single source of truth for the SprintIQ research portfolio website.
> Everything a designer or developer needs is here: concept, tokens, page-by-page layout,
> copy, 3D scenes, motion, components, accessibility, performance and build plan.
> Image slots are referenced by ID (`IMG-xx`); the prompts to generate them live in
> [`IMAGE-PROMPTS.md`](IMAGE-PROMPTS.md).

---

## 0. How to use this file

| If you are… | Read |
|---|---|
| Building the site | §4 tokens → §5 IA → §6–§8 pages → §9 3D → §10 motion → §11 components → §15 stack |
| Writing content | §2 facts → §12 voice → §16 content checklist |
| Generating images | §3 concept, §4.1 palette, then `IMAGE-PROMPTS.md` |
| Reviewing before launch | §13 accessibility → §14 performance → §17 definition of done |

Anything in **[CONFIRM]** must be checked with the person named before it goes live.
Anything in **[ADD]** is content only the team has (photos, dates, PDFs, video links).

---

## 1. Brief

**Subject.** SprintIQ: an AI-driven Agile project management framework, built by SLIIT
research group **R26-SE-005** for Sri Lanka's software industry. It's a working, multi-tenant
SaaS product with four researched AI models, plus an LLM Copilot that can call them.

**Audience, in priority order.**
1. The SLIIT evaluation panel and supervisors. They need the research problem, gap, objectives,
   methodology, results, milestones and documents, and they need to find them quickly.
2. Industry visitors: practitioners, recruiters and potential partners. They need to see in one
   minute what the product does and whether the numbers are credible.
3. Peers and future students. They need to see how it was built.

**The page's single job.** In 60 seconds a visitor should understand that *SprintIQ answers
the four questions every sprint-planning meeting argues about, and each answer comes with
its range and its reasons*. Everything after that minute is evidence they can dig into.

**Success criteria.**
- A panel member can reach any required section (Domain, Milestones, Documents,
  Presentations, About us, Contact) in at most two clicks from any page.
- Every result shown has its baseline or its range beside it (the envelope rule, §12.2).
- The home page's first meaningful paint takes under 2.5 s on a mid-range phone over 4G.
  The 3D scene loads after the content, never before it.
- The site is fully usable with reduced motion, without WebGL, and from the keyboard.

---

## 2. Project facts (content source of truth)

Put all of this in one typed content file (`content/project.ts`, §15.3) so no number is
ever typed twice.

### 2.1 Identity

| Field | Value |
|---|---|
| Product | SprintIQ |
| Research title | An AI-Driven Agile Project Management Framework for Enhancing Productivity and Project Success in Sri Lanka's Software Industry |
| Group ID | R26-SE-005 |
| Institution | Sri Lanka Institute of Information Technology (SLIIT), Faculty of Computing |
| Degree | BSc (Hons) in Information Technology, specialising in Software Engineering |
| Year | 2026 |
| Supervisor | Mrs. Karthiga Vivek |
| Co-supervisor | Ms. Kaushalya Rajapakse |
| External supervisor | Mr. Suresh Fernando |
| Logo | `frontend/public/SprintIQLOGO.png`: indigo-to-violet "S" with three aqua forward chevrons, on night black |

### 2.2 The four components

The order follows **when each model acts in a sprint**, which is also the order of the home
page (§6). Model codes are the real codes used in the product.

| Code | Question it answers | Component | Researcher | Acts at |
|---|---|---|---|---|
| M1 | How big is it, and how much can we do? | AI-Based Effort & Sprint Estimation Engine (story points + velocity) | Cooray B.D.A · IT22189530 | Day 00, planning |
| M3 | Who should take it? | Skill-aware Resource Allocation [CONFIRM title] | Athukorala H.H.B · IT22066770 | Day 00, assignment |
| M4 | What will it cost? | Sprint Cost Forecasting [CONFIRM title] | Hewaduwa H.G.V · IT22186706 | Day 00 and Day 05 (mid-sprint) |
| M2 | What could go wrong? | Sprint Risk Prediction [CONFIRM title] | Jayasinghe J.A.N.T · IT22122100 | Days 01–10, live |
| — | Ask across all four | SprintIQ Copilot (LLM with tool calling) | Group | Any time |

### 2.3 Method and result per component

All figures below come from the team's notebooks, thesis drafts and the product's model
registry (`ai_models.metadata`). Each researcher must confirm their own row before launch.

**M1 · Estimation (Cooray)**
- *Story points:* XGBoost over 150 TF-IDF text terms plus 34 structural features; two LightGBM
  quantile models (P20/P80) calibrated with conformalised quantile regression (CQR); outputs
  snapped to the Fibonacci scale 1-2-3-5-8-13; per-prediction TreeSHAP drivers.
- *Velocity:* XGBoost trained **without** the domain feature, so it predicts zero-shot for any
  new domain; few-shot weighted retraining; a domain adapter routes each prediction to a
  fine-tuned model when one exists.
- Results (held-out data):
  - Story points: MAE **1.94** points vs **3.11** for the median baseline (−37.8%).
    **89.2%** of estimates are within one Fibonacci step; exact match 41.0%.
  - Range: **70.3%** coverage against a 70% target (raw); **89.7%** after snapping,
    about 2 Fibonacci steps wide.
  - Velocity, unseen domain, zero-shot: MAE **3.31** (R² 0.79) vs **3.61** for rescaled
    recent history and **3.45** for linear regression. Fine-tuned: MAE **2.92**.
- Data note: trained on synthetic datasets (3,000 stories; 3,000 sprints across 5 domains).

**M3 · Resource allocation (Athukorala)** [CONFIRM all]
- A stacking ensemble predicts each developer's performance on a task. The ranking blends that
  prediction with explicit skill match, workload balance and a growth incentive. Weights come
  from the Analytic Hierarchy Process (consistency ratio < 0.10) refined by simulation-based
  SLSQP optimisation. Behavioural models score each developer's consistency and learning rate.
- Results (simulation):
  - Ten-sprint simulation: overall success **81.0%** vs **66.6%** for manual allocation.
  - Workload Gini **0.162** vs **0.208** for uniform weights.
  - Overload rate **4.0%** vs **8.0%** for uniform weights.

**M4 · Cost forecasting (Hewaduwa)** [CONFIRM all]
- A hybrid by evidence. At planning, the sum of task estimates × the blended rate beat the ML
  candidate on the time holdout (MAPE 23.45% vs 25.65%), so the formula ships. Mid-sprint,
  gradient boosting predicts the remaining effort and adds the actual cost already incurred.
  Drivers are exact Shapley values over nine factors (all 512 coalitions).
- Result: mid-sprint MAPE **28.63%** vs **49.85%** for the remaining-estimate baseline.

**M2 · Risk prediction (Jayasinghe)** [CONFIRM all]
- CatBoost with two heads per risk: *how bad* (Low/Medium/High) and *how likely*. One model
  each for over-budget and for requirement change. A fine-tuned DistilBERT model covers
  communication and collaboration risk. Board events adjust the scores live during the sprint,
  and an LLM advisor explains each risk and recommends steps.
- Results:
  - Over-budget: AUC **0.896**, accuracy 82.8%, F1 0.722.
  - Requirement change: AUC **0.786**, F1 0.459.

**Copilot (group)**
- An LLM with tool calling over all four models. It sees only what the user can see, and it
  cannot change anything itself: it creates proposal cards that a person confirms.

### 2.4 Platform facts (for the "how it's built" section)

- Next.js 16 · React 19 · Tailwind 4 frontend.
- Spring Boot 3.3 / Java 21 modular-monolith backend.
- PostgreSQL 16 · Redis 7 · FastAPI model service.
- Multi-tenant, with tenant isolation proven by integration tests.
- Every prediction returns the same envelope (value, bounds, drivers, explanation) and is
  stored with its exact inputs.
- Every suggestion has Accept / Override / Dismiss, and the choice is recorded.

---

## 3. Concept

### 3.1 The idea: *the sprint, rendered*

A sprint is a journey: work enters as a backlog of guesses and leaves as shipped software.
SprintIQ's logo already draws that journey. The **S** is the path, and the **three aqua
chevrons** are work leaving at speed. The portfolio makes the logo literal:

> **The S-Track.** A 3D glass ribbon shaped like the SprintIQ "S". Backlog cards ride it
> from the bottom tail to the chevrons at the top. On the way they pass through four
> **gates**, one per AI model. Each gate does something visible to the cards: sizes them,
> staffs them, costs them, checks them for risk. Scrolling the home page *is* the sprint:
> it moves the cards and the camera along the track while a sprint clock counts from
> Day 00 to Day 10.

This is the page's **signature**: the one thing it will be remembered by. Everything around
it stays quiet, precise and disciplined.

### 3.2 The aesthetic risk: *sprint dawn*

The home page starts at night: deep indigo, glowing glass, stars. As the sprint clock
advances it **dawns**. The background, the 3D lighting and the type tokens move continuously
from night to a cool daylight. By the results section (Day 10, sprint review) the page is
in full daylight.

**Why it's justified.** The research is about turning guesses into evidence. Planning starts
in uncertainty; review happens in the light. Light literally rises as uncertainty is
measured. It also solves a practical problem: the dense evidence sections (results,
documents, milestones) are far more readable on a light surface than on glowing night.

**How it stays legible.** Text never sits on a mid-tone background. The text tokens swap at
one threshold, and the swap happens while a full-bleed "horizon" band (`IMG-11`) covers the
viewport (§10.6).

### 3.3 Supporting motif: *the envelope*

Every SprintIQ model returns the same shape: a value, a range, the drivers behind it and an
explanation. The portfolio shows **every result in that shape** (§11.3): a big number, a
range bar or baseline ghost bar, the top drivers, and one sentence. It's true to the product,
it keeps every claim honest, and it makes four different research areas look like one system.

### 3.4 What this design is not

- Not a generic dark SaaS page with one neon accent. The palette comes from the logo, and the
  page changes light over its length.
- Not a numbered 01/02/03 feature list. The structure is the sprint timeline (Day 00 → Day 10),
  which is a real sequence.
- Not animation everywhere. One orchestrated 3D journey on the home page; the inner pages are
  calm and editorial, with motion only where it explains something.

---

## 4. Design tokens

### 4.1 Colour

Derived from the logo (indigo-violet S, aqua chevrons) and the product's own tokens
(`--accent: #5b5cf0`).

| Token | Name | Hex | Role |
|---|---|---|---|
| `--night` | Midnight Backlog | `#0A0A1C` | Night canvas. Indigo-tinted, never pure black |
| `--night-raised` | Night Glass | `#14152B` | Cards and panels at night (with 60–70% opacity + blur) |
| `--night-line` | Night Line | `#262842` | Hairlines and borders at night |
| `--mist` | Mist | `#9AA0B8` | Secondary text at night (≈7.5:1 on `--night`) |
| `--starlight` | Starlight | `#EEF0FA` | Primary text at night |
| `--indigo` | Sprint Indigo | `#5B5CF0` | Primary brand accent: buttons, links, focus ring |
| `--violet` | Violet Signal | `#8B5CF6` | AI glow, gradients with indigo, the S-Track core light |
| `--aqua` | Velocity Aqua | `#19E6C1` | Forward motion only: chevrons, "shipped", positive deltas. Never body text on light |
| `--aqua-deep` | Aqua Deep | `#0A7A68` | Aqua's text-safe form on daylight |
| `--paper` | Daybreak Paper | `#F3F4FA` | Daylight canvas. Cool, not cream |
| `--paper-raised` | Paper White | `#FFFFFF` | Cards in daylight |
| `--paper-line` | Paper Line | `#E2E4EE` | Hairlines in daylight |
| `--ink` | Review Ink | `#121427` | Primary text in daylight |
| `--ink-2` | Ink Muted | `#555A73` | Secondary text in daylight |
| `--risk-amber` | Risk Amber | `#FFB020` | Semantic: medium risk, warnings (M2 only) |
| `--risk-red` | Risk Red | `#FF5A5F` | Semantic: high risk (M2 only) |

**Gradient (the only one).** `--grad-s: linear-gradient(135deg, #5B5CF0 0%, #8B5CF6 55%, #19E6C1 100%)`.
It's the logo's own sweep. Use it on the S-Track, the hero headline's final word underline,
and the primary button's hover sheen. Nowhere else.

**Usage ratios per viewport.** About 80% canvas (night or paper), 15% text and lines,
4% indigo/violet, 1% aqua. Aqua is precious: it means "moving forward".

**Contrast rules.**
- Body text is ≥ 4.5:1 and large display text ≥ 3:1.
- `--indigo` on `--paper` ≈ 5:1 (OK for text). `--aqua` on `--paper` fails, so use `--aqua-deep`.
- Verify every pairing in a contrast checker before launch.

**Theme mapping.** Semantic tokens point at night or day values:

```css
:root            { --bg: var(--night); --surface: var(--night-raised); --line: var(--night-line);
                   --text: var(--starlight); --text-2: var(--mist); }
[data-light=day] { --bg: var(--paper); --surface: var(--paper-raised); --line: var(--paper-line);
                   --text: var(--ink); --text-2: var(--ink-2); }
```

Users with `prefers-color-scheme: dark` get the night mapping on every page. On the home page
the dawn still lifts the 3D scene, but the evidence sections use a night variant of their
cards. A small sun/moon toggle in the nav lets anyone pin either mode (stored in `localStorage`).

### 4.2 Typography

Three faces, each with one job.

| Role | Face | Why this face | Source |
|---|---|---|---|
| Display | **Mona Sans** (variable: `wdth` 75–125, `wght` 200–900) | Its width axis lets headlines *accelerate*, from condensed to expanded as the sprint moves. It's engineered and confident without being cold | Self-host the variable WOFF2 from GitHub's official `mona-sans` repo (SIL OFL) |
| Body | **Geist** (400, 500, 600) | It's the SprintIQ product's own UI face, so screenshots and site copy read as one system | `next/font/google` |
| Data | **Geist Mono** (400, 500), tabular numerals | Sprint clock, metrics, IDs, model codes, captions. Numbers line up and feel measured | `next/font/google` |

**Scale: golden ratio (φ ≈ 1.618) from a 17 px body.** Fibonacci is the story-point
scale, and φ is its limit, so the type scale shares the subject's maths.

| Token | Size (desktop) | Size (mobile) | Face / settings | Use |
|---|---|---|---|---|
| `--t-hero` | 116 px (`clamp(3.25rem, 7.2vw, 7.25rem)`) | 52 px | Mona Sans 800, `wdth` 125, tracking −0.035em, leading 0.92 | Home hero only |
| `--t-h1` | 72 px | 42 px | Mona Sans 750, `wdth` 112, −0.03em, 0.98 | Page titles, chapter questions |
| `--t-h2` | 44.5 px | 32 px | Mona Sans 700, `wdth` 105, −0.02em, 1.05 | Section titles |
| `--t-h3` | 27.5 px | 22 px | Mona Sans 650, `wdth` 100, −0.01em, 1.15 | Card titles |
| `--t-body-l` | 21 px | 18 px | Geist 400, leading 1.55 | Lead paragraphs |
| `--t-body` | 17 px | 16 px | Geist 400, leading 1.6, max 68ch | Body |
| `--t-caption` | 13 px | 13 px | Geist 500, leading 1.45 | Captions, metadata |
| `--t-mono` | 13 px | 12 px | Geist Mono 500, tracking +0.04em, uppercase for labels | Eyebrows, clock, chips |
| `--t-metric` | 68 px | 44 px | Geist Mono 500, tabular, −0.02em | Envelope values |

**Type rules.**
- Mona Sans appears only in headings. Never set body copy or buttons in it.
- **Width as motion (signature type move).** Chapter questions enter at `wdth 75` / `wght 500`
  and settle at `wdth 112` / `wght 750` over 900 ms as they scroll in. Use it only on the hero
  and the five chapter questions. With reduced motion they render settled.
- Sentence case everywhere. Uppercase only for mono labels, which are short.
- Numbers in running text use Geist with `font-variant-numeric: tabular-nums` when they sit in
  tables or lists.
- One hero emphasis: the final word "Risk-checked." gets the `--grad-s` underline (3 px,
  offset 0.12em), drawn left to right on load.

### 4.3 Spacing: the story-point scale

Spacing tokens **are story points**: Fibonacci numbers in pixels. Naming them by value makes
them self-documenting (`gap: var(--sp-21)`).

```
--sp-2: 2px   --sp-3: 3px   --sp-5: 5px   --sp-8: 8px    --sp-13: 13px
--sp-21: 21px --sp-34: 34px --sp-55: 55px --sp-89: 89px  --sp-144: 144px  --sp-233: 233px
```

| Context | Desktop | Mobile |
|---|---|---|
| Section padding (block) | `--sp-144` (`--sp-233` for chapter openers) | `--sp-89` |
| Grid gutter | `--sp-34` | `--sp-21` |
| Card padding | `--sp-34` | `--sp-21` |
| Stack gap, heading → body | `--sp-21` | `--sp-13` |
| Inline gap, icon → label | `--sp-8` | `--sp-8` |

### 4.4 Shape, depth, glass

| Token | Value | Use |
|---|---|---|
| `--r-3` | 3 px | Chips' inner marks, range-bar ends |
| `--r-8` | 8 px | Buttons, inputs |
| `--r-13` | 13 px | Cards, envelopes |
| `--r-21` | 21 px | Media frames, device mockups |
| `--r-pill` | 999 px | Chips, the sprint clock |

**Glass (night only).**
```css
background: color-mix(in oklch, var(--night-raised) 68%, transparent);
backdrop-filter: blur(18px) saturate(140%);
border: 1px solid color-mix(in oklch, var(--starlight) 10%, transparent);
box-shadow: inset 0 1px 0 color-mix(in oklch, white 8%, transparent),
            0 21px 55px -21px rgb(0 0 0 / 0.6);
```

**Daylight cards.** White, 1 px `--paper-line` border, shadow
`0 1px 2px rgb(18 20 39 / .04), 0 13px 34px -21px rgb(18 20 39 / .18)`. No blur.

**Grain.** A 3–4% film-grain overlay (`IMG-19`, or a procedural noise shader) on night
sections only. It removes gradient banding and adds tactility.

### 4.5 Layout grid and breakpoints

- 12 columns, max content width 1,440 px, side margins `--sp-55` desktop and 16 px mobile.
  Never allow horizontal scroll.
- Breakpoints: `sm` 640, `md` 768, `lg` 1024, `xl` 1280, `2xl` 1536.
- Reading width is capped at 68ch. Long-form pages use columns 2–8 for text and 9–12 for
  sticky asides.

### 4.6 Iconography

Lucide icons at 1.5 px stroke, 20 px default. Model codes (M1–M4) are typographic marks in
Geist Mono inside a `--r-pill` chip, not icons. No emoji in the UI.

### 4.7 Z-index scale

`canvas: 0 · content: 10 · sticky-rail: 30 · nav: 40 · overlay: 50 · toast: 60`.

---

## 5. Information architecture

The structure matches the standard SLIIT research-project website sections (Domain,
Milestones, Documents, Presentations, About us, Contact). [CONFIRM against this year's
module checklist.]

```
/                       Home: the sprint, rendered (cinematic story, §6)
/research               Domain: literature survey, gap, problem, objectives, methodology, technologies (§8.1)
/components/estimation  M1 deep dive (§7)
/components/allocation  M3 deep dive
/components/cost        M4 deep dive
/components/risk        M2 deep dive
/copilot                Copilot deep dive (shorter variant of §7)
/milestones             Timeline of assessments and deliverables (§8.2)
/documents              Downloadable reports and papers (§8.3)
/presentations          Slide decks and demo videos (§8.4)
/team                   About us: members and supervisors (§8.5)
/contact                Contact (§8.6)
```

**Primary nav (left to right):** logo · Research · Components ▾ · Milestones · Documents ·
Presentations · Team · [Contact] (button).
"Components ▾" opens a four-item menu, in sprint order, with each model's question as a subtitle.

**Mobile nav:** logo + "Menu" button opening a full-height sheet. The same order, with
components listed inline, not in a submenu.

**Footer:** group ID, institution, supervisors, quick links to every section, GitHub link,
"Illustrations on this site were generated with AI tools" disclosure, © 2026.

---

## 6. Home page

One continuous scroll. A fixed WebGL canvas (the S-Track, §9.2) sits behind the first two
thirds of the page; content panels scroll over it.

### 6.0 Page skeleton and the sprint clock

```
┌───────────────────────────────────────────────────────────────┐
│ NAV (glass, compresses after 80px scroll)                     │
├────┬──────────────────────────────────────────────────────────┤
│ ⏱  │                                                          │
│ D  │   HERO                                       (S-Track)    │
│ a  │                                                          │
│ y  │   PROBLEM · four guesses                                 │
│    │   PLATFORM · one board, four models underneath           │
│ 0  │   M1 · How big is it?             [gate 1]  Day 00       │
│ 0  │   M3 · Who should take it?        [gate 2]  Day 00       │
│ │  │   M4 · What will it cost?         [gate 3]  Day 05       │
│ │  │   M2 · What could go wrong?       [gate 4]  Day 07       │
│ │  │   COPILOT · ask across all four             Day 08       │
│ 1  │   ════ HORIZON (dawn completes) ════                     │
│ 0  │   RESULTS · numbers with their ranges        Day 10      │
│    │   HOW IT'S BUILT · exploded stack                        │
│    │   RESEARCH · gap → method                                │
│    │   MILESTONES preview · TEAM · DOCUMENTS CTA               │
│    │   FOOTER                                     Shipped ››› │
└────┴──────────────────────────────────────────────────────────┘
```

**Sprint clock (left rail, desktop ≥ lg).** A fixed vertical pill, 34 px wide, in Geist Mono.
- Top: current phase label, rotated 90° (`PLANNING`, `MID-SPRINT`, `IN FLIGHT`, `REVIEW`,
  `SHIPPED`).
- Middle: a 1 px progress line that fills with `--grad-s`.
- Bottom: `DAY 00`–`DAY 10`.
- Each gate is a tick on the line. Clicking a tick scrolls to that chapter. It's the home
  page's own table of contents, and it's keyboard-focusable with `aria-label="Go to M1:
  estimation, Day 00"`.

On mobile the clock becomes a 2 px progress bar under the nav, with the day label at the right.

**Clock mapping (true to when each model acts).**

| Scroll section | Clock reads | Phase |
|---|---|---|
| Hero → Platform | DAY 00 | PLANNING |
| M1, M3 | DAY 00 | PLANNING |
| M4 | DAY 05 | MID-SPRINT |
| M2 | DAY 07 | IN FLIGHT |
| Copilot | DAY 08 | IN FLIGHT |
| Horizon → Results | DAY 10 | REVIEW |
| Footer | — | SHIPPED ››› (aqua) |

The day numbers tick like an odometer (each digit rolls; 300 ms, `--ease-out`).

### 6.1 Hero

```
┌──────────────────────────────────────────────────────────────┐
│ R26-SE-005 · SLIIT FACULTY OF COMPUTING · 2026   (mono)       │
│                                                               │
│ Sized. Staffed.                           ╭─╮  ›››            │
│ Costed.                                  ╭╯ ╰──╮  (S-Track,   │
│ Risk-checked.                            ╰──╮  │   cards       │
│ ‾‾‾‾‾‾‾‾‾‾‾‾ (gradient underline)           ╰──╯   riding)     │
│                                                               │
│ SprintIQ is an AI-driven Agile project management framework   │
│ for Sri Lanka's software industry. Four research models       │
│ answer the four questions every sprint-planning meeting       │
│ argues about, and each one shows its working.                 │
│                                                               │
│ [ Follow a sprint ↓ ]   Read the research →                   │
│                                                               │
│ DAY 00 · PLANNING                       scroll to start ↓     │
└──────────────────────────────────────────────────────────────┘
```

- **Layout.** Text in columns 1–6 (desktop) over the left third of the canvas. The S-Track is
  framed in the right two thirds. On mobile the S-Track sits above the text at 55 svh, and the
  text follows.
- **Copy.**
  - Eyebrow: `R26-SE-005 · SLIIT FACULTY OF COMPUTING · 2026`
  - H1: **Sized. Staffed. Costed. Risk-checked.** (one line per word on desktop, two lines
    on mobile)
  - Lead: *SprintIQ is an AI-driven Agile project management framework for Sri Lanka's
    software industry. Four research models answer the four questions every sprint-planning
    meeting argues about, and each one shows its working.*
  - Primary button: **Follow a sprint** (smooth-scrolls to Problem).
  - Text link: **Read the research →** (`/research`).
- **Poster / fallback:** `IMG-01` (also the Open Graph image).
- **Load sequence:** §10.4.

### 6.2 Problem: "Sprint planning runs on four guesses"

The night is still dark. The camera drifts toward the S-Track's tail, where unsized cards
wait in a loose, foggy cluster.

```
Sprint planning runs on four guesses.

How big is it?          a planning-poker vote
Who takes it?           whoever looks free
What will it cost?      last quarter's spreadsheet
What could go wrong?    found out on Day 9
```

- A two-column list: left the question (`--t-h3`, Mona Sans), right the usual answer
  (`--t-body-l`, `--text-2`, Geist italic). Each row reveals on scroll (stagger 80 ms).
- Under it, one line: *"SprintIQ replaces each guess with an estimate that says how sure it is."*
- Optional backdrop at 30% opacity: `IMG-02` (fog of cards).
- Cite the literature in a small footnote link to `/research#gap` (no unsourced statistics on
  the home page).

### 6.3 Platform: "One board. Four models underneath."

A short interlude that shows the product before the deep dives.

```
┌──────────────────────────────────────────────────────────────┐
│ ONE BOARD. FOUR MODELS UNDERNEATH.                            │
│                                                               │
│   ┌ To do ┐ ┌ In progress ┐ ┌ Review ┐ ┌ Done ┐   (isometric  │
│   │ ▭ M1  │ │ ▭ M3        │ │ ▭ M2   │ │ ▭    │    3D board)  │
│   │ ▭     │ │ ▭           │ │        │ │ ▭ ›› │               │
│   └───────┘ └─────────────┘ └────────┘ └──────┘               │
│                                                               │
│ SprintIQ is a complete multi-tenant Agile tool: backlog,      │
│ boards, sprints, reports. Each AI model attaches to the       │
│ decision it informs, and every suggestion waits for a person  │
│ to accept it.                                                 │
└──────────────────────────────────────────────────────────────┘
```

- **3D:** the isometric Kanban scene (§9.3), in its own small canvas. Cards hop between lanes
  as the section scrolls (scrubbed). When a card enters a lane, a model chip (M1/M3/M4/M2)
  lights on it and a tiny envelope pops above it (value + range) for 1.2 s.
- **Fallback:** `IMG-03`.
- Below the board, three small **product facts** in mono (no big-number cliché):
  `Accept · Override · Dismiss on every suggestion`, `Every prediction stored with its inputs`,
  `Tenant data isolated, tested`.

### 6.4 The four chapters (M1, M3, M4, M2)

The heart of the page. Each chapter **pins for about 180 vh**. The camera flies along the
S-Track to that chapter's gate (§9.2), and the content panel crossfades in.

**Chapter template (desktop):**

```
┌──────────────────────────────────────────────────────────────┐
│ M1 · DAY 00 · PLANNING                         (mono eyebrow) │
│                                                               │
│ How big is it,               │                                │
│ and how much can we do?      │      [ GATE 1 in 3D:           │
│ (width animates 75→112)      │        Fibonacci ring sizing   │
│                              │        a card ]                │
│ Story points and velocity,   │                                │
│ predicted with honest ranges.│                                │
│                              │                                │
│ ┌ envelope ┐ ┌ envelope ┐    │                                │
│ │ 1.94 MAE │ │ 70.3%    │    │                                │
│ │ ▬▬▬ ░░░░ │ │ coverage │    │                                │
│ └──────────┘ └──────────┘    │                                │
│                              │                                │
│ (avatar) Cooray B.D.A        │                                │
│ IT22189530 · Estimation      │                                │
│ Explore M1 →                 │                                │
└──────────────────────────────────────────────────────────────┘
  cols 1–5: content panel (glass)        cols 6–12: 3D gate
```

On mobile the gate becomes a 16:10 poster image above the panel (no pinning, no camera flight).

**Per-chapter content.**

| | M1 Estimation | M3 Allocation | M4 Cost | M2 Risk |
|---|---|---|---|---|
| Eyebrow | `M1 · DAY 00 · PLANNING` | `M3 · DAY 00 · ASSIGNMENT` | `M4 · DAY 05 · MID-SPRINT` | `M2 · DAY 07 · IN FLIGHT` |
| Question (H1) | How big is it, and how much can we do? | Who should take it? | What will it cost? | What could go wrong? |
| One-line answer | Story points and next-sprint velocity, each with a range that keeps its promise. | A ranked shortlist that weighs skill, workload and growth, not just who's free. | A planning forecast, then a mid-sprint reforecast that knows what's already been spent. | Three risks scored live as the board moves, with the reasons and the next step. |
| Envelope A | Story points: **1.94** MAE · baseline 3.11 | Sprint success: **81.0%** · manual 66.6% | Mid-sprint error: **28.63%** MAPE · baseline 49.85% | Over-budget: **0.896** AUC · F1 0.722 |
| Envelope B | Range coverage: **70.3%** · target 70% | Workload Gini: **0.162** · uniform 0.208 | Planning error: **23.45%** MAPE · ML 25.65% (formula kept) | Requirement change: **0.786** AUC |
| Envelope C | Velocity, new domain: **3.31** MAE · history 3.61 | Overload: **4.0%** · uniform 8.0% | Drivers: exact Shapley, 9 factors | Live adjustments from board events |
| 3D gate | Sizing gate (§9.2.4) | Loom (§9.2.5) | Meter (§9.2.6) | Weather (§9.2.7) |
| Poster | `IMG-05` (+ `IMG-06` for velocity) | `IMG-08` | `IMG-09` | `IMG-07` |
| Link | Explore M1 → `/components/estimation` | Explore M3 → | Explore M4 → | Explore M2 → |

Every envelope shows its baseline as a ghost bar (§11.3). Under each chapter's envelopes put a
one-line **data note** in `--t-caption`, e.g. *"Held-out test data; models trained on
synthetic datasets."* Honesty is part of the design.

### 6.5 Copilot: "Ask across all four."

```
┌──────────────────────────────────────────────────────────────┐
│ COPILOT · DAY 08                                              │
│ Ask across all four.                                          │
│                                                               │
│  ┌ chat (glass) ─────────────────────┐   (3D: a light thread  │
│  │ › Is sprint 14 over-committed?     │    from the Copilot    │
│  │   ⚙ forecast_velocity  ✓           │    orb to each gate,   │
│  │   ⚙ get_sprint_risk    ✓           │    lighting in turn)   │
│  │ Committed 34 pts vs 23 predicted.  │                        │
│  │ ┌ Proposal ───────────────────┐   │                        │
│  │ │ Move PROJ-142 to sprint 15   │   │                        │
│  │ │ [ Confirm ]  Dismiss         │   │                        │
│  │ └──────────────────────────────┘   │                        │
│  └────────────────────────────────────┘                        │
│ It sees only what you can see. It changes nothing until you   │
│ press Confirm.                                                 │
└──────────────────────────────────────────────────────────────┘
```

- The chat **types itself** as the section scrolls into view (scrubbed: the user controls the
  pace). Tool-call rows appear with a spinner → check (200 ms each). The proposal card slides
  up last.
- The example must use real tool names from the product (`forecast_velocity`,
  `get_sprint_risk`, `propose_move_story_to_sprint`).
- **3D:** the Copilot "conductor" orb above the S-Track sends a light thread to each gate it
  calls (§9.2.8). **Fallback:** `IMG-10`.

### 6.6 Horizon (the dawn completes)

A full-bleed band, 100 vh tall, showing `IMG-11` (the S-Track at sunrise). While it covers the
viewport, `data-light` flips from night to day (§10.6). The only copy is centred mono text:
`DAY 10 · SPRINT REVIEW`. The band scrolls away to reveal daylight.

### 6.7 Results: "Numbers, with their ranges."

Daylight. Editorial, calm, dense, and the most important section for the panel.

```
┌──────────────────────────────────────────────────────────────┐
│ Numbers, with their ranges.                                   │
│ Every figure below is from held-out data and sits beside the  │
│ baseline it has to beat.                                      │
│                                                               │
│ [M1 ▾] [M3] [M4] [M2]   ← segmented control (tabs)            │
│                                                               │
│ ┌ envelope ─┐ ┌ envelope ─┐ ┌ envelope ─┐                     │
│ ┌ envelope ─┐ ┌ envelope ─┐ ┌ chart: calibration curve ──┐    │
│                                                               │
│ Method notes ▸ (disclosure: data, splits, caveats)            │
└──────────────────────────────────────────────────────────────┘
```

- Tabs by component (default M1 if you are presenting M1, or "All" for the group site).
- One chart per component, drawn as SVG and animated in once:
  - M1: CQR coverage vs target (5 points, with the y = x line).
  - M3: allocation strategies (fitness bars).
  - M4: MAPE vs baseline bars.
  - M2: ROC curve.
- Charts follow the product's palette; axis text in Geist Mono 12 px; no 3D charts.
- "Method notes" is a native `<details>` with the data source, splits and the honest caveats.

### 6.8 How it's built: the exploded stack

A 3D (or CSS 3D) exploded view of the architecture. Four glass slabs separate vertically as
you scroll, and data packets travel between them.

| Slab (top → bottom) | Label | Detail on hover/focus |
|---|---|---|
| 1 | Next.js 16 · React 19 | Story estimate panel, velocity panel, risk board, Copilot |
| 2 | Spring Boot 3.3 · Java 21 | Modular monolith; one inference path: flag → cache → call → record |
| 3 | FastAPI model service | M1–M4 bundles; `/predict/*` endpoints; contract checked at start-up |
| 4 | PostgreSQL 16 · Redis 7 | Prediction ledger, feedback, model registry, tenancy |

The packets are small aqua spheres travelling 2 → 3 → 2 → 4 (request, prediction, record).
**Fallback:** `IMG-04`.

### 6.9 Research snapshot

Three cards in a row linking into `/research`: **The gap** · **Our method** (Design Science
Research) · **What's new**. Each card is one or two sentences plus a link. Header image `IMG-12`
at low contrast.

### 6.10 Milestones preview, team, documents CTA

- **Milestones preview:** the next upcoming milestone large, the last two completed small, and
  "All milestones →".
- **Team strip:** four member portraits (§8.5 treatment) with name, ID and component, plus a
  supervisors line. "Meet the team →".
- **Documents CTA:** *"Read the full work."* Buttons: **Documents** · **Presentations**.

### 6.11 Footer

Night returns at the very bottom: a dusk-to-night strip so the page loops like a sprint. The
three aqua chevrons fly out to the right when the footer enters view (once). Final mono line:
`SHIPPED ››› R26-SE-005`.

---

## 7. Component deep-dive page template (`/components/*`)

Calm, editorial and daylight by default. No full-screen WebGL; one interactive pipeline
diagram instead.

```
┌──────────────────────────────────────────────────────────────┐
│ ← All components            M1 · ESTIMATION                   │
│                                                               │
│ How big is it, and how much can we do?           [poster      │
│ AI-Based Effort & Sprint Estimation Engine        IMG-05,     │
│ Cooray B.D.A · IT22189530                         21px radius]│
│                                                               │
│ ON THIS PAGE  Problem · Gap · Objectives · Method ·           │
│               Pipeline · Results · Product · Limits · Papers  │
├───────────────────────────────┬──────────────────────────────┤
│ Long-form content (cols 2–8)  │ Sticky aside (cols 9–12):    │
│                               │  key envelopes, researcher   │
│                               │  card, downloads             │
└───────────────────────────────┴──────────────────────────────┘
```

**Sections (each component fills these from its own thesis):**
1. **Problem:** two short paragraphs.
2. **Research gap:** a bulleted list, each item linked to its citation.
3. **Objectives:** the main objective plus sub-objectives (from the TAF).
4. **Method:** prose plus the pipeline.
5. **Pipeline (interactive):** a horizontal rail of stages. A token travels along it on
   scroll; clicking a stage expands its detail. Built with SVG + CSS 3D tilt (no WebGL).
   Stages per component:
   - **M1 story points:** Story text + structure → TF-IDF (150) + 34 features → XGBoost →
     LightGBM P20/P80 → CQR (δ = 0.594) → Fibonacci snap → envelope.
   - **M1 velocity:** Sprint history + capacity → 16 features → domain adapter (tuned or
     universal) → band → commitment verdict.
   - **M3:** Task profile + developer profiles → stacking ensemble → skill match · workload ·
     growth → AHP + SLSQP weights → ranked shortlist.
   - **M4:** Plan → formula baseline | mid-sprint: GBM remaining effort + actual cost → exact
     Shapley drivers → forecast with range.
   - **M2:** Sprint metrics + board events → CatBoost ×2 (how bad / how likely) · DistilBERT →
     live adjustments → LLM advisor → steps.
6. **Results:** envelopes, a chart, and a results table.
7. **In the product:** 2–3 real screenshots in device frames (`SHOT-xx`, see `IMAGE-PROMPTS.md` §6).
8. **Limitations:** an honest bulleted list (e.g., synthetic training data).
9. **Papers and downloads:** the researcher's proposal, progress reports, final thesis and paper.

The `/copilot` page uses the same template with: problem → design (tool loop, proposals,
permissions) → example conversation → safety.

---

## 8. Other pages

### 8.1 `/research` (Domain)

Long-form editorial page, daylight. The sticky table of contents on the left is the six
required parts, in order:

1. **Literature survey:** a short narrative plus a comparison table (studies × capabilities;
   reuse the comparison tables from each thesis, merged).
2. **Research gap:** grouped by component, with the shared gap first: *tools record and report;
   they don't predict, explain or say how sure they are*.
3. **Research problem:** the main problem statement, plus one per component.
4. **Research objectives:** the main objective (from the TAF), then four sub-objectives, one per
   component, each linked to its deep-dive page.
5. **Methodology:** Design Science Research. Show the six activities as a loop diagram
   (`IMG-13` as the backdrop, labels in HTML, never baked into the image).
6. **Technologies used:** a logo-free grid of chips grouped as ML (XGBoost, LightGBM, CatBoost,
   DistilBERT, scikit-learn), Services (FastAPI, Spring Boot), Web (Next.js, React, Tailwind),
   Data (PostgreSQL, Redis), Tooling (Docker, GitHub, Jupyter).

Citations use IEEE numbering [1], linking to a references list at the bottom of the page.

### 8.2 `/milestones`

A vertical timeline. Its spine is the S-Track's line motif drawn in 2D.

| Field per milestone | Example |
|---|---|
| Date | [ADD] |
| Title | Topic Assessment (TAF) · Project Proposal · Progress Presentation 1 · Progress Presentation 2 · Final Presentation & Viva · Website · Research Paper · Final Report [CONFIRM list] |
| Status | Done (aqua check) · Upcoming (outline) · Today (indigo pulse, 2 s loop) |
| Marks allocated | [ADD, if the module shows them] |
| Deliverables | Links to documents/slides |

Motion: the spine draws as you scroll (scrubbed). Each node scales from 0.9 → 1 with opacity as
it enters. **Header image:** `IMG-14`.

### 8.3 `/documents`

A filterable grid: **All · Group · Cooray · Jayasinghe · Athukorala · Hewaduwa**, and by type:
**Proposal · Progress · Final · Checklist · Paper**.

Document card: type label (mono) · title · owner · date · format + size · **Download** (with
`download` attribute) · **Preview** (opens the PDF in a new tab). Empty filter state: *"No
documents of this type yet. They're added after each milestone."* **Header image:** `IMG-15`.

### 8.4 `/presentations`

A card per presentation: date, title, a slides cover image (a generated 16:9 cover is fine,
see `IMAGE-PROMPTS.md` `IMG-20`), **View slides**, **Download**. The demo video is embedded
with `loading="lazy"` and a custom poster (`IMG-01` crop). [ADD video URL]

### 8.5 `/team` (About us)

- **Members:** four cards in sprint order (M1, M3, M4, M2). Each card has a portrait, name,
  student ID, component and its question, a 2-line bio [ADD], and email · LinkedIn · GitHub
  [ADD].
- **Portrait treatment:** real photos only, never AI-generated faces of real people. Apply one
  consistent treatment to all four: duotone (`--ink` → `--paper`) by default, full colour on
  hover/focus (300 ms crossfade), same crop (shoulders up, eyes at 40% height), same backdrop.
  Details in `IMAGE-PROMPTS.md` §5.
- **Supervisors:** three smaller cards (name, role, department). Photos only with their
  consent; otherwise a monogram tile.
- **Group photo:** full width, same treatment. [ADD]

### 8.6 `/contact`

A two-column page:
- **Left:** group email [ADD], individual emails, GitHub repo, SLIIT address.
- **Right:** a simple form (name, email, message) posting to a form service. The success state
  reads *"Message sent. We'll reply from our SLIIT email."* Errors name the field and what's
  wrong.

**Image:** `IMG-17` (chevrons launching).

### 8.7 `/404`

`IMG-18` (a card that slid off the track). Copy: *"This card isn't on the board."*
Button: **Back to the sprint**.

---

## 9. 3D system

### 9.1 Stack and principles

- `three` + `@react-three/fiber` + `@react-three/drei` + `@react-three/postprocessing`.
  Load them dynamically (`next/dynamic`, `ssr: false`) only after the hero text has painted.
- **Procedural first.** Every object here can be built from primitives (tubes, extrusions,
  instanced boxes and spheres), so there are no heavy model files. Optional GLB only for the
  chevrons (extruded from the logo outline in Blender, < 60 KB with Draco).
- **One canvas** on the home page, fixed behind content. Smaller isolated canvases (Kanban,
  exploded stack) mount only when in view (`IntersectionObserver`) and unmount when out.
- No text inside WebGL. Labels are HTML overlays (drei `<Html>` with `transform` off), so
  they stay crisp, translatable and accessible.

### 9.2 The S-Track scene (home)

#### 9.2.1 Path

The path is a `CatmullRomCurve3` traced from the logo's S. It runs from the bottom-left tail,
clockwise round the bottom lobe, across the spine, round the top lobe and out to the
top-right chevrons. It's given gentle depth so it reads as 3D. Starting control points
(scene units; refine by tracing the logo in Blender or Figma):

```
(-3.4,-2.8, 1.0) (-1.2,-3.2, 0.6) ( 1.6,-2.6, 0.2) ( 2.5,-1.1,-0.2)
( 1.3, 0.0,-0.5) (-1.3, 0.3,-0.5) (-2.5, 1.4,-0.2) (-1.5, 2.7, 0.2)
( 1.0, 3.1, 0.6) ( 2.6, 3.5, 1.0) ( 3.8, 4.3, 1.4)   ← chevron exit
```

#### 9.2.2 Track geometry and material

- **Ribbon:** a flat extruded strip (width 0.42, thickness 0.04) swept along the curve with
  `ExtrudeGeometry({ extrudePath })` or a custom ribbon. 600 segments.
- **Material:** `MeshPhysicalMaterial` with `transmission 0.55`, `roughness 0.18`,
  `thickness 0.6`, `ior 1.4`, `iridescence 0.25`, colour `#8B5CF6`, `attenuationColor #5B5CF0`.
- **Core light:** a thin line (`Line2`, width 1.5 px) runs down the ribbon's centre with a
  travelling gradient (indigo → violet → aqua) via a shader offset, at 0.08 units/s ambient.
- **Lighting:** two drei `<Lightformer>` rectangles (indigo, left; violet, top) inside
  `<Environment resolution={256}>`, plus one aqua rim light near the chevrons. Optionally use
  `IMG-21` as the environment map.
- **Chevrons:** three extruded chevrons at the exit, `MeshStandardMaterial` with emissive
  `#19E6C1` at intensity 2.2, so they bloom.

#### 9.2.3 Cards (the backlog)

- `InstancedMesh` of rounded boxes (1.0 × 0.62 × 0.04), 48 on desktop, 24 on mobile tier.
- Frosted glass (`transmission 0.3`, `roughness 0.45`). A thin top edge in the card's state
  colour: unsized `--mist`, sized `--indigo`, staffed `--violet`, shipped `--aqua`.
- Cards ride the curve with a parameter `u_i = (u_0 + speed·t + i/N) mod 1`. On scroll, `speed`
  eases up to 2.5× (scroll velocity) and back to ambient 0.012.
- Gates change card state (edge colour, a number decal via a shared texture atlas of
  1, 2, 3, 5, 8, 13, `?`).

#### 9.2.4 Gate 1: Sizing gate (M1) at u = 0.18

- Six glass tiles arranged on an arc around the track, sized by the golden ratio (1 → 13 grows
  left to right).
- A vertical scan beam (an additive plane with a gradient alpha) sweeps each passing card.
  The card's `?` decal flips to a number.
- **The bracket:** two thin violet brackets appear either side of the card and settle to the
  range width (e.g., [5–8]). Their width reflects real uncertainty, mirroring the product's
  range.
- A second, smaller **velocity rail** runs parallel: a stream of particles whose density is the
  predicted velocity, with a faint ghost stream for "committed". If committed > predicted, the
  overflow particles turn `--risk-amber`. This is the commitment verdict made visible.

#### 9.2.5 Gate 2: Loom (M3) at u = 0.36

- Five developer orbs (spheres, r = 0.18) float in an arc beside the track, each with a soft
  halo whose radius is current workload.
- As a card passes, five thin threads reach toward the orbs. Their opacity is the ranking
  score. The top-ranked thread brightens to `--violet` and retracts, pulling the card's edge to
  violet; the others fade.
- Workload halos rebalance (the busiest orb's halo shrinks as work goes elsewhere). That's
  fairness, shown rather than told.

#### 9.2.6 Gate 3: Meter (M4) at u = 0.52 (mid-track = Day 05)

- A ring of 55 segmented ticks around the track, like a dial.
- **Initial forecast:** a ghost arc (outline, `--mist`).
- **Actual cost so far:** solid ticks filling in `--indigo`.
- **Mid-sprint reforecast:** an arc in `--violet` that snaps into place, slightly different
  from the ghost.
- The gap between ghost and reforecast is the story: *the forecast learned from what was spent*.

#### 9.2.7 Gate 4: Weather (M2) at u = 0.70

- A volume of soft particles (instanced points, 1,500) around the top lobe.
- The particle colour field reflects risk: clear (white, sparse) → amber haze (`--risk-amber`
  at 0.35 opacity) → red pulse (`--risk-red`, 1.6 s breathing).
- As the clock advances from DAY 01 to DAY 07, small "event" particles (blockers, scope
  changes) drift in and settle on the track, and the haze thickens or clears. That's live
  adjustment.
- One card glows amber; an HTML tag above it reads `Over-budget · Medium · 68%` (example values
  only, styled as an illustration).

#### 9.2.8 Copilot conductor at u = 0.86

A small luminous orb above the S's top lobe. When the Copilot chapter plays, it sends a thread
of light to M1's rail, then M2's haze, then retracts. These are the tool calls. Timing is
synced with the chat rows typing in.

#### 9.2.9 Camera path

The camera follows a second, smoother curve offset from the track (`CatmullRomCurve3`,
tension 0.5), looking ahead at `u + 0.04`. Scroll progress `p` (0 → 1 across hero → Copilot)
maps to `u`, with stops:

| Section | Camera u | Framing |
|---|---|---|
| Hero | 0.00 | Wide: whole S visible on the right, three-quarter view, FOV 35 |
| Problem | 0.06 | Low, near the tail; cards in fog |
| Platform | — | Canvas dims to 30% (the board scene has focus) |
| M1 | 0.18 | Medium, side-on to the sizing gate, FOV 30 |
| M3 | 0.36 | Over-the-shoulder of the orbs |
| M4 | 0.52 | Looking through the dial ring |
| M2 | 0.70 | Inside the haze, looking up the top lobe |
| Copilot | 0.86 | Pulled back and up; conductor and gates in view |
| Horizon | 1.00 | Past the chevrons, looking back at the S against sunrise |

Use `maath/easing.damp3` on position and look-at (smoothTime 0.35 s) so the camera never snaps,
even if the user flings the scroll.

#### 9.2.10 Atmosphere and the dawn

- Stars: 3,000 instanced points (1,200 on mobile) with a slow parallax.
- Exponential fog: colour lerps from `#0A0A1C` (night) to `#DDE1F2` (day) with `--dawn`.
- Environment intensity rises from 0.6 to 1.4. Stars fade out after `--dawn` 0.5. Bloom
  intensity falls from 0.8 to 0.3.
- Post-processing: `Bloom` (luminanceThreshold 0.72, mipmapBlur), `Noise` (opacity 0.025,
  night only), `Vignette` (darkness 0.45). Skip `SMAA` on mobile.

### 9.3 Isometric Kanban scene (Platform section)

- Orthographic camera at a true isometric angle (rotation x −35.264°, y 45°).
- Four lanes: shallow glass trays labelled in HTML (To do · In progress · Review · Done).
- Eight cards hop lanes on scroll (scrubbed). Each hop is an arc (height 0.6) with a 3%
  squash on landing.
- On entering a lane, the card's model chip lights and a mini envelope pops above it for
  1.2 s (`scale 0.95 → 1`, opacity, 200 ms).
- Done-lane cards emit three tiny aqua chevrons once, as a nod to the logo.

### 9.4 Exploded stack (How it's built)

- Four slabs (rounded boxes 4 × 0.18 × 2.6). Gap scrubs from 0.05 to 0.9 as the section
  scrolls. Slab materials are glass at night and frosted white in day.
- Packets: aqua spheres on vertical bezier lines, 1.1 s per hop, never more than 6 at once.
- **CSS 3D alternative** (no WebGL): four `div` slabs in a `perspective: 1400px` container
  with `rotateX(58deg) rotateZ(-38deg)` and `translateZ` gaps scrubbed by GSAP. This is the
  default on mobile.

### 9.5 Performance tiers

Use drei's `<PerformanceMonitor>` plus a static capability check at start-up:

| Tier | Detected when | What runs |
|---|---|---|
| **High** | Desktop, `deviceMemory` ≥ 8 or unknown, no reduced motion | Everything; DPR up to 1.75 |
| **Medium** | Laptop on battery, mid phones, or monitor reports dips | Transmission → `MeshStandardMaterial` + env; 24 cards; 1,200 stars; no noise; DPR 1.25 |
| **Low** | `prefers-reduced-motion`, `saveData`, no WebGL2, or < 30 fps for 2 s | No WebGL. Poster images (`IMG-01`, `IMG-05`–`IMG-10`) with CSS crossfades between chapters |

Always pause rendering (`frameloop="demand"`) when the canvas is off-screen or the tab is
hidden.

---

## 10. Motion system

### 10.1 Principles

1. **Motion explains the sprint.** Every big animation shows something the research does:
   sizing, staffing, costing, risk, time passing. If it doesn't, cut it.
2. **One orchestrated journey; quiet elsewhere.** The home page earns its spectacle. Inner
   pages animate only reveals, the pipeline token and chart draws.
3. **The user holds the scrubber.** Story animations are scroll-scrubbed, never autoplayed on a
   timer the reader can't control.
4. **Never animate what people use many times a minute:** nav links, tabs, filters, focus.
   Those respond instantly.

### 10.2 Tokens

```css
--ease-out:     cubic-bezier(0.23, 1, 0.32, 1);     /* entrances, UI response */
--ease-in-out:  cubic-bezier(0.77, 0, 0.175, 1);    /* things moving on screen */
--ease-drawer:  cubic-bezier(0.32, 0.72, 0, 1);     /* sheets, mobile menu */
--dur-press: 140ms;  --dur-ui: 200ms;  --dur-panel: 320ms;
--dur-reveal: 700ms; --dur-hero: 1200ms;
```

- GSAP: `power3.out` ≈ `--ease-out`, `expo.inOut` for the dawn.
- Springs (hover tilt only): `{ stiffness: 170, damping: 26 }`, no visible bounce.

### 10.3 Smooth scroll

Lenis (`lerp: 0.1`, `wheelMultiplier: 1`, touch native), synced to GSAP's ticker.
**Disabled** when `prefers-reduced-motion` is set, and on any page without scroll-scrubbed
scenes (inner pages use native scroll).

### 10.4 Home load sequence (once per session)

| t (ms) | What happens |
|---|---|
| 0 | Night canvas and HTML text already painted (no layout shift) |
| 0–600 | Eyebrow and lead fade up 8 px (`--ease-out`) |
| 150–1,050 | H1 lines: `wdth 75 → 125`, `wght 450 → 800`, opacity 0 → 1, stagger 120 ms |
| 900–1,500 | Gradient underline draws under "Risk-checked." (`scaleX 0 → 1`, origin left) |
| WebGL ready (≈ 400–1,500) | S-Track core line draws itself along the curve (900 ms), then the ribbon fades up (400 ms) |
| + 200 | Cards drop onto the track (stagger 40 ms, `y +0.6 → 0`, `--ease-out`) |
| + 600 | Sprint clock shows `DAY 00 · PLANNING` (odometer roll) |

On revisit within the session, show the final state immediately. Never block reading for an
intro animation.

### 10.5 Scroll choreography (home)

| Section | Trigger | Animation | Scrub |
|---|---|---|---|
| Problem rows | each row top at 80% | opacity 0 → 1, y 13 → 0, 600 ms, stagger 80 ms | no (once) |
| Platform board | section pinned 120 vh | cards hop lanes in sequence | yes, `scrub: 0.8` |
| Chapter (×4) | section pinned 180 vh | camera to gate (0–35%), gate action (35–80%), panel envelopes count up (45–70%) | yes, `scrub: 1` |
| Chapter question | heading enters | `wdth 75 → 112`, `wght 500 → 750`, 900 ms | no |
| Envelope values | envelope in view | count up 0 → value, 900 ms, `--ease-out`, tabular numerals | no |
| Copilot chat | pinned 140 vh | rows type in; tool rows spinner → check | yes |
| Horizon | band covers viewport | `--dawn` 0.5 → 1 and `data-light` flip at 0.5 (§10.6) | yes |
| Results charts | chart in view | lines draw (`stroke-dashoffset`), 900 ms | no |
| Exploded stack | pinned 100 vh | slab gaps open, packets start | yes |
| Footer | footer in view | chevrons fly right 34 px, opacity, 600 ms | no |

### 10.6 The dawn mechanics

- A single ScrollTrigger sets a CSS custom property `--dawn` (0 → 1) on `<html>` from the M1
  chapter to the end of the Horizon band, and the 3D scene reads the same value.
- The background mixes continuously:
  `background: color-mix(in oklch, var(--paper) calc(var(--dawn) * 100%), var(--night));`
- **Text tokens never mix.** `data-light` flips from `night` to `day` exactly once, at
  `--dawn = 0.5`, while the Horizon band covers 100% of the viewport, so no text is ever on a
  mid-tone background. Scrolling back up flips it back at the same point.
- Respect user preference: with `prefers-color-scheme: dark` or the moon toggle pinned, cap
  `--dawn` at 0.35 (the 3D scene warms, the page stays night).

### 10.7 Micro-interactions

| Element | Interaction |
|---|---|
| Buttons | `:active` scale 0.97 (140 ms). Primary hover: gradient sheen sweeps once (400 ms). Focus: 2 px `--indigo` ring, 3 px offset |
| Text links with → | Arrow nudges 3 px right on hover (160 ms) |
| Envelope cards | Hover/focus: lift 2 px, drivers list expands (`grid-template-rows` 0fr → 1fr, 240 ms) |
| Team cards | Duotone → colour crossfade (300 ms) |
| Pipeline stages | Click: stage expands; token jumps to it (320 ms, `--ease-in-out`) |
| Nav | Compresses after 80 px scroll (height 72 → 56, 200 ms); no other nav animation |
| Mobile menu | Sheet slides from the top (`--ease-drawer`, 320 ms); items appear without stagger |
| Card tilt (Kanban fallback, documents) | Max 4° on pointer move, spring; off on touch |

### 10.8 Reduced motion

With `prefers-reduced-motion: reduce`:
- No Lenis, no pinning, no camera flight, no scrubbing.
- 3D is replaced by posters, and chapters crossfade (150 ms).
- Headings render at their settled width and weight. Counters show final values.
- The dawn is a hard switch at the Horizon band.
- Loading spinners become static "Loading…" text.

---

## 11. Components

### 11.1 Buttons

| Variant | Night | Day |
|---|---|---|
| Primary | `--indigo` fill, `#fff` text, 8 px radius, 44 px tall, 21 px padding | Same |
| Secondary | 1 px `--starlight`/20% border, text `--starlight` | 1 px `--ink`/15% border, text `--ink` |
| Text link | `--starlight` + → | `--indigo` + → |

Labels are verbs that say what happens: **Follow a sprint**, **Read the research**,
**Download PDF**, **View slides**, **Send message**.

### 11.2 Chips

- **Model chip:** `M1` in Geist Mono 12 px, pill, 1 px border in the model's state colour.
- **Tech chip:** label only, `--surface`, 1 px `--line`.
- **Status chip** (milestones): `Done` aqua-deep · `Upcoming` outline · `Today` indigo fill.

### 11.3 The envelope (core content component)

```
┌ M1 · STORY POINTS ─────────────── held-out ┐
│ 1.94                                        │  ← --t-metric, counts up
│ MAE, story points                           │  ← caption
│ ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬░░░░░░░░░░░░░░░ 3.11 base │  ← value bar vs ghost baseline
│ ↓ 37.8% vs median baseline                  │  ← delta in aqua-deep/aqua
│ ─────────────────────────────────────────── │
│ ▸ Why this matters                          │  ← expands on hover/focus
│   9 in 10 estimates land on the right       │
│   Fibonacci value or its neighbour.         │
└─────────────────────────────────────────────┘
```

- **Variants:**
  - `metric-vs-baseline` (bar + ghost bar). Lower is better for errors, so the bar is shorter
    than the ghost; label the direction explicitly.
  - `coverage-vs-target` (a range bracket with the target tick).
  - `range` (value with [low–high] bracket).
- **Props:** `code`, `label`, `value`, `unit`, `baseline?`, `target?`, `range?`, `betterWhen:
  'lower'|'higher'`, `note`, `source`.
- **Rule:** an envelope without `baseline`, `target` or `range` is not allowed. Enforce it in
  the type.

### 11.4 Chapter panel

A glass (night) container: eyebrow, question, one-line answer, 2–3 envelopes, researcher row
(avatar 34 px duotone · name · ID), link. Max width 520 px.

### 11.5 Sprint clock

As specified in §6.0. States: default, phase-change (label crossfade 200 ms), focus (ring on
the active tick).

### 11.6 Pipeline rail

A horizontal (desktop) or vertical (mobile) sequence of stage nodes connected by a 1 px line.
- Node: mono stage number (true sequence) + short label.
- Expanded node: 2–3 lines of detail + the parameter that matters (e.g., `δ = 0.594`).
- A travelling token (6 px aqua dot) marks progress.

### 11.7 Timeline item, document card, team card, presentation card

As specified in §8.2–8.5. All are keyboard-reachable with visible focus, and every download
shows format and size (e.g., `PDF · 2.4 MB`).

### 11.8 Charts

- SVG only, no chart library needed for this few charts. Use 1.5 px strokes.
- Baselines and targets are dashed `--ink-2`. The model is `--indigo`; a second series is
  `--violet`.
- Every chart has a visible title, axis labels with units, and a text summary for screen
  readers (`<figcaption>`).

---

## 12. Voice and copy

### 12.1 Voice

Plain, specific, confident without hype. Short sentences, active verbs, sentence case.
Write from the reader's side: *"Each estimate says how sure it is"*, not *"Uncertainty
quantification is provided"*.

**Words to avoid:** revolutionary, cutting-edge, seamless, leverage, unlock, next-gen,
game-changing, "AI-powered" as a headline (show it instead).

### 12.2 The envelope rule

Never show a bare number. Every figure carries its baseline, target or range, and states
whether lower or higher is better. Every results block names its data (held-out, simulation,
synthetic) in one line.

### 12.3 Ready-to-use microcopy

| Place | Copy |
|---|---|
| Hero scroll cue | Scroll to start the sprint |
| Chapter link | Explore M1 → |
| Results intro | Every figure below is from held-out data and sits beside the baseline it has to beat. |
| Data note | Models trained on synthetic datasets; validation on real organisational data is ongoing. [CONFIRM per component] |
| Documents empty state | No documents of this type yet. They're added after each milestone. |
| Form success | Message sent. We'll reply from our SLIIT email. |
| Form error (email) | Enter an email address like name@example.com. |
| 404 | This card isn't on the board. → Back to the sprint |
| WebGL off | (no message; posters simply show) |

---

## 13. Accessibility (WCAG 2.2 AA)

- **Canvas.** Every canvas is `aria-hidden="true"`. Everything it shows also exists as HTML
  text (chapter panels, captions).
- **Landmarks and skip link.** One `<h1>` per page. Use landmarks (`header`, `nav`, `main`,
  `footer`) and a "Skip to content" link.
- **Focus.** A visible indigo ring (2 px + 3 px offset) on every interactive element. Focus is
  never trapped except inside the open mobile menu, and Esc closes it.
- **Pinned sections.** They must not hijack keyboard scrolling: Tab moves through panel
  content normally, and Page Down / Space scroll the page.
- **Sprint clock.** It's a `nav` with `aria-label="Sprint chapters"`; each tick is a link.
- **Contrast.** §4.1 rules; check both light modes.
- **Images.** Images that carry meaning get alt text (written in `IMAGE-PROMPTS.md`);
  decorative images use `alt=""`.
- **Motion.** §10.8. Nothing flashes more than 3 times per second; the risk red pulse is a
  1.6 s breath.
- **Touch and zoom.** Targets ≥ 44 × 44 px. Text resizes to 200% without loss; layout reflows
  at 320 px width.
- **Form.** Labels above inputs, errors linked with `aria-describedby`, and success announced
  via `aria-live="polite"`.

---

## 14. Performance budget

| Metric | Target |
|---|---|
| LCP (mobile, 4G) | < 2.5 s. The LCP element is the hero H1 text, not the canvas |
| CLS | < 0.05. Reserve the canvas box and image aspect ratios |
| INP | < 200 ms |
| Initial JS (home, gzip) | < 180 KB before 3D. The 3D chunk (three + r3f + drei + post) loads after LCP, ≈ 250 KB gzip |
| Images | AVIF first, WebP fallback. Hero poster ≤ 180 KB at 1920 w; all images `srcset` 640/1080/1920/2560 |
| Fonts | 3 WOFF2 files, ≤ 160 KB total, `font-display: swap`, preload Mona Sans and Geist only |
| Frame rate | 60 fps desktop target; degrade tiers below 45 fps sustained (§9.5) |
| Lighthouse | ≥ 90 Performance (mobile) on inner pages; ≥ 80 on home with 3D |

---

## 15. Build plan

### 15.1 Stack

| Concern | Choice |
|---|---|
| Framework | Next.js 16 App Router, static export (`output: 'export'`). It's the team's stack. Read `node_modules/next/dist/docs/` first, because Next 16 has breaking changes (see `frontend/AGENTS.md`) |
| Language / styling | TypeScript; Tailwind CSS 4 with tokens declared in `@theme` |
| 3D | three, @react-three/fiber, @react-three/drei, @react-three/postprocessing, maath |
| Motion | GSAP + ScrollTrigger (`@gsap/react` `useGSAP`), Lenis |
| Content | Typed TS content modules + MDX for long-form research pages |
| Hosting | Vercel or GitHub Pages (static) |
| Forms | A static-friendly form endpoint (e.g., Formspree) [ADD] |
| Analytics (optional) | Privacy-friendly, cookie-less |

### 15.2 Folder structure

```
portfolio/
  app/
    layout.tsx  page.tsx
    research/page.mdx
    components/[slug]/page.tsx
    copilot/page.tsx  milestones/page.tsx  documents/page.tsx
    presentations/page.tsx  team/page.tsx  contact/page.tsx  not-found.tsx
  components/
    nav/  sprint-clock/  envelope/  chapter-panel/  pipeline-rail/
    timeline/  document-card/  team-card/  charts/
  three/
    STrackScene.tsx  Track.tsx  Cards.tsx  Stars.tsx
    gates/SizingGate.tsx  gates/Loom.tsx  gates/Meter.tsx  gates/Weather.tsx
    Conductor.tsx  KanbanScene.tsx  ExplodedStack.tsx  useDawn.ts  perfTier.ts
  motion/
    lenis.ts  scroll-timeline.ts  dawn.ts
  content/
    project.ts   members.ts   components.ts   results.ts   milestones.ts   documents.ts
  public/
    images/ (IMG-xx.avif/.webp)   shots/ (SHOT-xx)   docs/ (PDFs)   fonts/
```

### 15.3 Content model (excerpt)

```ts
type Envelope = {
  code: 'M1' | 'M2' | 'M3' | 'M4';
  label: string; value: number; unit: string;
  betterWhen: 'lower' | 'higher';
  source: 'held-out' | 'simulation' | 'cross-validation';
  note: string;
} & ({ baseline: { label: string; value: number } }
   | { target: number }
   | { range: [number, number] });

type Component = {
  code: Envelope['code']; slug: string; question: string; answer: string;
  title: string; researcher: { name: string; id: string };
  sprintDay: number; phase: 'PLANNING' | 'MID-SPRINT' | 'IN FLIGHT';
  envelopes: Envelope[]; pipeline: { label: string; detail: string }[];
  poster: `IMG-${string}`;
};
```

### 15.4 Phases

1. **Foundation (2 days):** tokens, fonts, layout, nav, footer, content model, all inner pages
   with real content and no 3D.
2. **Home without 3D (2 days):** every section with posters, the sprint clock, envelopes and
   charts. This is the reduced-motion site and must be shippable on its own.
3. **S-Track (4–5 days):** path, ribbon, cards, camera, then the gates one at a time
   (M1 → M3 → M4 → M2), then the conductor.
4. **Dawn + choreography (2 days):** `--dawn`, Horizon flip, scroll timelines, load sequence.
5. **Secondary scenes (2 days):** isometric Kanban, exploded stack (CSS 3D first).
6. **Polish and QA (2 days):** performance tiers, accessibility pass, cross-device testing,
   Lighthouse.

---

## 16. Content checklist (collect from the team)

| Item | From | Status |
|---|---|---|
| Confirmed component titles (TAF wording) | Each member | [ADD] |
| Confirmed headline metrics (§2.3) and data notes | Each member | [ADD] |
| Problem, gap, objectives text per component (from theses) | Each member | [ADD] |
| Portraits (same setup, see `IMAGE-PROMPTS.md` §5) + 2-line bios + links | Each member | [ADD] |
| Supervisor consent for photos/names | Group | [ADD] |
| Milestone dates, marks, deliverables | Group | [ADD] |
| PDFs: TAF, proposals ×4, progress reports, final theses ×4, group report, paper, checklists | Group | [ADD] |
| Slide decks (PDF) for each presentation | Group | [ADD] |
| Demo video URL | Group | [ADD] |
| Product screenshots `SHOT-01`–`SHOT-08` | Each member | [ADD] |
| Group email, GitHub URL | Group | [ADD] |

---

## 17. Definition of done

- [ ] Every page passes axe with zero serious issues; keyboard-only walkthrough completed.
- [ ] The reduced-motion and no-WebGL versions are complete and attractive (posters, no gaps).
- [ ] Every number on the site comes from `content/` and follows the envelope rule.
- [ ] Every member has confirmed their component copy and metrics.
- [ ] Lighthouse and Core Web Vitals meet §14 on a real mid-range Android phone.
- [ ] No text is baked into any generated image; all labels are HTML.
- [ ] The AI-image disclosure is in the footer; no AI-generated faces of real people.
- [ ] All downloads work, with correct file names (`R26-SE-005_<Owner>_<Doc>.pdf`).
- [ ] Open Graph images render for every page (Slack, WhatsApp, LinkedIn check).
- [ ] The dawn flip never shows text on a mid-tone background (scroll slowly to check).
