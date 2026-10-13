# SprintIQ research website — HTML/CSS build design

**Date:** 2026-10-10 · **Status:** approved by Dilshan

This adapts [`docs/portfolio/DESIGN.md`](../../portfolio/DESIGN.md) (concept, tokens, copy,
motion) to the campus constraints. Where this file and DESIGN.md disagree, this file wins.

## Constraints (from the campus brief)

- Academic information website about the research, separate from the product.
- Common structure: Home · Domain · Milestones · Documents · Presentations · About us · Contact us.
- Domain covers literature survey, research gap, research problem, objectives, methodology,
  technologies used.
- Milestones lists every assessment with details, date and marks, using a drop-down.
- Documents lists produced and pending documents (links only).
- Presentations keeps room for future presentations.
- About us: members, photographs, e-mail, achievements. Contact: phones, e-mails, general
  e-mail template.
- Upload limit 20 MB. Allowed technology: WordPress, HTML, CSS.

## Decisions

| Topic | Decision |
|---|---|
| Direction | Keep DESIGN.md's concept: night-to-dawn home page, S-Track, sprint clock, envelope results |
| Stack | Static HTML + one CSS design system + classic (non-module) JS. No build step, no framework |
| Libraries | Vendored in `website/assets/vendor/`: GSAP 3 + ScrollTrigger, Lenis, three.js r149 UMD |
| Fonts | Self-hosted WOFF2: Mona Sans (variable), Geist, Geist Mono |
| Offline | Every page works from `file://` (double-click `index.html`) and any static host |
| Shared chrome | `website/partials/{header,footer}.html` copied into pages by `website/tools/sync-partials.mjs` between marker comments; pages stay plain HTML |
| Active nav | `<body data-page="…">` + CSS, no JS needed |
| Images | Hand-made SVG/canvas illustrations, real M3 graphs from `ai-service/models/graphs`, photo/screenshot slots with monogram fallbacks |
| Pages | Campus 7 + `components/{estimation,allocation,cost,risk,copilot}.html` + `404.html` |
| Milestones drop-down | Nav "Milestones ▾" submenu + each milestone is a `<details>` panel (details, date, marks, deliverables) |
| Contact form | Builds a `mailto:` with a prefilled template; no server |
| Missing content | Shown as "Pending"/placeholder; tracked in `website/CONTENT-CHECKLIST.md` |
| Progressive enhancement | Without JS, WebGL or with reduced motion: all content visible, posters instead of 3D, no pinning |

## Simplifications from DESIGN.md

- 3D: one home canvas only (S-Track ribbon, cards, four gates, stars, chevrons). Glow is
  done with additive shells, not post-processing. The isometric Kanban and exploded stack are
  CSS 3D.
- No Next.js, MDX, R3F or TypeScript content model. Numbers live in the HTML; the checklist
  says where each one came from.
- No separate `/research` route: it is `domain.html`.

## Size budget

Vendor JS ≈ 0.8 MB, fonts ≈ 0.35 MB, images ≈ 1 MB. Target < 4 MB before PDFs; PDFs larger than
the remaining budget are linked from Google Drive instead of uploaded.

## Verification

Every page loaded in the browser at 1440 px and 390 px: no console errors, no broken internal
links, keyboard path through nav and menus, reduced-motion pass, `du -sh website` < 20 MB.
