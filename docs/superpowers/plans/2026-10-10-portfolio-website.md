# SprintIQ Research Website Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A static, offline-capable academic website for R26-SE-005 (SprintIQ) that meets the
SLIIT structure and the 20 MB / HTML-CSS constraint, with the night-to-dawn S-Track home page.

**Architecture:** Plain HTML pages share one CSS design system (`core.css`, `components.css`)
and classic JS (`site.js` for every page, `home.js` + `strack.js` for the home page only).
Shared header/footer are copied into each page by a zero-dependency Node script. Vendored
libraries and fonts make every page work from `file://`.

**Tech Stack:** HTML5, CSS (custom properties, `color-mix`, container-free grid), vanilla JS,
GSAP 3 + ScrollTrigger, Lenis, three.js r149 UMD, Node ≥ 18 for the two tool scripts.

Spec: [`docs/superpowers/specs/2026-10-10-portfolio-website-design.md`](../specs/2026-10-10-portfolio-website-design.md)
Source of truth for copy, numbers and tokens: [`docs/portfolio/DESIGN.md`](../../portfolio/DESIGN.md)

---

## File map

```
website/
  index.html                     Home: hero + S-Track, problem, platform, 4 chapters, copilot,
                                 horizon, results, how it's built, research snapshot, team, CTA
  domain.html                    Literature survey, gap, problem, objectives, methodology, tech
  milestones.html                Timeline of <details> panels (details, date, marks, deliverables)
  documents.html                 Filterable document cards (Available / Pending)
  presentations.html             Presentation cards with SVG covers + future slots
  about.html                     Members, supervisors, achievements
  contact.html                   Contacts + mailto e-mail template form
  404.html                       "This card isn't on the board."
  components/{estimation,allocation,cost,risk,copilot}.html   Deep-dive template
  partials/header.html           Nav (logo, links, Components ▾, Milestones ▾, theme toggle, menu)
  partials/footer.html           Footer
  tools/sync-partials.mjs        Copies partials between <!-- header:start/end --> markers;
                                 rewrites relative paths for /components/*
  tools/check-links.mjs          Fails on any broken internal href/src
  assets/css/core.css            Fonts, tokens, reset, typography, layout, nav, footer, buttons
  assets/css/components.css      Envelope, chips, cards, timeline, pipeline, charts, forms, reveals
  assets/css/home.css            Home-only: hero, sprint clock, chapters, horizon, dawn
  assets/js/site.js              Nav, menus, theme, reveals, counters, filters, form, pipeline
  assets/js/home.js              Lenis, ScrollTrigger choreography, sprint clock, dawn
  assets/js/strack.js            three.js S-Track scene (exposes window.STrack)
  assets/vendor/                 gsap.min.js, ScrollTrigger.min.js, lenis.min.js, three.min.js
  assets/fonts/                  mona-sans.woff2, geist.woff2, geist-mono.woff2
  assets/img/                    logo, M3 graphs, SVG illustrations, covers, og image
  CONTENT-CHECKLIST.md           Everything the team must add or confirm
  README.md                      How to edit, sync partials, check links, deploy, upload
```

## Tasks

### Task 1: Scaffold and vendor
- [ ] Create the folder tree above.
- [ ] Download vendor JS and fonts into `assets/vendor` and `assets/fonts`; record versions in README.
- [ ] Copy the logo and the four M3 graphs into `assets/img`.
- [ ] Verify: `ls -la website/assets/vendor website/assets/fonts` shows non-zero files.

### Task 2: Design system CSS
- [ ] `core.css`: `@font-face`, DESIGN.md §4 tokens (colour, type scale, Fibonacci spacing,
      radii, z-index, motion), night/day semantic mapping via `[data-light]`, reset, type,
      12-col layout helpers, nav, mobile sheet, footer, buttons, skip link, focus ring.
- [ ] `components.css`: envelope (§11.3), chips, cards, document/presentation/team cards,
      timeline, pipeline rail, charts, form, reveal classes, reduced-motion overrides.

### Task 3: Partials and sync script
- [ ] Write `partials/header.html` and `partials/footer.html` using `{{root}}` for relative paths.
- [ ] `tools/sync-partials.mjs`: for every `*.html`, replace content between markers with the
      partial, `{{root}}` → `""` or `"../"` by depth. Idempotent.
- [ ] Verify: run twice; second run reports 0 changes.

### Task 4: site.js
- [ ] Nav compress on scroll, dropdowns (click + Esc + outside click), mobile sheet with focus
      trap, theme toggle stored in `localStorage` (try/catch), reveal-on-scroll via
      IntersectionObserver, number counters, document filters, pipeline stage expand,
      contact form → `mailto:`, current year.
- [ ] Without JS: menus fall back to visible links in the footer; all content visible.

### Task 5: Inner pages
- [ ] Domain, Milestones, Documents, Presentations, About, Contact, 404 with real copy from
      DESIGN.md §2 and §8; placeholders marked "Pending".
- [ ] Five component deep-dive pages using the §7 template, each with its pipeline and results.

### Task 6: Home page (no 3D)
- [ ] All sections from DESIGN.md §6 with SVG posters, envelopes, charts and CSS 3D stack.
      This is also the reduced-motion / no-WebGL experience.

### Task 7: S-Track and choreography
- [ ] `strack.js`: ribbon tube with gradient shader, glow shell, cards riding the curve, four
      gates, stars, chevrons, camera on a path driven by `STrack.setProgress(p)`, dawn via
      `STrack.setDawn(d)`, pauses off-screen / hidden tab, disposes on WebGL failure.
- [ ] `home.js`: Lenis + ScrollTrigger, sprint clock, chapter progress → scene, `--dawn`, the
      `data-light` flip at the horizon, copilot chat typing, count-ups.

### Task 8: Content checklist and README
- [ ] `CONTENT-CHECKLIST.md` lists every placeholder with its file and what to add.
- [ ] README: edit workflow, partial sync, link check, deploy (Vercel / GitHub Pages / course web zip).

### Task 9: Verify
- [ ] `node website/tools/check-links.mjs` → 0 broken links.
- [ ] Serve `website/` locally, open every page at 1440 px and 390 px; zero console errors.
- [ ] Keyboard: Tab through nav, open/close both dropdowns with Enter/Esc, mobile menu.
- [ ] Emulated reduced motion: home renders posters, no pinning.
- [ ] `du -sh website` < 20 MB.
