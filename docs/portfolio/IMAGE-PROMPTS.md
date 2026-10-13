# SprintIQ Research Portfolio — Image Brief and AI Prompts

> Every image the portfolio needs, with where it goes, what it must say, and a ready-to-paste
> prompt. Image IDs (`IMG-xx`, `SHOT-xx`) match [`DESIGN.md`](DESIGN.md).
>
> **One rule above all: no text in generated images.** AI tools garble letters and numbers.
> Every label, number and heading on the site is HTML, laid over the image.

---

## 1. How to work

1. **Generate `IMG-01` (the hero) first.** Iterate until it's right: correct S direction,
   chevrons at the top right, calm left side. It becomes the **style anchor**.
2. Generate every other image using `IMG-01` as a **style reference**
   (Midjourney `--sref`, or "use this image as a style reference" in ChatGPT, Ideogram or
   Firefly). This keeps the whole set looking like one shoot.
3. Make 4–8 variations of each image and pick by the checklist in §9, not by first impression.
4. Post-process every pick (§8): grade to the palette, remove any stray letters, check crops.
5. Log every final image (tool, prompt, seed, date) in a spreadsheet. You need this for the
   AI-use disclosure in your theses and the site footer.

### Which tool for what

| Tool | Best for | Settings |
|---|---|---|
| Midjourney v7 | Hero and all glass/3D scenes (best material quality) | `--ar` as listed · `--style raw` · `--stylize 150–250` · `--sref <IMG-01 URL>` once you have it · keep the `--seed` of good results |
| ChatGPT (GPT image) | Precise compositions ("empty left third", exact object counts) and edits on a picked image | State the aspect ratio in words; ask for edits in a follow-up message ("make the haze more amber") |
| Ideogram / Flux | Alternative takes; good prompt adherence | Turn **off** "magic prompt" so it doesn't add text |
| Adobe Firefly | Anything that needs the cleanest commercial-use licence | Use "Style reference" upload with `IMG-01` |
| Runway / Kling / Veo / Luma | Optional short loops from finished stills (§7) | Image-to-video, 5–6 s, slow camera |

Check each tool's terms for academic and public use before publishing. On some plans you
only own outputs from paid accounts.

---

## 2. The style lock

Paste one of these at the **end of every prompt**. The night lock is for the home page's
3D-world images; the day lock is for the evidence pages (research, milestones, documents).

### 2.1 Night style lock

```
premium minimalist 3D render, frosted and clear glass forms with soft inner glow,
deep midnight indigo environment (#0A0A1C), electric indigo (#5B5CF0) and violet (#8B5CF6)
light, small precise accents of luminous aqua-mint (#19E6C1), soft volumetric haze,
gentle rim lighting, physically based materials, subtle film grain, shallow depth of field,
calm, precise, generous negative space, high-end technology key visual, ultra detailed
```

### 2.2 Day style lock

```
premium minimalist 3D render, frosted and clear glass forms, soft diffused morning daylight,
cool pale lavender-white background (#F3F4FA), soft long shadows, small accents of
electric indigo (#5B5CF0) and violet (#8B5CF6), a rare touch of aqua-mint (#19E6C1),
physically based materials, airy, editorial, calm, generous negative space, ultra detailed
```

### 2.3 Negative prompt (use on every image)

For Midjourney, add it as `--no …`; for other tools, add *"Avoid: …"* at the end.

```
text, letters, numbers, words, typography, logo, watermark, signature, UI screenshot,
dashboard, people, faces, hands, robot, brain, circuit board, binary code, sci-fi HUD,
cyberpunk city, neon signs, rainbow colours, pink, orange sunset, lens flare, chromatic
aberration, cartoon, low poly, plastic toy, clutter, busy background, pure black
```

### 2.4 Visual vocabulary (use these words consistently)

| Thing in the story | Describe it as |
|---|---|
| The S-Track | "a thin flat ribbon of translucent violet glass shaped like a flowing letter S, like a race track" |
| Backlog cards | "small rectangular frosted-glass cards, like index cards, with a thin glowing top edge" |
| Shipped | "three sharp aqua-mint chevron shapes pointing up and to the right" |
| A model at work | the gate description for that image (§4) |
| Uncertainty | "soft haze", "fog", "faint outline" |
| Evidence and certainty | "crisp edges", "clear glass", "daylight" |

