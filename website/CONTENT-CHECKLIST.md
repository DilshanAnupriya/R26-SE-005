# Content checklist

Everything the site needs from the team before the viva. Each line names the file to edit.
Numbers on the site come from `docs/portfolio/DESIGN.md` §2.3 and
`ai-service/models/graphs/*.json`; each member should check their own.

## Confirm (already on the site, please verify)

| What | Where | Who |
|---|---|---|
| Component titles (TAF wording) for M2, M3, M4 | `partials/header.html` menu, `about.html`, `components/*.html` | Jayasinghe, Athukorala, Hewaduwa |
| M1 figures: MAE 1.94 vs 3.11, 89.2% within one step, 70.3% / 89.7% coverage, velocity 3.31 / 2.92 | `index.html`, `components/estimation.html` | Cooray |
| M3 figures: 81.0% vs 66.6%, Gini 0.162 vs 0.208, overload 4.0% vs 8.0% | `index.html`, `components/allocation.html` | Athukorala |
| M4 figures: mid-sprint MAPE 28.63% vs 49.85%, planning 23.45% vs 25.65% | `index.html`, `components/cost.html` | Hewaduwa |
| M2 figures: over-budget AUC 0.896 (acc 82.8%, F1 0.722), requirement change AUC 0.786 (F1 0.459) | `index.html`, `components/risk.html` | Jayasinghe |
| "Trained on synthetic data" notes for M2, M3, M4 | data notes on the same pages | Each member |
| Literature survey wording and the comparison table | `domain.html` §01 | Group |
| References: check each against your thesis reference list | `domain.html` References | Each member |
| Milestone order and statuses (Done / In progress / Upcoming) | `milestones.html`, `index.html` "Where the project stands" | Group |
| Group contact e-mail (currently it22189530@my.sliit.lk) | `contact.html` (3 places) | Group |
| SLIIT general line +94 11 754 4801 and the Malabe address | `contact.html` | Group |
| Student e-mails (IT number @my.sliit.lk) | `about.html`, `contact.html`, `components/*.html` | Each member |

## Add (shown as "Pending" or "To be added" until then)

| What | Where | Who |
|---|---|---|
| Date and marks for each of the 9 milestones | `milestones.html` (`<dd class="tbc">`) | Group |
| PDFs: TAF, charter, 4 proposal reports, 2 checklists, paper, group report, 4 theses | `assets/docs/` + `documents.html` | Group |
| Slides: proposal, PP1, PP2, final | `assets/slides/` + `presentations.html` | Group |
| Demo video link | `presentations.html` (#demo) | Group |
| Portraits (4:5, same setup; see `docs/portfolio/IMAGE-PROMPTS.md` §5) | `assets/img/team/` + `about.html` | Each member |
| Achievements per member (awards, publications), if any | `about.html` | Each member |
| Product screenshots (story estimate, velocity, assignment, workload, cost, drivers, risk dashboard, advisor) | replace the wireframe slots in `components/*.html` "In the product" | Each member |
| Supervisor consent for names (and photos, if used) | `about.html`, footer | Group |
| Group phone number, if the module asks for one | `contact.html` | Group |

After any edit to `partials/`, run `node tools/sync-partials.mjs`. Before uploading, run
`node tools/check-links.mjs` and `du -sh .` (must stay under 20 MB).
