# AgileRisk → SprintIQ: what to port, and what not to

Analysis of `risk_prediction` (AgileRisk) against SprintIQ's `main`, for folding its
features into R26-SE-005. Written after running both systems side by side.

The headline: **the two projects solve the same problem twice.** SprintIQ already has
all three risk models wired through its own inference pipeline. Porting AgileRisk
wholesale would duplicate that. What AgileRisk has that SprintIQ genuinely lacks is
the *layer above* the models — aggregation, live monitoring, narrative advice, and the
UI that makes a risk score actionable.

---

## 1. Architecture, side by side

| | AgileRisk | SprintIQ |
|---|---|---|
| Java backend | Spring Boot, `com.agilerisk` | Spring Boot, modular monolith |
| AI service | FastAPI, port 9002 | FastAPI, port 8000 |
| Risk models | 3 (overbudget, reqchange, collaboration) | same 3, plus story points, velocity, 2 cost |
| DB | `agilerisk`, 12 migrations | `sprintiq`, 23 migrations |
| Multi-tenancy | none — single project | org → workspace → project |
| Auth | JWT, disabled by default | full RBAC, 37 permissions |
| Frontend | Next.js, 9 screens | Next.js, 21 screens |

**Consequence for porting:** AgileRisk has no tenancy or permissions. Every entity,
endpoint and query brought across needs `organizationId` scoping and a `@PreAuthorize`,
or it becomes a hole in SprintIQ's isolation. This is the single biggest hidden cost.

---

## 2. What SprintIQ already has — do not port

| AgileRisk piece | SprintIQ equivalent | Verdict |
|---|---|---|
| `OverBudgetRiskClient` | `RiskService` + `M2_OVER_BUDGET` | duplicate |
| `RequirementChangeRiskClient` | `RiskService` + `M2_REQUIREMENT_CHANGE` | duplicate |
| `CommunicationCollaborationRiskClient` | `RiskService` + `COMMUNICATION_COLLABORATION` | duplicate |
| `SprintRiskAdjustment` entity/ledger | `SprintRiskAdjustmentService` | already ported |
| Daily risk trend | `SprintRiskTrendService` | already there |
| Sprint / Task / UserStory / Comment | full delivery core | far richer in SprintIQ |
| Resilience retry on AI calls | latency budget + `DEGRADED` status | equivalent |

SprintIQ's versions are better adapted: they carry provenance, cache keys, model
versions and feedback capture that AgileRisk's clients do not.

---

## 3. What is genuinely new — the port list

Ordered by value against effort.

### 3.1 DistilBERT collaboration weights — **highest value, near-zero effort**

SprintIQ's `ai-service/models/communication_model/` is an **empty directory**. The
loader logs:

```
WARNING No DistilBERT weights at .../models/communication_model — collaboration risk
will be scored from the composite equation the corpus was labelled with.
```

`/health` confirms `"scorer": "composite_rules"`. So SprintIQ's collaboration risk is
currently a rules engine reproducing its own training labels — it will look accurate
and is not the model.

AgileRisk has the real weights:

```
model.safetensors     268 MB
config.json, tokenizer.json, tokenizer_config.json
Labels: {0: Low, 1: Medium, 2: High}
```

**Action:** copy the four files into `ai-service/models/communication_model/`, restart,
confirm `scorer` no longer reads `composite_rules`. Verify SprintIQ's
`collaboration_predictor.py` expects the same label order — both use Low/Medium/High.

Caveat worth checking first: AgileRisk feeds the model a **generated sentence**, not raw
comment text —

```python
f"The team had an average response time of {avg_rt} hours. "
f"There were {cpt} comments per task on average. "
f"The team was inactive for {inactive} days during the sprint. "
f"There were {blockers} blocked tasks and {reopened} reopened tasks."
```

So it is a DistilBERT reading a template filled with five numbers, not a language model
reading what people actually wrote. It will only behave as trained if SprintIQ builds
the identical sentence. Confirm before assuming the weights drop in cleanly.

### 3.2 Weighted risk aggregation — **high value, small effort**

SprintIQ reports three separate risk scores and a `highestSeverity`. AgileRisk computes
a single **overall score**, which is the number a delivery lead actually wants.

```
weights:  over-budget 0.40 · requirement-change 0.30 · collaboration 0.30
thresholds: MEDIUM ≥ 35 · HIGH ≥ 70
```

Two details worth copying exactly, because they are the thoughtful part:

- **Re-normalising weights.** A model that could not score (insufficient data)
  contributes *no weight*, and the rest are renormalised — rather than being counted as
  a reassuring zero. If none scored, all three fall back to 1/3.
- **Degraded bump-up.** If any model fell back to a default, the final level is bumped
  one band upward. A degraded estimate is treated as more dangerous, not less.

Both map cleanly onto SprintIQ's existing `RiskSeverity` and `PredictionStatus.DEGRADED`.

### 3.3 `TaskCommunicationMonitorService` — **high value, medium effort**

A `@Scheduled` job that applies risk penalties from board activity between assessments.
SprintIQ has the adjustment *ledger* but nothing that writes to it on a timer.

Four rules, each idempotent via a unique reason key:

| Key | Condition |
|---|---|
| `TASK_SILENT:` | no comments once half the estimated hours have elapsed |
| `COMMENT_UNANSWERED:` | last comment older than the cutoff, no reply |
| `TASK_NOT_STARTED_HALF:` | still TODO at half-time (penalises both CC and OB) |
| `TASK_NOT_DONE_OVERDUE:` | past the estimated-hours deadline |

