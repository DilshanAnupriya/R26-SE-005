# Edge-case testing: story points and velocity (M1)

**Run on 16 October 2026, extended 18 October 2026** (stories past the training data,
the wider 0.5-100 scale, velocity outside the trained range, metamorphic tests) · branch
`Latest` · owner: Dilshan (M1_STORY_POINTS, M1_VELOCITY)
· Python 3.12.15 with the pinned `requirements.txt` (numpy 2.3.5, scikit-learn 1.7.2,
xgboost 3.2.0, lightgbm 4.6.0)

At Progress Presentation 2 the panel asked for test cases covering edge cases and more
complex situations. This document is the answer for the two M1 models: every hard case
we could think of, what the service should do with it, what it actually did before
this work, and the test that now holds it to the expected behaviour.

---

## The short version

| | |
|---|---|
| **Result** | **144 / 144 edge-case tests and 114 / 114 metamorphic tests pass.** The whole ai-service suite passes too: 355 passed, 1 skipped (the DistilBERT test, whose weights are deliberately not in git). |
| **Do the tests catch anything?** | Yes. Run against the code as it was before this work, **59 of the first 98 edge-case checks** and **15 of the 101 metamorphic checks** fail. Some fail only because the old code reports less (no `rejected_as_invalid`, no scaling record); the rest are the real defects listed [below](#defects-found-and-fixed). |
| **Do the models need retraining?** | No. Nothing here shows the trained XGBoost or LightGBM models predicting badly *inside* the range they were trained on. Outside it they cannot be right, and the service now says so instead of guessing. |
| **What was wrong** | 14 defects in the **service code around the models**: input cleaning, how the uncertainty range is built, what the label and explanation claim, how a story bigger than any in the training data is handled, and how a small SprintIQ Story is read. All 14 are fixed. |
| **What was added** | Story points on the full Planning Poker deck, **0.5 to 100**, with stories past the training data extrapolated rather than capped at 13; velocity that follows a team's own record when it is slower or faster than any team in the training data. See [Beyond 13, below 1, and teams outside the range](#beyond-13-below-1-and-teams-outside-the-range). |
| **What is still a limitation** | 6 things that need more data, not more code. They are listed [below](#known-limitations-not-fixed), so the panel hears them from us first. |
| **Tests** | 97 test functions, 258 cases: `ai-service/tests/test_edge_cases_story_points.py`, `test_edge_cases_velocity.py` and `test_metamorphic.py` |

### "The dataset only goes up to 13. How can it handle a huge story?"

The training data's stories have at most **7 components** and **6 external APIs**, and
at most 13 points. Inside that the model answers as it was trained to. Past it the
service now **extrapolates** onto the rest of the Planning Poker deck — 20, 40, 100 —
says so in the explanation, labels it HIGH and suggests splitting (SP-29 to SP-38). An
18-component migration is now **20, likely 13 to 40**; before any of this work it came
back as **8, "tight enough to plan against"** for a senior team, and a story with 7
external APIs scored as if it had none. How the extrapolation works, and why it is
defensible without data above 13, is [below](#beyond-13-below-1-and-teams-outside-the-range).

The panel's comment is about **testing and documenting behaviour in hard cases**, not
about accuracy. A model with a good average error can still give nonsense for a new
team, a sprint where everyone is on leave, or a request with a broken number in it.
That is what this work checks.

---

## Beyond 13, below 1, and teams outside the range

### The problem

The story-point dataset runs from 1 to 13 points; the velocity dataset's teams average
roughly 13 to 54 points a sprint. A tree model cannot answer outside the values it was
trained on — every tree sends a bigger input down the same branch as the biggest one it
saw. So before this work an 18-component epic came back as 13 at most, and a team that
had delivered nothing for three sprints was forecast about 10.

There is no data above 13 or below 1 to retrain on, and the training datasets are not
in the repository. So the answer has to be built on top of the trained models, and has
to be honest that it is an extrapolation.

### Story points: the Planning Poker deck, and two extrapolations

**The scale.** Estimates are reported on the deck planning sessions use, chosen as the
one scale for all of SprintIQ: **0, ½, 1, 2, 3, 5, 8, 13, 20, 40, 100**. The picker, the
backend's mock and the model service all use it. (The app's old picker offered 21; it is
now 20.)

**Inside the training data** (at most 7 components and 6 external APIs) nothing changes:
the trained model answers, its calibrated range is shown, and it snaps to the nearest
card. A one-line fix still comes out at about 2.

**Past the training data**, two standard ways of extrapolating give a lower and an upper
estimate, and the answer sits between them:

| | How | Assumption | Errs towards |
|---|---|---|---|
| **Trend** | The points the model itself adds per extra component (or API) between the training minimum and maximum, for this story, continued past the maximum. About 0.4 points per component for a typical story. | Everything else about the story stays as it is. | **Too small**: a bigger story is usually bigger in every way at once. |
| **Proportional** | The model's answer for the biggest story it knows, times how far past it this story is (18 components is 18/7 of 7). | Story points add up when a story is split — the premise of splitting an epic. | **Too large**: it also scales the fixed part of a story, its context and setup. |

The estimate is their **geometric mean** — the middle on a deck whose cards grow
geometrically. The range runs from the model's calibrated lower bound plus the trend, to
its calibrated upper bound scaled in proportion, so it widens the further a story is from
the data. At the training maximum both extrapolations equal the model's own answer, so
the estimate is continuous where the model stops (SP-37).

What a planner sees, for the demo's migration story (mid-level team):

| Components | Estimate | Range | |
|---|---|---|---|
| 3 | 8 | 8–13 | the model, as before |
| 7 | 13 | 8–13 | the model, at its limit |
| 12 | 13 | 8–20 | extrapolated |
| 18 | **20** | 13–40 | extrapolated |
| 35 | **40** | 20–40 | extrapolated |
| 150 | **100** | 40–100 | "read it as 100 or more" |

Every extrapolated answer is labelled HIGH, capped at 30% confidence, and explained in
words: "This story has 18 components touched (the most in its training data was 7), so
it is bigger than anything the model has seen and the estimate is extrapolated, not
measured: the model's own trend continued gives about 15, the biggest story it knows
scaled in proportion gives about 28, and the estimate sits between them. Treat it as an
epic, and split it before committing to it." It does not borrow the model's "70% of the
time" coverage, which was measured on stories the model knows (SP-38).

**Why not retrain on made-up big stories?** It was considered: compose an epic from
several real stories and give it the sum of their points. It makes the same additivity
assumption as the proportional estimate, but buries it inside the weights, where nobody
can see or question it; it also needs the training data, which is not here. The
extrapolation states its assumption in every answer.

### Story points: the low end

The deck goes down to 0 and ½: a model answer below 0.75 lands on ½, and one below 0.25
on 0 (SP-39). In
practice the trained model's smallest answers sit around 2: its data starts at 1, and a
tree model pulls the smallest stories up towards the middle. Its calibrated *range* for
a one-line fix does reach 1 (SP-40), which is where the honest uncertainty is. There is
no signal that a story is smaller than the smallest in the training data the way there
is for bigger ones — a story with nothing written down is under-specified, not small — so
the service does not invent sub-1 estimates. A team can choose 0, ½ or 1, and it saves.

The way to make the model itself reach those cards is data: SprintIQ already records
every accepted and overridden estimate with its final value (`ai_feedback`), including
the 0.5s, 20s and 40s teams choose. Retraining on that is the next step once enough has
accumulated.

### Velocity: a team's own record outside the trained range

The same reasoning applies to a team slower or faster than any in the velocity data.
The trees tell three-sprint averages apart only between 13 and 54 points (their lowest
and highest splits). Outside that range the forecast is now the model's answer at the
edge, scaled by the team's own average against that edge — "yesterday's weather", the
rule sprint planning already uses:

| Team's last sprints | Before | Now |
|---|---|---|
| 0, 0, 0 | 9.6 | **0** |
| 3, 4, 5 | about 10 | **2.9** |
| 60, 62, 64 | about 45 | **51.9** |
| 65, 68, 67, 69, 70 | 45.5 | **57.7** |
| a million a sprint | 45.5 | 91, scaled at most ×2, HIGH, "worth checking" |

Upward scaling is capped at double, because a history that far past the training data is
more often a recording problem than a team. The explanation says what was scaled and why
(V-27 to V-33, MR-13, MR-14). This is the same mechanism as the heavy-absence fix, and
the two combine.

### Small Stories were read as new features

The presentation test cases (`docs/m1-story-point-test-cases.txt`) found every small
SprintIQ **Story** sized at 8: "Add a tooltip to the Export button" — three components, no
dependency — came back 8 (5–13), while the same tooltip filed as a Chore came back 5.

**Cause.** The dataset splits work into **feature** (a new capability) and **enhancement**
(a change to an existing one). SprintIQ's Story says neither, and the service mapped
every Story to "feature". At the same structure the model puts about two points between
the two categories (measured: the tooltip is 7.7 as a feature and 5.9 as an enhancement;
the Stripe story 10.6 and 8.9), so every small Story started in the population of new
capabilities.

**Why not just change the mapping.** Reading every Story as an enhancement, or as no
category at all, fixes the tooltip and breaks every large case:

| Case | as feature (before) | as enhancement | as no category |
|---|---|---|---|
| Tooltip (3 components) | 8 | **5** | 5 |
| Stripe (7 components, 1 dependency) | **13** | 8 | 8 |
| Single sign-on (7 components, 2 dependencies) | **13** | 8 | 8 |
| Mobile app (16 components) | **20** | 13 | 13 |
| Microservices (36 components) | **40** | 20 | 20 |

**The rule.** A Story — or a Task, which the old mapping also called a feature — is read
as an **enhancement** when everything the service can measure says it is a small change:

- no more components than the dataset's **median** story (3);
- **no external dependency** (a dependency is integration with something outside);
- **no security work** (security changes are rarely small);
- a write-up of **at most 40 words**, about twice the longest text in the training data —
  the model's length features stop at 19 words, so a story written up at more length
  than that carries scope the model cannot read, and is not called small on its
  structure alone.

Otherwise it stays a **feature**, so every large story sizes exactly as before. A Story
whose component count was not sent keeps the old reading: unknown size is not small.
The explanation says when the rule applied ("sized as a change to something existing
rather than a new feature"), and `provenance.story_type_read_as` records it either way.

**Result**, on the presentation's team (4, mid-level, finance, Spring Boot):

| Case | Before | After |
|---|---|---|
| TC-03 tooltip, Story, 3 components | 8 (5–13), 34% | **5 (3–8)**, 45–51% |
| TC-12 "Misc improvements", 1 component | 5 (2–5) | **3 (1–3)** |
| TC-04 CSV export (6) / TC-05 overdue email (5) | 8 | 8 — features by the rule; as enhancements they would still be 8 |
| TC-06 Stripe / TC-08 SSO | 13 | 13 |
| TC-07 RBAC | 8 | 8 |
| TC-09 mobile / TC-10 microservices / 150 components | 20 / 40 / 100 | 20 / 40 / 100 |
| TC-13 long write-up (118 words) | unchanged — a feature by the 40-word rule | |
| Stripe by team: junior / mid / senior | 13 / 13 / 8 | 13 / 13 / 8 |

What it does not fix: a tooltip is still 5, not 1–2. That is the separate low floor —
the dataset has few stories under 3 points — and it needs data, not a rule.

**A related keyword.** "Export" and "import" in a story's *wording* no longer count as
integration work, in the backend (`StoryFeatureSource`) and the service alike: in a
sentence they are usually the system's own feature ("the Export button", "a CSV export"),
not another system. A person's `export` or `import` *label* still counts. On its own this
moved no test case's number; it stops the tooltip's explanation saying "pushed up by
integration work".

Migration `V34` bumps M1_STORY_POINTS to 2.3.0 so the six-hour cache stops serving the
old 8s (SP-41 to SP-45, MR-15).

### What changed outside the model service

- **Backend.** Story points are stored as `NUMERIC(5,2)` and validated at 0-999, and
  sprint totals and velocity are exact decimal sums, so 0, ½ and 100 store and add up.
  Two things did not: the mock model snapped to 1-21 and now uses the same deck
  (`MockAiModelClient`); and the board turned a story card's points into minutes with
  `intValue() * 60`, which truncated **½ point to 0 minutes** — it now multiplies first
  (`TaskService.storyPointMinutes`, tested in `StoryPointMinutesTest`). Migration `V33`
  bumps M1_STORY_POINTS to 2.2.0 and M1_VELOCITY to 2.1.0, which is what clears the
  six-hour prediction cache of capped answers. Resource allocation (M3) still rounds a
  ½-point story up to 1 for its optimiser, which works in whole points.
- **Frontend.** The story-points picker offers the full deck, 0 to 100, and the panel's
  "model said 20.6 — rounded down to 20" line knows the cards above 13 and below 1.

---

## How each case is judged

Every test expects one of four behaviours:

| Expected behaviour | Meaning |
|---|---|
| **Sensible prediction** | Story points on the 1-13 scale, velocity of 0 or more, the number inside its own range, nothing NaN, and a response the API can send. |
| **Wider / less confident** | When the model knows less, the range gets wider or the confidence goes down, and the label says HIGH. |
| **Recorded fallback** | A value that cannot be used is treated as missing, replaced with the training median, and **named** in the response's `provenance` (`rejected_as_invalid`, `defaulted_to_training_median`). |
| **Clean error** | `422` for a request with the wrong shape, `503` when no model is loaded. Never a `500`. |

"Before" numbers below were measured by running the same inputs through the code as
it was committed before this work (`2c95859`).

---

## Story points (M1_STORY_POINTS)

| ID | Scenario | Input | Expected | Before this work | Result |
|---|---|---|---|---|---|
| SP-01 | Empty request | `{}` | Sensible prediction that calls itself a baseline; confidence ≤ 0.3; all 12 missing inputs named | Same (5 points, confidence 0.03) | **pass** |
| SP-02 | Every field null | all keys `null` | Same answer as `{}` | Same | **pass** |
| SP-03 | No text at all | title `"   "`, no description | Prediction from structure; explanation says the wording was not read | Same | **pass** |
| SP-04 | Unusable number | `team_size` = NaN, ∞, −∞, 1e300, `"1e999"`, `"six"`, `[6]`, `{}` | Recorded fallback | NaN and ∞ scored and not recorded | **pass** |
| SP-05 | Infinity vs omitted | `team_size: inf` | Exactly the same answer as leaving it out | Different confidence (0.39 vs 0.34), and the driver list silently vanished: XGBoost refused the ∞ | **pass** |
| SP-06 | Impossible value | team of 0 or −4, 0-day sprint, negative counts or rates | Recorded fallback (0 stays valid where 0 is real, e.g. no external APIs) | Scored as a real measurement | **pass** |
| SP-07 | Numbers as strings | `"7"`, `" 4 "` | Read as numbers | Same | **pass** |
| SP-08 | Explanation for a bad value | `team_size: -4` | Explanation names the field that could not be used | Not mentioned | **pass** |
| SP-09 | Odd yes/no flags | `has_security` = NaN, `"yes"`, `"TRUE"`, `"no"`, `1`, `false` | NaN guessed from the text and recorded; yes/no words read as such | NaN read as **yes**; `"yes"` read as **no** | **pass** |
| SP-10 | Text and flag disagree | "Add OAuth token encryption…" with `has_security: 0` | The caller's explicit 0 wins over the keyword guess | Same | **pass** |
| SP-11 | Story type and task type disagree | `story_type: BUG`, `task_type: feature` | Story type wins; only one task type set | Same | **pass** |
| SP-12 | Tiny title, huge structure | "Fix typo" with 12 components, 5 APIs | Sensible prediction; the structure wins (13 or more, see SP-30) | The model's own number | **pass** |
| SP-13 | Interval heads cross | P20 head above P80 head (stand-in models) | Same answer as the uncrossed pair: 5, range 3–8, confidence 0.31 | Range collapsed to **5–5** with confidence **1.0**, "tight enough to plan against" | **pass** |
| SP-14 | Point outside its own interval | XGBoost 12.8, heads 2–4 (stand-in models) | Wide range, HIGH, confidence ≤ 0.5 (now 0.41) | HIGH label beside confidence **0.83** | **pass** |
| SP-15 | Very small vs very large story | one-line typo fix vs platform migration | Fix sized no bigger than the migration | Same (2 vs 13) | **pass** |
| SP-16 | Story at the top of the scale | raw estimate 14.2 (stand-in models) | 13, explained as "13 or more", split it | "The range is tight enough to plan against". The **real** migration story in SP-15 got exactly this too | **pass** |
| SP-17 | Far past the training range | 1,000 vs 1,000,000 components | Both on the top card, 100, "100 or more", with the raw number capped where the backend can store it | Same answer as 7 components, unflagged | **pass** |
| SP-18 | Everything absurd at once | team of 500, 365-day sprint, 999 APIs… | Sensible prediction | Same | **pass** |
| SP-19 | Very long story | 5,000-word description | Sensible prediction | Same | **pass** |
| SP-20 | Unknown categories | domain `manufacturing`, stack `laravel`, level `expert`, type `epic` | No category column set; same answer as leaving them blank | Same | **pass** |
| SP-21 | Spelling variants | `E-Commerce`, `Spring Boot`, `" SENIOR "`, `bug` | Recognised as the known values | Same | **pass** |
| SP-22 | Another language | `修复登录页面的错误 🐛` | Explanation says the wording was not recognised | Same | **pass** |
| SP-23 | Markup or SQL in the title | `<script>…`, `DROP TABLE` | Treated as plain text | Same | **pass** |
| SP-24 | Text fields that are not text | title `12345`, description a list | Read as text | Same | **pass** |
| SP-25 | Infinity over HTTP | JSON `1e999` | `200`, field named as rejected | `200`, nothing recorded | **pass** |
| SP-26 | Wrong request shape | `features` is a string, list or null | `422` | Same | **pass** |
| SP-27 | Empty body | `{}` | `200` with the baseline | Same | **pass** |
| SP-28 | No model loaded | — | `503` | Same | **pass** |
| SP-29 | The limits | read from the model bundle | 7 components and 6 external APIs are the most either model was trained on, and 1 and 0 the least (interval heads' own training record; point model's highest split) | — | **pass** |
| SP-30 | Bigger than the training data | 8 or 18 components; 7 or 12 external APIs | At least the model's answer at the limit, HIGH, confidence ≤ 0.3, "extrapolated, not measured", "split it", the input named | Whatever the model said, usually 8 with LOW or MEDIUM | **pass** |
| SP-31 | At the training maximum | 7 components, 6 external APIs | The model's own answer, nothing flagged | — | **pass** |
| SP-32 | 7 external APIs | `external_apis: 7` | The model scores it like 6 (the most it knows), and the estimate is at least that | Scored like **0**: XGBoost sent it down its "missing" branch, 7.46 against 8.74 | **pass** |
| SP-33 | A senior team's epic | 18-component migration, senior team | Above 13, HIGH | **8, "tight enough to plan against"** | **pass** |
| SP-34 | Drivers after capping | 18 components | Drivers say "components touched (18)", not the capped 7 | — | **pass** |
| SP-35 | The whole deck is reachable | 7, 8, 12, 18, 35, 60, 150 components | Never smaller for more; 20 by 18, 40 by 35, 100 ("100 or more") by 150 | 13 at most | **pass** |
| SP-36 | Between its two extrapolations | 18 components | Trend ≤ estimate ≤ proportional; range holds the estimate | — | **pass** |
| SP-37 | Continuous at the edge | 7 vs 8 components | One past the limit starts from the model's own answer there; under 1.5 points apart | — | **pass** |
| SP-38 | No borrowed coverage | 18 components | The extrapolated range does not claim "70% of the time" | — | **pass** |
| SP-39 | The small cards | model answers of 0.2 to 1.6 (stand-in models) | 0.2 → 0; 0.6 and 0.7 → ½; 0.8 and 1.4 → 1; 1.6 → 2 | 1 at least | **pass** |
| SP-40 | A tiny story's range | one-line typo fix, senior team | Range reaches 1 or below | — | **pass** |
| SP-41 | A small Story | TC-03 tooltip: Story, 3 components, no dependency | Read as an enhancement, ≤ 5, below the same story read as a feature, explained | **8** (5–13) | **pass** |
| SP-42 | Any sign of size | the tooltip plus a 4th component, a dependency, security work, a 41+ word write-up, or no component count | Stays a feature | — | **pass** |
| SP-43 | The large test cases | TC-06, TC-07, TC-08, TC-09, TC-10 and TC-15's team levels | 13, 8, 13, ≥ 20 HIGH, ≥ 40 HIGH; 13 / 8 by level — unchanged | Same | **pass** |
| SP-44 | A small Task | the tooltip typed as TASK | Read the same way as a Story | Read as a feature | **pass** |
| SP-45 | "Export" in the wording | the tooltip without its integration flag | Not integration work; "Sync … API" still is | Integration work | **pass** |

*Stand-in models* replace the trained models with ones that always return chosen
numbers, so cases where the two models disagree with each other can be tested on
demand instead of waiting for an input that happens to cause them.

---

## Velocity (M1_VELOCITY)

| ID | Scenario | Input | Expected | Before this work | Result |
|---|---|---|---|---|---|
| V-01 | New team (cold start) | sprint filled in, `historical_velocities: []` | Prediction from typical values, label HIGH, confidence ≤ 0.3 | 27.4 points, **MEDIUM**, confidence **0.60**, beside an explanation saying not to rely on it | **pass** |
| V-02 | Empty request | `{}` | Typical team, 11 inputs named, band widened 12% each, HIGH, confidence ≤ 0.3 | HIGH but confidence 0.43 | **pass** |
| V-03 | Averages without a list | `avg_velocity_last3: 40`, `avg_velocity_last5: 38` | Both used, neither reported as defaulted | Five-sprint average wrongly marked "defaulted"; band ±3.7 instead of ±3.3 | **pass** |
| V-04 | Only a 3-sprint average | `avg_velocity_last3: 40` | 5-sprint average = 40, no trend invented | 5-sprint average set to the dataset median 34.1, inventing an upward trend of +5.9 | **pass** |
| V-05 | One sprint of history | `[28]` | Says the history is thin, in the singular | Same | **pass** |
| V-06 | Team delivered nothing | `[0, 0, 0]` | 0 or more, and below a steady team | Same (9.6 vs 27.4); V-28 now makes it 0 | **pass** |
| V-07 | Very volatile team | `[5, 60, 3, 58, 4]` | Range at least ±30 (the team's own spread), HIGH | 18.2 with range **14.9–21.5**, MEDIUM, confidence **0.82**, the same ±3.3 as a steady team | **pass** |
| V-08 | Steady team | `[32, 35, 33, 34, 36]` | Range stays the model's own ±3.3 | Same (27.4, 24.0–30.7) | **pass** |
| V-09 | Very long history | 100 sprints | Uses the last 3 and 5 only; counts all 100 | Same | **pass** |
| V-10 | Heavy absence | 5% availability, 80 leave-days | Scaled down in proportion below the 55% training floor, said in the explanation, HIGH (now 1.8 points, 0–5.1) | **19.4** points, MEDIUM, confidence 0.83. 30% and 55% availability both gave **22.1**: the model cannot see below 57% | **pass** |
| V-11 | At the training floor | 55% availability | Not rescaled (22.1, as before) | — | **pass** |
| V-12 | Nobody available | 0% availability | 0 points, confidence 0, HIGH; workload read as overloaded | **22.5 points** with confidence 0.84; workload read as "typical" | **pass** |
| V-13 | Far past the training range | 500 vs 50,000 years' experience; 10,000 vs 1,000,000 planned points | Same answer (trees plateau) | Same | **pass** |
| V-14 | Everything absurd at once | team of 10,000, 365-day sprint… | Sensible prediction | Same | **pass** |
| V-15 | Unusable number | `team_size` = NaN, ∞, 1e300, `"six"`, `[6]`… | Recorded fallback | NaN and ∞ scored and not recorded | **pass** |
| V-16 | Infinity vs omitted | `team_size: inf` | Exactly the same answer as leaving it out | Different answer, and the driver list silently vanished | **pass** |
| V-17 | Impossible value | team of 0 or −3, 0-day sprint, negative leave, bugs or points | Recorded fallback | Scored as a real measurement | **pass** |
| V-18 | Junk in the history | `[30, "abc", null, NaN, -5, inf, 34]` | Only 30 and 34 used; history marked partly rejected (now 25.7) | **45.6 points at confidence 0.93**, built on an infinite average, with no drivers | **pass** |
| V-19 | History that is all junk | `["n/a", null]` | Treated as a new team (V-01 behaviour) | Counted as "2 sprints of history": LOW, confidence 0.76 | **pass** |
| V-20 | History not a list | `"30,32,34"` | Rejected, treated as no history | Ignored without saying so | **pass** |
| V-21 | Unknown domain | `manufacturing` | Base model (zero-shot), said plainly | Same | **pass** |
| V-22 | Odd spelling of a domain | `"  LOGISTICS "` | Still routed to the fine-tuned Logistics model | Same | **pass** |
| V-23 | Unknown experience band | `expert` | Median years, recorded as defaulted | Same | **pass** |
| V-24 | Infinity over HTTP | JSON `1e999` | `200`, field named as rejected | `200`, nothing recorded | **pass** |
| V-25 | Wrong request shape | `features` is a string, list or null | `422` | Same | **pass** |
| V-26 | No model loaded | — | `503` | Same | **pass** |
| V-27 | The history range | read from the model bundle | 13 to 54.1: the base model's lowest and highest split on the three-sprint average | — | **pass** |
| V-28 | Nothing delivered | `[0, 0, 0]` | **0**, HIGH, explained | 9.6 | **pass** |
| V-29 | A slow team | `[3, 4, 5]`, `[6, 7, 6]`, `[10, 11, 12]` | The model's answer at 13, times the team's average ÷ 13 | The model's answer for a 13-point team, whatever this team did | **pass** |
| V-30 | A fast team | `[65, 68, 67, 69, 70]` | Above the model's own answer, in proportion (about 58) | 45.5 | **pass** |
| V-31 | An implausible history | a million points a sprint | Scaled at most ×2, HIGH, confidence ≤ 0.3, "worth checking" | 45.5 at 0.93 confidence | **pass** |
| V-32 | Inside the range | `[13, 13, 13]`, steady, `[50, 52, 54]` | Not scaled: the model answers alone | — | **pass** |
| V-33 | A new team | no history | Not scaled; the cold-start rules apply | — | **pass** |

---

## Metamorphic tests (both models)

Most questions these models answer have no answer key: nobody knows a story's "true"
size, or a sprint's velocity before it happens. Metamorphic testing checks what *can*
be known: how the answer must move when the question changes in a known way. More of
something that makes work bigger must never make the estimate smaller, and a change
that does not touch the work must not touch the answer. Each relation runs on three
stories × three experience levels, or three teams, and is checked on what the planner
sees. The tests are in `ai-service/tests/test_metamorphic.py`.

| ID | Relation | How it is varied | Before this work | Result |
|---|---|---|---|---|
| MR-01 | More components never lower the estimate | 1 to 20 components | Held (the estimate rose to 6, then plateaued; it now keeps climbing past 7) | **pass** |
| MR-02 | More external APIs never lower the estimate | 0 to 12 APIs | **Broken.** The typo bug went 3, 5, 5, 5, 5, 5, 5, **3**: 7 APIs read as none | **pass** (3, 5, … 5, 13, 13) |
| MR-03 | Inside the training range, the model's own number never dips by more than 0.15 | 1–7 components, 0–6 APIs, before snapping | Held; going from 2 to 3 APIs dips by up to 0.1, invisible once snapped | **pass** |
| MR-04 | Adding integration, security or UI work never lowers it | each flag 0 → 1 | Held | **pass** |
| MR-05 | Saying the same story differently changes nothing | capitals, a full stop, tabs and line breaks, fields the model does not read, STORY vs feature, a sentence moved to the acceptance criteria | **Broken for whitespace.** Tabs and line breaks moved a range from 3–8 to 5–13 | **pass** |
| MR-06 | The same story twice gets the same answer | repeat | Held | **pass** |
| MR-07 | A team that delivered more is never predicted less | every past sprint +0 to +20 | Held | **pass** |
| MR-08 | More of the team present never lowers the forecast | availability 10% to 100% | **Broken.** A fast team got 30.2 at 10%, and 30.5 at 40% but 30.4 at 55% | **pass** (5.5, 11.0, 22.1, 30.4, …) |
| MR-09 | More leave, bugs or carried-over work never raises it | each varied alone | Held | **pass** |
| MR-10 | Sprints older than the last five change nothing but the count | three wild old sprints added | Held | **pass** |
| MR-11 | Below the training floor, the forecast is the model's answer × availability ÷ 55% | 5%, 20%, 40% availability | No scaling existed | **pass** |
| MR-12 | The same sprint twice gets the same answer | repeat | Held | **pass** |
| MR-13 | Below the history range, twice the record is twice the forecast | `[1, 2, 3]` against `[2, 4, 6]`, and two more pairs | **Broken.** Both got the model's answer for a 13-point team | **pass** |
| MR-14 | A team that delivered more is never forecast less, across both edges | averages 0 to 100 | **Plateaued** below 13 and above 54 | **pass** |
| MR-15 | A Story never shrinks as it grows | SprintIQ Story, 1 to 12 components, crossing the small-change line at 3 | — (the rule is new) | **pass** |

Three of these found something. MR-02 and MR-05 found two new defects (13 and 12
below). MR-08 confirmed, independently, the heavy-absence defect the edge-case tests
had found (9).

---

## Defects found and fixed

| # | Where | Defect | Fix | Cases |
|---|---|---|---|---|
| 1 | `features.py`, `velocity_features.py` | NaN, infinity and numbers too big for float32 were scored without being recorded; infinity also made XGBoost drop the driver list | `finite_number()` rejects them; they fall back to the median and are listed in `rejected_as_invalid` | SP-04/05/25, V-15/16/24 |
| 2 | both feature files | Impossible values (team of 0, negative counts) were scored as measurements | Treated as missing and recorded | SP-06, V-17 |
| 3 | `features.py` | A NaN flag read as **yes**; the string `"yes"` read as **no** | Yes/no words are read; anything unreadable is guessed from the text and recorded | SP-09 |
| 4 | `predictor.py` | Crossed P20/P80 heads collapsed the range to one value at confidence 1.0 | Heads are sorted before the conformal widening | SP-13 |
| 5 | `predictor.py` | Confidence ignored a point estimate outside its own interval (0.83 beside HIGH) | Confidence is measured on the range actually shown | SP-14 |
| 6 | `predictor.py` | A story at the top of the 1-13 scale was called "tight enough to plan against" | Explained as "13 or more", with a suggestion to split it | SP-15/16 |
| 7 | `velocity_features.py` | `avg_velocity_last5` marked as defaulted when supplied; a missing one replaced by the median, inventing a trend | Uses the caller's value, or the 3-sprint average | V-03/04 |
| 8 | `velocity_features.py` | Junk history entries counted as sprints; NaN or infinite entries poisoned the averages (45.6 points at 0.93 confidence) | Only finite, non-negative velocities count | V-18/19/20 |
| 9 | `velocity_features.py`, `velocity_predictor.py` | 0% availability predicted 22.5 points; anything below 57% predicted the same as 57% | Zero capacity reads as overloaded; below the 55% training floor the forecast is scaled by availability ÷ 0.55, and the response says so | V-10/11/12 |
| 10 | `velocity_predictor.py` | A team swinging between 5 and 60 got the same ±3.3 range as a steady team | Range is the wider of the model's error and the team's own spread over its last 5 sprints | V-07/08 |
| 11 | `velocity_predictor.py` | A brand-new team got MEDIUM at 0.60 confidence while the explanation said not to trust it | No history means label HIGH and confidence capped at 0.3 | V-01/02/19 |
| 12 | `features.py` | The length feature counted whitespace, so the same story pasted with tabs or line breaks got a different range (3–8 became 5–13) | Whitespace is collapsed before the text is measured | MR-05 |
| 14 | `features.py`, `StoryFeatureSource.java` | Every SprintIQ Story was sent as a "feature", so a small one started in the 5–13 population: a tooltip came back 8. "Export" in the wording counted as integration work | A Story or Task that is small by every measure is read as an enhancement; "export"/"import" in a sentence are no longer integration keywords | SP-41 to SP-45, MR-15 |
| 13 | `features.py`, `predictor.py` | A story bigger than any in the training data could not be recognised as one: 18 components read as 7, a senior team's epic came back as 8 "tight enough to plan against", and 7 external APIs went down XGBoost's "missing" branch and scored as none | Past the training maximums (7 components, 6 external APIs) the story is scored at those maximums, then shown as 13, "13 or more", HIGH, confidence ≤ 0.3, with the reason and "split it" | SP-29 to SP-34, MR-01/02 |

Nothing here retrains a model or changes a pickle. The pinned library versions in
`requirements.txt` are untouched.

---

## Where each input actually matters

Read from the split points of the deployed XGBoost point models (the story-point
interval heads are LightGBM and were not inspected). Outside these ranges a tree model
gives the same answer however far the value goes, which is why SP-17 and V-13 plateau.

| Model | Input | Range the model can tell apart |
|---|---|---|
| Story points | `num_components` | 2 to 6; trained on 1 to 7. Past 7 the guard takes over (fix 13) |
| Story points | `external_apis` | 2 to 6; trained on 0 to 6. Past 6 XGBoost read it as none, until the guard (fix 13) |
| Story points | `team_size` | 4 to 8 |
| Story points | `team_velocity_avg` | 11 to 33 |
| Story points | `task_age_days` | 2 to 51 |
| Velocity | `avg_velocity_last3` | 13 to 54; outside it the forecast follows the team's own record |
| Velocity | `developer_availability` | **0.57** to 1.00, hence fix 9 |
| Velocity | `leave_days` | 1 to 5 |
| Velocity | `team_size` | 4 to 9 |
| Velocity | `planned_story_points` | 14 to 98 |

---

## Known limitations (not fixed)

These need more data or a retrain, not code. Say them before the panel finds them.

1. **Nothing above 13 or below 1 was ever measured.** The deck now runs from 0.5 to
   100, and a story past the training data is extrapolated onto it — but that is an
   assumption stated in the answer, not something the data showed. A big story *inside*
   the training limits still gets the model's own number, up to 13, and the smallest
   answers stay around 2. Retraining on SprintIQ's own recorded estimates is what would
   change that.
2. **Short, vague stories are pulled towards 5.** That is the training median, and a
   five-word title gives the model little to go on (already noted in
   `ai-service/README.md`).
3. **Velocity outside 13-54 points is scaled, not learned.** A team slower or faster
   than any in the training data now follows its own record in proportion (V-28 to
   V-31), which is planning's own rule; the model itself still knows nothing about such
   teams beyond what the scaling assumes.
4. **Values past the training range plateau** (see the table above). That is safe,
   since nothing explodes, but "twice as big" stops making a difference. Two inputs
   are now handled past it: availability, which the service scales (capacity follows
   the people present), and a story's components and external APIs, which it flags.
   The model is also not strictly monotonic: 2 to 3 external APIs lowers its raw
   number by up to 0.1 (MR-03), invisible after snapping but a sign it learned
   without monotonic constraints. A retrain with them would remove it.
5. **The 55% availability floor** comes from the training set's range as recorded in
   the backend (`EstimationService.availability`). The trees agree: their lowest
   availability split is 0.57. It is still worth confirming against
   `Sprint_Velocity_Dataset_v3.csv`.
6. **The cold-start and volatility ranges are rules, not calibrated intervals.** The
   label and confidence are now honest about a new team, but its ±range is still the
   model's error on teams *with* history. Calibrating it properly means measuring the
   model's error on held-out sprints with the history features hidden.

---

## What to bring to the next presentation

1. **This table**, with the 208 passing cases, and the 59 edge-case and 15
   metamorphic failures on the old code as evidence the tests have teeth.
2. **The answer to "what about huge stories?"**: the dataset stops at 13, as agile
   practice does, so the system's job is to notice a story bigger than anything it
   learned and say "13 or more, split it" (SP-30, SP-33). Then show the metamorphic
   table: no answer key needed, only the right direction.
3. **A three-minute live demo in SprintIQ itself**, from the seeded demo projects
   described in [Showing it in SprintIQ](#showing-it-in-sprintiq): the steady team's
   normal sprint against its holiday sprint, the erratic team, the new team, and the
   epic-sized story. Each shows the number, the range, the confidence and the
   explanation in the product's own panels.
4. **Before and after for a few defects.** The strongest are V-18 (junk history gave
   45.6 points at 93% confidence), SP-13 (crossed interval heads gave 100%
   confidence) and SP-33 (a senior team's 18-component epic was called 8, "tight
   enough to plan against").
5. **The "where each input matters" table and the limitations list.** Naming them is
   stronger than being asked about them.
6. **One line on the method:** reject or fall back on bad input, widen the range when
   the model knows less, flag what is beyond what it learned, and always say which of
   those happened.

---

## Showing it in SprintIQ

`scripts/seed-m1-edge-cases.py` builds three demo projects through SprintIQ's own API,
the same way a user would, so every edge case appears in the normal product screens.
The malformed-input cases (NaN, infinity, negative counts) are not among them: the
product's forms and backend validation never let them through, which is itself the
answer to "what if someone enters nonsense". The tests cover the service directly.

### One-time setup, local Docker

The full Docker stack uses **mock** predictions unless told otherwise, and the model
service is not part of `docker compose`. Without these steps the panel sees invented
numbers, not your models.

1. In the repository root's `.env` (beside `JWT_SECRET`), add `AI_PROVIDER=http`.
2. Start the stack:
   ```bash
   docker compose up -d --build
   ```
3. Start the model service on your own machine, listening for the containers:
   ```bash
   cd ai-service && .venv/Scripts/python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000
   ```
4. Seed the demo and point the models at the host. Inside a container, `localhost` is
   the container itself, so the backend reaches your machine as `host.docker.internal`:
   ```bash
   ai-service/.venv/Scripts/python.exe scripts/seed-m1-edge-cases.py --ai-url http://host.docker.internal:8000
   ```
5. Sign in at http://localhost:3000 as `demo@sprintiq.test` (the local seed account).

Run the seed with `--check` once beforehand to print every answer the panel will see.
Those answers are then cached for six hours, and the panel marks them "cached". If
`--check` warns about the **mock** model, step 1 has not taken effect: rebuild the
backend container.

Seed the demo **once** per database. A second run makes a second set of projects
(keys `EDGNEW2`, `EDGERR2`, …) rather than failing.

**On Dilshan's laptop** two more things are in place, both in a local
`docker-compose.override.yml` that git does not track (see `.local-docker/README.md`):

- Norton 360 scans HTTPS with its own certificate, which Docker builds do not trust, so
  the build files there trust it in their build steps only.
- `docker-compose.yml` does not set `BACKEND_INTERNAL_URL` for the frontend, so in
  Docker the frontend's sign-in calls `localhost` — its own container — and fails with
  "The SprintIQ API is unreachable". The override sets it to `http://backend:8080/api/v1`.
  On any other machine, add that line to the frontend's `environment` in
  `docker-compose.yml` (better: fix it there for everyone).

For Render later, set `SPRINTIQ_API`, `SPRINTIQ_EMAIL` and `SPRINTIQ_PASSWORD` to the
deployed backend and your own account, and pass `--ai-url` with the Hugging Face
Space's address. The Space has to be rebuilt from this branch first, or it is still
running the code without these fixes.

### What to click, and what it should show

Numbers are from a real run on 16 October 2026: the seed script against the local
Docker stack, with the fixed models answering. The holiday sprint and the epic story
were also checked on screen in SprintIQ itself. `--check` prints them again for your run.

| Show | Where in SprintIQ | Expect | The point to make |
|---|---|---|---|
| Steady team, normal sprint | M1 Edge · Steady team → Sprints → *Normal sprint* → **Predict velocity** | **28.4**, likely 25–32, confidence 88% "Well supported" | The baseline: this is what a well-supported answer looks like |
| Same team, holiday sprint | same project → *Holiday sprint* → **Predict velocity** | **5**, likely 1.7–8.3, confidence 34% "Thin evidence", committed 32 vs predicted 5, **Over-committed**; explanation says 14% availability, so the model's 19 was scaled down | Before the fix the model's own answer, 19 points, was shown as it was: below 57% availability the trees cannot tell 50% from 5%. The service now scales it and says so |
| Erratic team | M1 Edge · Erratic team → Sprints → *Next sprint* → **Predict velocity** | **20.9**, likely **0–51**, confidence 0% "Thin evidence"; explanation says the sprints vary by about 30 points | Before the fix: ±3.3 and about 80% confidence. The range now admits what the history shows |
| New team | M1 Edge · New team → Sprints → *Sprint 1* → **Predict velocity** | **31.3**, likely 27–36, confidence 30% "Thin evidence"; "no completed sprints … a starting point, not a forecast", scored by the universal model (unknown domain) | Before the fix this case read about 60% "Moderately supported" |
| Epic-sized story | M1 Edge · Steady team → *Migrate the billing platform…* → **Suggest** | **20**, likely 13–40, HIGH, **about 25% "Thin evidence"**; "18 components touched (the most in its training data was 7) … extrapolated, not measured … Treat it as an epic, and split it" | Bigger than anything in the training data: the deck goes past 13, and the explanation says how and why. The same story for a senior team used to come back as 8, "tight enough to plan against" |
| Programme-sized story | same project → *Replatform checkout across every region* → **Suggest** | **40**, likely 20–100, HIGH; 36 components, "the model's own trend continued gives about 21, the biggest story it knows scaled in proportion gives about 49" | The deck climbs with the story: 20 for an epic, 40 for a programme of work, 100 for anything bigger, and every one is explained as an extrapolation |
| Sinhala title | same project → *ගෙවීම් පිටුවේ…* → **Suggest** | **2**, likely 1–3, confidence 17% "Thin evidence"; "did not recognise any of this story's wording" | The model only reads the English vocabulary it was trained on, and says so instead of pretending |
| Vague one-liner | same project → *Misc improvements* → **Suggest** | **5**, likely 2–8, confidence 15% "Thin evidence"; same "did not recognise" sentence | Nothing to read means low confidence, not a confident guess |
| Unknown domain | M1 Edge · New team → *Ingest machine telemetry…* → **Suggest** | **5**, likely 3–13, confidence 15%; "3 inputs were not supplied"; sizing line shows domain as typical | "manufacturing" is not one of the five trained domains; it is treated as blank, never filed under a wrong one |

A good order for the panel is normal sprint → holiday sprint (the contrast is the
whole story), then the erratic team, then the epic story.

---

## How to run

From `ai-service/`, with the pinned requirements installed. The HTTP cases also need
`httpx` and are skipped without it.

```bash
pip install -r requirements.txt pytest httpx
```

```bash
PYTHONPATH=. pytest -v tests/test_edge_cases_story_points.py tests/test_edge_cases_velocity.py tests/test_metamorphic.py
```

Then the whole suite. Point pytest at `tests/`, because a script under `training/` is
named like a test and fails to import outside its author's machine.

```bash
PYTHONPATH=. pytest -q tests
```

Each test's docstring starts with its ID (`[SP-13]`, `[V-07]`, `[MR-02]`), so a failure maps
straight to a row above.