---

## 3. Image list at a glance

Priority: **P1** = needed for launch, **P2** = needed for the full site, **P3** = nice to have.

| ID | Name | Used on | Ratio(s) | Style | Priority |
|---|---|---|---|---|---|
| IMG-01 | The S-Track (hero key visual) | Home hero poster, OG image, video poster | 16:9, 9:16, 1.91:1 | Night | P1 |
| IMG-02 | Fog of guesses | Home: Problem backdrop | 16:9 | Night | P2 |
| IMG-03 | Glass Kanban board | Home: Platform fallback | 4:3, 1:1 | Night | P2 |
| IMG-04 | Exploded stack | Home: How it's built fallback | 4:3, 1:1 | Night | P2 |
| IMG-05 | Sizing gate (M1) | M1 chapter poster, `/components/estimation` header | 16:10, 21:9, 4:5 | Night | P1 |
| IMG-06 | Velocity river (M1) | M1 velocity poster, deep-dive section | 16:10, 21:9 | Night | P1 |
| IMG-07 | Weather (M2) | M2 chapter poster, `/components/risk` header | 16:10, 21:9, 4:5 | Night | P1 |
| IMG-08 | Loom (M3) | M3 chapter poster, `/components/allocation` header | 16:10, 21:9, 4:5 | Night | P1 |
| IMG-09 | Meter (M4) | M4 chapter poster, `/components/cost` header | 16:10, 21:9, 4:5 | Night | P1 |
| IMG-10 | Conductor (Copilot) | Copilot poster, `/copilot` header | 16:10, 21:9 | Night | P1 |
| IMG-11 | Horizon (sprint dawn) | Home: Horizon band (theme flip) | 21:9, 9:16 | Night → day | P1 |
| IMG-12 | The missing step (research gap) | `/research` header, home research card | 21:9, 4:3 | Day | P2 |
| IMG-13 | Design Science loop | `/research#methodology` backdrop | 16:9 | Day | P2 |
| IMG-14 | Stepping stones | `/milestones` header | 21:9 | Day | P2 |
| IMG-15 | Floating papers | `/documents` header | 21:9 | Day | P2 |
| IMG-16 | Portrait backdrop plate | `/team`: behind cut-out real portraits | 4:5 | Day | P2 |
| IMG-17 | Launch (chevrons) | `/contact`, footer accent | 16:9, 1:1 | Dusk | P2 |
| IMG-18 | Off the track | `/404` | 4:3 | Night | P3 |
| IMG-19 | Film grain tile | Night sections overlay | 1:1 tileable | — | P3 |
| IMG-20 | Presentation covers ×5 | `/presentations` cards | 16:9 | Night | P3 |
| IMG-21 | Studio environment map | 3D reflections (optional) | 2:1 equirectangular | Night | P3 |

---

## 4. Prompts

Each entry gives: **purpose** (what the viewer should feel or understand), **composition**
(where the empty space for HTML text must be), the **prompt** (append the style lock and
negative prompt), **crops**, and **alt text** for the site.

---

### IMG-01 · The S-Track (hero key visual)

**Purpose.** This is the whole concept in one frame: work enters as a backlog, flows along
SprintIQ's "S", and launches out as shipped. It must read as *the logo, made into a world*.

**Composition (16:9).** The S fills the right 60%, seen in a three-quarter view from slightly
below. The **left 40% is calm, dark, empty space** for the headline. The chevrons sit near the
top-right corner but don't touch the edge.

**Prompt.**
```
A single continuous ribbon of translucent violet glass shaped like a large flowing letter S,
floating in a deep midnight-indigo void, seen in three-quarter view from slightly below. The
ribbon is thin and flat like a race track. A fine line of light runs along its centre,
shifting from indigo at the bottom tail to aqua-mint at the top. Small rectangular
frosted-glass cards, like index cards, glide along the ribbon in a loose, evenly spaced
procession, each with a thin glowing top edge. At the top-right end of the S, three sharp
aqua-mint chevron shapes burst forward and upward, glowing softly, as if the cards are
launching out of the track. Faint distant stars, soft haze. The left 40% of the frame is
calm, empty, dark space.
[night style lock]
```

