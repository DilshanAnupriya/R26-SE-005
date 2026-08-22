# SprintIQ estimation service

Serves SprintIQ's story-point, velocity, delivery-risk, and sprint-cost models.

## Sprint cost forecasting

The M4 cost endpoints use a deliberately hybrid design:

- `/predict/cost/initial` uses the sum of the sprint's task estimates. On the
  untouched time holdout this planning baseline had 23.45% MAPE and beat the
  initial ML candidate's 25.65%, so deploying the model would make the result
  worse.
- `/predict/cost/mid-sprint` predicts remaining effort minutes with gradient
  boosting, then adds the actual cost already incurred. Its 28.63% holdout MAPE
  beats the simple remaining-estimate baseline's 49.85%.
- Both paths convert effort to money using the blended hourly rate sent by the
  backend. Currency and rate are not hidden training constants.
- Actual cost so far is calculated by the backend from time entries grouped by
  developer, using each available profile rate and a 1.5x overtime rate. The
  project blend is only the fallback when an individual rate is unavailable.

The response's range is based on holdout MAPE and is explicitly a heuristic,
not a calibrated probability interval. Accordingly, M4 returns no probability
`confidence` value. Its drivers are additive cost components rather than
global feature importance presented as a local explanation.

The training CSV contains completed-sprint aggregates but no timestamped
snapshots. `training/train_cost_models.py` therefore generates deterministic
35%-65% partial snapshots for mid-sprint research. This limitation is recorded
in the evaluation report; production snapshots should replace them when enough
historical telemetry has been collected.

```bash
cd ai-service
PYTHONPATH=training .venv/bin/python training/train_cost_models.py \
  --data-path /path/to/final_agile_dataset.csv --stage both --strict-targets
PYTHONPATH=training .venv/bin/python training/evaluate_cost_models.py \
  --data-path /path/to/final_agile_dataset.csv --artifacts-dir models
PYTHONPATH=. .venv/bin/pytest -q tests/test_cost_predictor.py
```

## What the model is

Two models working together, from `model_xgb_lgbm_uncertainty.pkl`:

- **XGBoost** gives the point estimate.
- **Two LightGBM quantile heads** (P20 / P80) give the interval, widened by a
  **conformal (CQR) correction** measured on a held-out split.

That correction is the reason the interval means what it says. The raw quantile
heads covered the true value 70% of the time while claiming 80%; after the
correction and Fibonacci snapping, measured coverage is 90%.

| | |
|---|---|
| Test MAE | **1.94** points (constant-predictor baseline: 3.11) |
| Within one Fibonacci step | **89%** |
| Exact match | 41% |
| Trained on | `Agile_Estimation_Dataset_Realistic_v2.xlsx`, n=3000 |

## Running it

```bash
cd ai-service
pip install -r requirements.txt
uvicorn app.main:app --port 8000
```

The backend's `dev` profile already points at `http://localhost:8000`. If the
service is not running, `HttpAiModelClient` turns the connection failure into a
`FAILED` prediction and the UI shows the story unestimated — it does not invent a
number.

```bash
curl localhost:8000/health           # models loaded, columns, test MAE
curl localhost:8000/model/contract   # every field each model accepts, and the defaults
```

`requirements.txt` does not include torch or transformers. Only the
communication-collaboration model uses them, only to serve its fine-tuned
weights rather than the equation behind them, and they cost about 2.5GB
installed — see `requirements-training.txt` and the section on that model below.

## The input contract

184 columns in a fixed order: **150 TF-IDF text columns first, then 34
structural**. That order comes from the notebook's `hstack([X_text, X_struct])`.
Feeding the halves the other way round produces confident nonsense rather than an
error, which is the worst failure mode available, so `predictor.py` asserts the
width at load rather than at first request.

**Text** — `title`, `description`, `acceptance_criteria`, concatenated. Word and
character counts are computed here rather than trusted from the caller, so they
always describe the text actually scored.

**Categorical** — only these values register. Anything else sets no one-hot at
all, which is deliberate: an unseen stack should not be silently filed under
whichever one sorts first.

| Field | Accepted values |
|---|---|
| `task_type` | bug, enhancement, feature |
| `domain` | ecommerce, education, finance, healthcare, logistics |
| `tech_stack` | angular, django, dotnet, fastapi, flask, nodejs, rails, react, springboot, vue |
| `dev_experience_level` | junior, mid, senior |