The `KEY_..._RESOLVED` / `KEY_COMMENT_ANSWERED` counter-keys let a penalty be cancelled
when the team fixes it, so the score can go back down. That reversibility is what stops
it becoming a ratchet.

### 3.4 LLM Risk Advisor — **high value, medium effort, needs a decision**

SprintIQ has **no LLM layer at all**. AgileRisk sends the aggregated risk, SHAP feature
impacts, findings and model explanations to Gemini with a 9,604-character system prompt
and returns a narrative advisory.

```
base-url: https://generativelanguage.googleapis.com/v1beta/openai
model:    gemini-2.0-flash   (OpenAI-compatible chat/completions)
```

It degrades honestly when no key is configured — tells the user to get a free key rather
than inventing advice.

**Decision needed:** this sends sprint data to Google. For a university project that may
be fine; it should be an explicit, documented choice, and the API key must not be
committed. The `advisor.api-key` is already env-only, which is correct.

### 3.5 The UI layer — **high value, largest effort**

SprintIQ's `risk-card.tsx` shows one verdict. AgileRisk has a whole workflow:

| Component | Lines | What it does |
|---|---|---|
| `GuidedFixWizard` | 375 | walks a user through resolving a finding step by step |
| `FindingSolver` | 169 | per-finding resolution with actions |
| `CollaborationRiskPanel` | 165 | detected causes + recommendations |
| `DailyRiskTrendChart` | 98 | risk over the sprint's days |
| `RiskGauge` | 34 | the overall score dial |

Plus four screens SprintIQ lacks: **Risk Analysis**, **Reports**, **Trends**, **Compare**
(sprint-vs-sprint).

`GuidedFixWizard` is the standout. Every other AI feature in SprintIQ stops at "here is a
number and why"; this one continues to "here is what to do about it, one step at a time".
That is the genuinely differentiating idea in AgileRisk.

### 3.6 Reopen-risk scaling (migration V12) — **small, self-contained**

A neat rule SprintIQ does not have:

```
delta = weight * (4.0 + 1.5 * (reopen_count - 1)),  capped at 12.0
weight = clamp(estimated_hours / 8, 0.5, 2.0), default 1.0
```

A task bounced six times no longer scores the same as one bounced once, and a large task
bouncing costs more than a small one. The comment also records a deliberate restraint:
the model *feature* `reopened_tasks` still counts distinct tasks, because the model was
trained on 0–8 tasks and feeding it reopen events would claim six tasks broke down when
one did. Worth copying that reasoning along with the formula.

### 3.7 Detected-causes thresholds — **small, and needs care**

Plain conditionals turning features into sentences:

| Feature | Elevated | Severe |
|---|---|---|
| avg response time | > 24h | > 48h |
| comments per task | < 2 | = 0 |
| inactive days | > 4 | > 7 |
| blocked tasks | > 2 | > 5 |
| reopened tasks | > 2 | > 4 |

Useful, but note these are **hand-set constants, not learned**. They should be presented
as heuristics in the UI, not as model output — the same honesty rule SprintIQ applies
elsewhere.

---

## 4. Recommended order

1. **Copy the DistilBERT weights** — one file copy, turns a rules engine back into the model it claims to be. Verify the input-sentence format matches first.
2. **Weighted aggregation + overall score** — small, self-contained, immediately useful; reuses `RiskSeverity` and `DEGRADED`.
3. **Reopen scaling + detected-cause thresholds** — pure logic, no new infrastructure.
4. **`TaskCommunicationMonitorService`** — needs org scoping and a scheduler; the ledger it writes to already exists.
5. **UI: RiskGauge → CollaborationRiskPanel → DailyRiskTrendChart** — increasing size, each independently shippable.
6. **GuidedFixWizard + FindingSolver** — the biggest win and the biggest job; do it last, and redesign to SprintIQ's token system rather than copying its CSS.
7. **LLM advisor** — last, and only after an explicit decision about sending data to Google.

---

## 5. Things that will bite

- **No tenancy anywhere in AgileRisk.** Every ported entity needs `organizationId`; every endpoint needs `@PreAuthorize`. SprintIQ's ArchUnit rules will catch layering violations but not missing authorisation.
- **`Long` ids vs `UUID`.** AgileRisk uses sequential `Long` primary keys throughout; SprintIQ uses `UUID`. Sequential ids across a tenancy boundary are an enumeration risk. Convert, don't carry across.
- **Two different `Sprint` entities.** AgileRisk's is standalone; SprintIQ's belongs to a project with capacity, workflow states and metrics. Map onto SprintIQ's — do not import a second one.
- **sklearn version mismatch.** AgileRisk's bundles were pickled with scikit-learn **1.8.0**; this machine has **1.7.2**, and loading logs `InconsistentVersionWarning: ... might lead to breaking code or invalid results`. If SprintIQ's own overbudget/reqchange bundles are retrained from these, align the version first.
- **CORS is hardcoded** to `localhost:3000` in `CorsConfig.java` — replace with SprintIQ's config, don't port it.
- **Windows-origin repo.** No execute bits; `node_modules` was installed for Windows and caused 168 Turbopack panics until reinstalled. Never copy `node_modules` across.

---

## 6. What not to port at all

- The whole `com.agilerisk` package structure — SprintIQ's modular monolith with inward-pointing ports is stricter and already enforced by ArchUnit.
- AgileRisk's `Sprint`, `Task`, `UserStory`, `Comment` entities — SprintIQ's are supersets.
- Its auth (`JWT_ENABLED:false` by default) and in-memory user details.
- Its CSS/design system — SprintIQ has a token layer with light/dark and a motion system.