**Crops.**
- 9:16 (mobile): the S centred in the upper 60%, with the bottom 40% dark and empty for text.
  Re-generate rather than crop.
- 1.91:1 (Open Graph, 1200×630): the S on the right half, the left half empty for the title.

**Alt text.** *"A glass ribbon shaped like the SprintIQ S, carrying backlog cards from its
tail to three glowing chevrons that launch them out."*

**Check.** It's a real S (not mirrored "Ƨ"); flip horizontally if needed. The chevrons point
**up and right**, and nothing reads as letters.

---

### IMG-02 · Fog of guesses

**Purpose.** Sprint planning before SprintIQ: unsized work, uncertainty, nothing ordered yet.

**Composition.** The cards fill the right and centre. The top-left is darkest, for the section
title.

**Prompt.**
```
Dozens of blank frosted-glass index cards drifting loosely in a dark indigo fog, unordered,
at different depths and angles, some half-hidden in haze, a few casting long soft shadows.
A single thin beam of cool violet light enters from the right edge and catches the nearest
three cards, suggesting order about to arrive. Quiet, uncertain mood. The top-left area is
the darkest and emptiest.
[night style lock]
```

**Alt text.** *"Blank glass cards drifting in fog, with one beam of light starting to reach
them."*

---

### IMG-03 · Glass Kanban board

**Purpose.** SprintIQ is a real Agile tool: a board with lanes, and AI attached to the work.

**Composition.** A true isometric view with the board centred and margin on all sides.

**Prompt.**
```
Isometric view of a minimalist Kanban board made of four shallow rectangular glass trays
placed side by side on a dark indigo surface, each tray a lane. Small frosted-glass cards
rest in the trays; two cards are frozen mid-hop in small arcs between lanes. Tiny glowing
pill-shaped tags sit on some cards in indigo, violet and aqua-mint. The rightmost tray's
cards have a faint aqua-mint glow. Clean orthographic isometric camera, slight tilt-shift
softness at the edges.
[night style lock]
```

**Alt text.** *"An isometric glass Kanban board with four lanes; cards move between lanes and
carry small glowing tags."*

---

### IMG-04 · Exploded stack

**Purpose.** How SprintIQ is built: four layers that talk to each other.

**Composition.** The stack slightly right of centre, three-quarter isometric view. The left
third is empty.

**Prompt.**
```
Four thin rectangular slabs of frosted glass stacked vertically with wide even gaps between
them, floating in an exploded technical view, three-quarter isometric angle. Fine vertical
threads of light connect the slabs, and small glowing aqua-mint spheres travel along the
threads like data packets. The top slab is cool indigo glass, the second violet, the third a
deeper violet with a faint engraved grid, and the bottom slab is dense, darker and heavier,
like a foundation. Clean and architectural.
[night style lock]
```

**Alt text.** *"An exploded stack of four glass layers connected by threads of light, with
small spheres passing between them."*

---

### IMG-05 · Sizing gate (M1: story point estimation)

**Purpose.** A story being measured: sized on the Fibonacci scale, with an honest range (the
brackets) around it.

**Composition.** The card and gate on the right two thirds; the track curves away left into
darkness, leaving space top-left.

**Prompt.**
```
A single frosted glass index card passing through an arch made of six glass tiles arranged
on a curve, the tiles growing in size from small to large in a golden-ratio progression. A
soft vertical scanning beam of violet light passes through the card. Two thin glowing violet
bracket shapes stand on either side of the card like the jaws of a measuring caliper,
framing it. The card rides on a thin translucent violet glass ribbon track that curves away
into darkness. Precise, scientific, elegant.
[night style lock]
```

**Crops.** 16:10 (chapter poster) · 21:9 (deep-dive header; subject on the right) ·
4:5 (mobile).

**Alt text.** *"A glass card measured inside an arch of six growing tiles, framed by two
light brackets showing its range."*

---

### IMG-06 · Velocity river (M1: sprint velocity)

**Purpose.** Capacity as a flow rate. The ghost stream is what the team committed; the
overflow in amber is over-commitment.

**Composition.** The channel runs diagonally from bottom-left to top-right, with empty space
top-left.