SprintIQ's story types are mapped rather than passed through — `BUG → bug`,
`STORY/TASK → feature`, `SPIKE/CHORE/TECH_DEBT → enhancement`.

**Numeric** — `num_components`, `external_apis`, `has_integration`,
`has_security`, `has_ui_complexity`, `team_size`, `sprint_duration_days`,
`similar_task_count`, `team_velocity_avg`, `recent_completion_rate`,
`task_age_days`.

## Missing values become medians, never zeros

Zero is a real value in this feature space. `team_velocity_avg=0` tells the model
the team has never delivered anything, and it prices the story accordingly. A
field the caller omits should mean *typical*, not *catastrophic*, so it falls
back to the median of the training set.

Every response carries a `provenance` block naming what was defaulted and what
was guessed, and the explanation says so in words. An estimate built mostly from
defaults should not read as a measurement.

## What is inferred rather than known

Three flags — `has_integration`, `has_security`, `has_ui_complexity` — are
guessed from the story text by keyword when the caller does not supply them.
These are keyword probes, not understanding. They appear in
`provenance.inferred_from_text` so the guess is visible.

If they turn out to matter, the honest fix is to ask for them on the story form
rather than to make the regexes cleverer.

## Drivers

`drivers` comes from XGBoost's `pred_contribs` — exact SHAP for tree ensembles —
so it says what moved *this* estimate. Global feature importance would answer a
different question, "what matters on average", and presenting that as *why 8?* is
an explanation worse than none.

TF-IDF contributions are summed into a single "wording of the story" entry.
Attributing an estimate to individual words is not something a TF-IDF
contribution sum can honestly do.

## Known behaviour worth knowing

The model leans on the training distribution when the text signal is weak. "Fix
typo on the settings page heading" comes back at **5 points** — the training set
has a mean of 6.15 and a median of 5, and a five-word title gives the model very
little to go on. This is the model's own behaviour, not a wiring fault. Treat
short, low-context stories as the case where a human number beats the model's.

`risk_level` (LOW / MEDIUM / HIGH) is calibrated from terciles of the observed
interval-width distribution, so LOW means "narrow *for this model*", not narrow
in absolute terms. The explanation says so when the upper bound is at least twice
the lower.

## Communication and collaboration risk

`POST /predict/risk/communication-collaboration` is the third M2 classifier, and
it is built differently from the other two. Five readings go in — average reply
time in hours, comments per task, days with nothing recorded, blocked tasks,
reopened tasks — and come back as one of Low / Medium / High.

**The labels are synthetic, and that is not a shortcut.** There is no
observational dataset for this: no sprint's history carries a column saying it
failed because the team stopped talking. So the corpus is generated, and its
labels come from `compute_risk_score` in `app/collaboration_features.py`, a
deterministic composite over the same five readings, scored 0–18 and banded at 4
and 8.

Which raises the fair question of what the transformer is for. **Tolerance.** The
composite is a stack of step functions, so a sprint at 7 is Medium and one at 8
is High with nothing between them. DistilBERT fine-tuned on three thousand of
those examples learns the shape of the boundaries and answers with a
distribution, so a near-edge sprint reads as near-edge. It is a smoothing of the
equation, not a second opinion about it.

That relationship is why this endpoint, alone among the three, never answers 503
for a missing bundle. Without the weights it scores from the equation — the
classifier's own boundary in closed form — and reports which one answered:

```json
{ "risk_level": "High", "value": 0.99, "provenance": { "scorer": "distilbert" } }
```

`scorer` is `distilbert` or `composite_rules`. `/health` reports it too.

The weights are ~270MB, past what a git remote will take, so they are the one
model in `models/` that is **not** committed. Regenerate them:

```bash
pip install -r requirements-training.txt
python -m training.create_communication_dataset
python -m training.train_communication_model
```

`features_to_text` is the model's input contract as much as the field names are.
It lives in `app/collaboration_features.py` and the corpus generator imports it
rather than keeping a copy — reword it and inference is reading sentences the
corpus never contained, which degrades quietly rather than erroring.

**No accuracy figure is published for this model, deliberately.** Held-out
accuracy against a corpus generated by the same function you are scored against
measures memorisation of an equation, not prediction of anything. A High here is
a well-argued flag built on an agreed definition of poor collaboration — not
evidence that collaboration caused a sprint to fail.
