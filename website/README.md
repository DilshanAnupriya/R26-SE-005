# SprintIQ research website (R26-SE-005)

The academic information website for the SprintIQ research project: domain, milestones,
documents, presentations, team and contact. It is plain HTML, CSS and JavaScript with no
build step, so it can be uploaded to the course web as it is (limit 20 MB, HTML/CSS only).

The design is specified in [`docs/portfolio/DESIGN.md`](../docs/portfolio/DESIGN.md); this
build follows it with the changes recorded in
[`docs/superpowers/specs/2026-10-10-portfolio-website-design.md`](../docs/superpowers/specs/2026-10-10-portfolio-website-design.md).

## View it

The most reliable way, and the one to use at the viva, is a local web server:

```bash
python3 -m http.server 8000 --directory website
```

Then open <http://localhost:8000>. Double-clicking `index.html` also works, but some browsers
block the web fonts on `file://` pages and fall back to system fonts.

**Slow laptop or projector?** Open `index.html?lite`. It turns off smooth scrolling and every
animation and shows the finished drawings instead. Visitors whose system asks for reduced motion
get the same version automatically. There is no WebGL anywhere on the site, so nothing depends
on the graphics card.

**Light and dark.** The site starts in the light theme; the sun/moon button switches every
page, and the choice is remembered. The home page's hero and chapter drawings follow the theme
(white and blue in light, black and blue in dark). In the dark theme the page dawns into
daylight at the "Day 10" band.

**The hero.** An animated SVG network (inline in `index.html`): SprintIQ at the centre, the four
models (Estimation, Allocation, Cost, Risk) as solid blue hexagons and Scrum, Kanban, the backlog
and the Copilot as outlined ones. On the first visit of a session the connections draw out from
the centre and the nodes settle in; after that small pulses keep travelling along the
connections (they pause when the hero is off screen). Styles: "Hero" and "The SprintIQ network"
in `assets/css/home.css`; animation: section 8 of `assets/js/home.js`.

**The chapters.** Each model's section has its own inline SVG drawing, which builds when it
scrolls into view: M1 sizes a story on the Fibonacci scale with its range, M3 ranks developers,
M4 shows a cost gauge and forecast, M2 scores three risks with a live trend. They are generated
in one go; edit them in `index.html` (inside each `figure.chapter__poster`), styles under
"Chapter illustrations" in `assets/css/home.css`. The Kanban board ("One board. Four models
underneath.") is CSS 3D and still moves as you scroll.

**Unused files.** `assets/vendor/three.min.js`, `assets/js/scene-kit.js`, `assets/js/scenes/*`
and `assets/img/illus/strack-poster.svg` are no longer used anywhere and can be deleted to save
space. Keep `assets/img/illus/gate-*.svg`: the component pages and the presentations page still
use them.

**Colours.** White, black and blue, with one colour per model: M1 blue, M3 emerald, M4 cyan,
M2 amber. The tokens are at the top of `assets/css/core.css` (`--m1` … `--m4`). There is no
purple anywhere; keep it that way when adding illustrations.

## What's where

```
index.html               Home: the sprint, rendered (3D S-Track, chapters, results)
domain.html              Literature survey, gap, problem, objectives, methodology, technologies
milestones.html          Every assessment as an expandable panel
documents.html           Documents, filterable by owner and type
presentations.html       Slides and the demo video
about.html               Members, supervisors, achievements
contact.html             Contacts, e-mail form (opens the visitor's mail app), e-mail template
components/*.html        One deep-dive page per model: estimation (M1), allocation (M3),
                         cost (M4), risk (M2), and the Copilot
404.html                 Not-found page
partials/                The shared head, header and footer (edit these, then sync)
assets/css/              core.css (tokens, layout, nav), components.css, home.css
assets/js/               site.js (every page); home.js, scene-kit.js and scenes/*.js (home only)
assets/vendor/           GSAP 3.15 + ScrollTrigger, DrawSVG, MotionPath; Lenis 1.3.26; three.js r149
assets/fonts/            Mona Sans, Geist, Geist Mono (WOFF2)
assets/img/              Logo, illustrations, presentation covers, M3 result graphs
tools/                   sync-partials.mjs, check-links.mjs (Node 18+)
CONTENT-CHECKLIST.md     Everything the team still has to add or confirm
```

## Edit it

**Shared header, footer or `<head>`.** Edit the file in `partials/`, then copy it into every
page:

```bash
node website/tools/sync-partials.mjs
```

Each page marks the shared blocks with comments such as `<!-- header:start -->` and
`<!-- header:end -->`; anything between them is replaced. Edit the rest of each page directly.

**Check links before you upload.**

```bash
node website/tools/check-links.mjs
```

It fails if any page points to a missing file or a missing `#section`.

**Add a document.** Save the PDF as `assets/docs/R26-SE-005_<Owner>_<Document>.pdf`. In
`documents.html`, find its card, change `data-status="pending"` to `data-status="available"`,
change the chip to `<span class="chip chip--available">Available</span>`, and replace the
"Not uploaded yet" button with:

```html
<a class="btn btn--primary btn--sm" href="assets/docs/R26-SE-005_Group_Charter.pdf" download>Download PDF</a>
<a class="btn btn--sm" href="assets/docs/R26-SE-005_Group_Charter.pdf" target="_blank" rel="noopener">Preview</a>
```

Large files count against the 20 MB limit. If the PDFs don't fit, upload them to Google
Drive and link to the shared file instead.

**Add slides.** Same pattern in `presentations.html`, using `assets/slides/`.

**Add a portrait.** Save a 4:5 photo as `assets/img/team/<surname>.jpg` (for example
`cooray.jpg`). In `about.html`, the member's card has a comment with the exact `<img>` line
to paste in. Use the same crop and lighting for all four.

**Add milestone dates and marks.** In `milestones.html`, replace each
`<dd class="tbc">To be added</dd>` with the value, for example `<dd>12 March 2026</dd>`.
Update the status (`data-status="done"`, `"now"` or `"upcoming"`) and the chip to match.

## Publish it

- **Course web:** zip the `website` folder's contents and upload them. Check the size first:
  `du -sh website`.
- **Vercel:** import the repository and set the root directory to `website`. There is no
  build command and no output directory.
- **GitHub Pages:** publish the `website` folder (for example with a Pages workflow that
  uploads it as the artifact).

## Credits

Fonts: Mona Sans (GitHub) and Geist / Geist Mono (Vercel), both under the SIL Open Font
License. Libraries: GSAP and ScrollTrigger (GreenSock standard license, free), Lenis (MIT),
three.js (MIT). All illustrations were drawn for this site; the M3 result graphs come from
`ai-service/models/graphs`.