**Prompt.**
```
A narrow translucent glass channel curving gently through dark space, carrying a dense stream
of tiny glowing indigo particles flowing forward like a river of light. Beside it runs a
fainter parallel ghost stream of hollow outline particles. At one point the main stream
overflows its banks and the spilled particles glow a warm amber. Long-exposure feel, strong
sense of rate and flow.
[night style lock]
```

**Alt text.** *"A stream of light particles flowing through a glass channel beside a fainter
ghost stream; some particles spill over in amber."*

---

### IMG-07 · Weather (M2: risk prediction)

**Purpose.** Risk as weather moving in during the sprint: clear, then amber, then a red pulse
around one piece of work.

**Composition.** The track rises from the bottom centre to the top right; the haze layers
stack vertically. The left third is calmer.

**Prompt.**
```
A curved ribbon of translucent violet glass rising upward through layered atmospheric haze.
Frosted glass cards travel up the ribbon. The lowest haze layer is clear and cool, the middle
layer is a warm amber mist, and near the top a soft red glow pulses around one single card.
Small drifting motes of light settle onto the ribbon like weather. Gentle volumetric light
shafts from above. Dramatic but calm.
[night style lock]
```

**Note.** Keep the red soft and limited to one card. It should read as a warning, not danger
everywhere. If the tool adds orange-pink skies, add "no sunset" to the negatives.

**Alt text.** *"Glass cards climbing a ribbon through clear, then amber, haze; one card near
the top glows red."*

---

### IMG-08 · Loom (M3: resource allocation)

**Purpose.** Choosing who takes the work, fairly. Five people (abstract orbs, **never faces**),
one strong connection, workload shown as halos.

**Composition.** The card at left-centre and the orbs in an arc on the right. Space above.

**Prompt.**
```
A single frosted glass card resting on a violet glass ribbon track. Five luminous spheres
float in a gentle arc beside it, each surrounded by a soft halo of a different size. Fine
threads of light stretch from the card toward each sphere like the strings of a loom: one
thread is bright violet and taut, the other four are faint and fading. Precise, balanced,
elegant.
[night style lock]
```

**Alt text.** *"A glass card connected by threads of light to five glowing spheres; one
thread is bright and taut."*

---

### IMG-09 · Meter (M4: cost forecasting)

**Purpose.** A precise instrument showing spend so far, the original forecast, and the
reforecast.

**Composition.** The ring centred-right and slightly tilted (about 20°), with the track
passing through it. Empty left third.

**Prompt.**
```
A large circular ring made of many small segmented glass ticks, like the dial of a precision
instrument, floating in dark space and tilted slightly toward the camera. A thin violet glass
ribbon track passes straight through the centre of the ring, and a frosted glass card is
passing through. About half of the ring's ticks glow solid indigo. Just outside them, a thin
violet arc of light marks a slightly different extent, and a faint hollow outline arc shows a
third extent. Clean, mechanical, exact.
[night style lock]
```

**Alt text.** *"A dial-like ring of glass ticks around a track, partly lit, with two arcs
marking different forecast lengths."*

---

### IMG-10 · Conductor (Copilot)

**Purpose.** One assistant coordinating all four models: threads of light from a single
source to four different instruments.

**Composition.** The orb at upper centre and the S-ribbon below with four distinct structures.
Speech panes are blank.

**Prompt.**
```
A small luminous orb of pale violet-white light hovering above a flowing glass S-shaped
ribbon. Four fine threads of light extend down from the orb to four distinct glowing
structures placed along the ribbon: a curved arch of glass tiles, a cluster of small glowing
spheres, a segmented glass ring, and a patch of amber haze. Three blank translucent glass
panes float near the orb like speech bubbles with nothing written on them. Orchestral, calm,
connected.
[night style lock]
```

**Alt text.** *"A glowing orb above the S-track sending threads of light to four different
structures along it."*

---

### IMG-11 · Horizon (sprint dawn)

**Purpose.** The moment the page turns from night to day: sprint review. It covers the screen
while the theme flips, so its **centre must be pale and calm**.

**Composition (21:9).** The S-track's top end and chevrons sit in the lower-right quarter,
seen from behind. The sky fills the rest. The central horizontal band is the lightest area.

**Prompt.**
```
Ultra-wide cinematic view from behind the top end of a glass S-shaped ribbon. Frosted cards
launch off its end through three aqua-mint chevrons into a vast sky at the exact moment of
sunrise. The sky transitions smoothly from deep midnight indigo at the very top to pale cool
lavender-white at the horizon, with the last stars fading. The middle band of the image is
soft, bright and calm. No sun disc, no orange; the dawn light is cool white with a hint of
violet.
[night style lock] — then replace "deep midnight indigo environment" with "night sky turning
to cool dawn"
```

**Crops.** 21:9 desktop; 9:16 mobile (re-generate: chevrons bottom-right, sky above).

**Alt text.** Decorative (`alt=""`). The section's text says "Day 10 · Sprint review".

---

### IMG-12 · The missing step (research gap)

**Purpose.** Existing research builds upward, but one step is missing, and that gap is where
SprintIQ fits.

**Composition.** The staircase rises left to right across the lower half. The top half is
pale and empty for the page title.

**Prompt.**
```
A staircase of floating translucent glass sheets, like pages of research papers, each step
slightly higher, ascending from left to right. One step near the top is missing, leaving a
clear gap, and a single violet glass card hovers precisely in that gap, glowing softly,
completing the staircase. The upper half of the image is empty pale background.
[day style lock]
```

**Alt text.** *"Glass sheets forming a rising staircase with one missing step, filled by a
glowing violet card."*

---

### IMG-13 · Design Science loop (methodology backdrop)

**Purpose.** The research method as a cycle. The six stage names are added in HTML around it.

**Composition.** The loop centred, with room around each ring for a label. Keep it simple.

**Prompt.**
```
Six thin glass rings arranged evenly around a large circular path, each ring tilted slightly
toward the viewer, connected by one fine continuous line of light that circles through all
six. One ring glows softly indigo as if active. Wide empty space around each ring.
Minimal, diagrammatic, elegant.
[day style lock]
```

**Alt text.** Decorative (`alt=""`); the HTML diagram labels carry the meaning.

---

### IMG-14 · Stepping stones (milestones)

**Purpose.** Progress over time: completed milestones glow, upcoming ones wait.

**Composition.** The path rises gently from the lower left into the distance at the right.
The upper 55% is sky.

**Prompt.**
```
A gently rising path of small square glass plinths stepping into the distance like stepping
stones across a pale, smooth surface. The nearest five plinths have a faint aqua-mint glowing
edge as if completed; the farther plinths are clear and unlit. Soft morning light, long
gentle shadows, wide composition with empty pale sky above.
[day style lock]
```

**Alt text.** *"A path of glass stepping stones; the nearest ones glow as completed, the
farther ones wait."*

---

### IMG-15 · Floating papers (documents)

**Purpose.** The written work, light and orderly.

**Composition.** The papers on the right half; the left half pale and empty.

**Prompt.**
```
A loose fan of thin frosted-glass sheets and slim glass folders floating at slight angles in
soft daylight, gently overlapping, one sheet lifted slightly higher with a violet glowing
edge. Clean, orderly, editorial. Left half of the image is empty pale background.
[day style lock]
```

**Alt text.** Decorative (`alt=""`).

---

### IMG-16 · Portrait backdrop plate (team)

**Purpose.** One identical backdrop behind all four real portraits, so photos taken in
different places look like one shoot.

**Prompt.**
```
Seamless photography studio backdrop, smooth vertical gradient from cool pale lavender-grey
at the top to slightly deeper soft slate-violet at the bottom, very soft vignette, faint
film grain, completely empty, no objects, no shadows.
```

**Size.** 2000×2500 (4:5). See §5 for how to combine it with the real photos.

---

### IMG-17 · Launch (contact, footer)

**Purpose.** The end of the sprint loop: shipped. Energetic but clean, an invitation to get
in touch.

**Composition.** The chevrons in the right half, launching up-right. The left half is calm dusk.

**Prompt.**
```
Three sharp chevron shapes of luminous aqua-mint glass launching diagonally up and to the
right out of the end of a violet glass ribbon, leaving faint soft light trails behind them,
against a dusk sky moving from soft indigo near the horizon to deep night at the top. Clean,
energetic, uplifting. Left half calm and empty.
[night style lock]
```

**Alt text.** *"Three glowing chevrons launching up and to the right from the end of a glass
track."*

---

### IMG-18 · Off the track (404)

**Purpose.** A friendly "this page isn't here".

**Prompt.**
```
A single frosted glass index card lying slightly tilted on a smooth dark surface, just beside
a curving violet glass ribbon track, as if it slid off the track. A soft spotlight falls on
the card. Quiet, gently humorous, lots of empty space around it.
[night style lock]
```

**Alt text.** *"A glass card that has slid off its track."*

---

### IMG-19 · Film grain tile

**Better made procedurally** (a CSS SVG `feTurbulence` filter or a shader) than generated. If
you want an image:

```
Seamless tileable fine monochrome film grain noise texture, neutral mid-grey, completely
uniform, no visible pattern or repetition, no objects.
```

Export at 512×512 PNG; use it at 3–4% opacity with `mix-blend-mode: overlay`.

---

### IMG-20 · Presentation covers (×5)

One template, one variable. These are covers for the slide-deck cards on `/presentations`.
The titles are HTML.

```
A close, cropped detail of a translucent violet glass S-shaped ribbon track in dark space,
{STAGE}. Empty left half for a title.
[night style lock]
```

| Presentation | `{STAGE}` |
|---|---|
| Topic Assessment / Proposal | "a single blank frosted card waiting alone at the very start of the track" |
| Progress Presentation 1 | "a few cards entering a curved arch of glass tiles" |
| Progress Presentation 2 | "cards passing through a segmented glass dial ring halfway along the track" |
| Final Presentation | "cards launching off the end through three aqua-mint chevrons" |
| Research Paper | "a single card held between two thin glowing brackets, sharply in focus" |

---

### IMG-21 · Studio environment map (optional, for 3D reflections)

Glass looks real only when it reflects something. This optional panorama gives the WebGL glass
the same lighting as the AI images.

```
Equirectangular 360-degree panorama of a dark empty photography studio: deep indigo walls,
one large soft rectangular softbox glowing indigo-blue on the left, one large soft violet
softbox overhead, a thin aqua-mint strip light low on the back wall, a matte black floor,
no objects, seamless left-right edges, 2:1 aspect ratio.
```

Check that the left and right edges meet without a seam. Use it as an LDR environment texture
(JPG, 2048×1024). It doesn't replace the `Lightformer` setup in `DESIGN.md` §9.2.2; it adds
richness.

---

## 5. Team portraits: real photos only

**Do not generate faces of real people with AI**, and don't alter anyone's features. The
team page uses **real photos** with one consistent treatment, so they look like a set.

### 5.1 How to take them (each member, same instructions)

| Setting | Instruction |
|---|---|
| Framing | Shoulders up, facing camera, slight head turn allowed; eyes at about 40% from the top |
| Background | Plain light wall (the background will be replaced) |
| Light | Soft window light from your left at about 45°; no overhead light; no flash |
| Clothing | Plain dark top (navy, black or charcoal), no logos or patterns |
| Camera | Phone rear camera in portrait mode **off**, at eye level, about 1.5 m away, 2× zoom |
| Expression | Natural, relaxed; same expression style for all four |
| File | Original resolution, at least 1600×2000 |

### 5.2 Treatment (same for all)

1. Remove the background (Photoshop, Pixelmator, or remove.bg). Ask each person's consent first.
2. Place the cut-out on `IMG-16`, scaled so all four heads are the same size.
3. Export colour at 1600×2000 AVIF/WebP. The **duotone is applied in CSS**, not baked into
   the file, so hovering can reveal colour.
4. Supervisors: real photos only with their permission; otherwise use a monogram tile
   (initials in Mona Sans on `--paper`).

**Alt text format.** *"Portrait of Cooray B.D.A, researcher for the estimation component."*

---

## 6. Product screenshots (capture, don't generate)

Real screenshots prove the product exists, so never fake UI with AI. Capture these from the
running SprintIQ app with realistic seed data (use the lifecycle seed script so sprints have
history, risks and costs).

| ID | Screen | Owner | Used on |
|---|---|---|---|
| SHOT-01 | Story detail with the AI estimate panel open (value, range, confidence, drivers, explanation) | Cooray | M1 chapter, `/components/estimation` |
| SHOT-02 | Sprint with the velocity panel and commitment verdict | Cooray | M1 deep dive |
| SHOT-03 | Backlog with batch estimates | Cooray | M1 deep dive |
| SHOT-04 | Sprint risk dashboard (three risks + daily trend) | Jayasinghe | M2 chapter, `/components/risk` |
| SHOT-05 | Risk advisor answer and remediation steps | Jayasinghe | M2 deep dive |
| SHOT-06 | Task assignment assistant with ranked developers | Athukorala | M3 chapter, `/components/allocation` |
| SHOT-07 | Cost forecast widget and cost trend with drivers | Hewaduwa | M4 chapter, `/components/cost` |
| SHOT-08 | Copilot conversation with tool steps and a proposal card | Group | Copilot section, `/copilot` |
| SHOT-09 | Sprint board (Kanban) mid-sprint | Group | Platform section |
| SHOT-10 | AI model registry / insights page | Group | How it's built |

**Capture settings.**
- Browser window 1440×900 at 2× (export 2880×1800), with browser UI hidden (use
  DevTools "Capture screenshot" or a clean full-screen window).
- Capture **dark mode** for images used in night sections (home chapters) and **light mode**
  for deep-dive pages.
- Use the same demo organisation and projects throughout. Remove real emails and names; use
  the seeded demo people.
- Crop to the panel that matters. A whole-page screenshot is too small to read on the site.
- Frame them on the site with a 21 px radius and soft shadow, optionally tilted
  (`rotateY(-8deg)`) in chapter panels. Don't use fake device bezels.

---

## 7. Optional motion loops (image-to-video)

Short silent loops can replace the live 3D on mid-range phones (the "medium" tier). Make them
from the finished stills.

| From | Prompt (image-to-video) | Length |
|---|---|---|
| IMG-01 | "Very slow forward dolly along the glass ribbon; the cards glide steadily along the track; the chevrons pulse softly; no camera shake, no new objects, seamless loop" | 6 s |
| IMG-06 | "The particle river flows steadily forward; the amber overflow spills gently; camera still; seamless loop" | 5 s |
| IMG-07 | "Haze drifts slowly upward; the red glow around one card breathes slowly; cards move up the track; camera still" | 6 s |
| IMG-11 | "Dawn light slowly brightens at the horizon; stars fade; cards keep launching through the chevrons; camera still" | 6 s |

Export each as H.264 MP4 and VP9 WebM, 1280 px wide, ≤ 2.5 MB, muted, with the still as the
`poster`. Use `autoplay muted loop playsinline`, and never autoplay when reduced motion is set.

---

## 8. Post-processing and export

1. **Grade to the palette.** Lift the blacks so the darkest tone is about `#0A0A1C` (not pure
   black). Keep the aqua mint rather than neon green, and the violet blue-leaning rather than
   pink.
2. **Clean.** Zoom to 100% and inpaint any stray glyphs, fake letters, warped edges or extra
   chevrons.
3. **Check direction.** The S must read as an S; the chevrons point up-right. Flip
   horizontally if the tool mirrored them.
4. **Crop for text.** Confirm the empty zone each entry asks for is truly empty at every
   breakpoint crop.
5. **Export.**
   - Masters: PNG, kept in a private `design/masters/` folder.
   - Web: AVIF (quality ≈ 50) + WebP (quality ≈ 75) at 640, 1080, 1920 and 2560 px wide.
     The hero is ≤ 180 KB at 1920.
   - Naming: `img-05-sizing-gate-16x10@1920.avif`, `shot-01-estimate-panel@2x.webp`.
6. **Log.** Record tool, prompt, seed, date and edits for each final image (for the AI-use
   disclosure).

---

## 9. Pick-the-winner checklist

For each image, the chosen variation must pass all of these:

- [ ] No text, letters, numbers or logos anywhere (check at 100% zoom).
- [ ] No faces, hands, robots, brains or circuit boards.
- [ ] Colours stay within the palette: indigo, violet, aqua-mint, night or daylight (+ amber
      and red only in risk images).
- [ ] Looks like the same world as `IMG-01`: same glass, same light, same grain.
- [ ] The empty area for HTML text is genuinely empty and calm.
- [ ] The metaphor reads in two seconds (sizing, loom, meter, weather…).
- [ ] It still works at 640 px wide on a phone.
- [ ] Alt text written, or the image marked decorative.
