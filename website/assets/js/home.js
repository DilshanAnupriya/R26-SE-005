/* ==========================================================================
   SprintIQ research website — home page choreography.
   The hero's SVG network draws itself, the sprint clock counts Day 00 → Day 10,
   the CSS-3D board moves as you scroll, each chapter's SVG illustration builds
   when it comes into view, and in the dark theme the page dawns at the horizon.
   Needs (optional): GSAP + ScrollTrigger + DrawSVG + MotionPath, Lenis.
   Without them the page is a static, fully readable document.
   ========================================================================== */
(function () {
  "use strict";

  var doc = document;
  var root = doc.documentElement;
  // ?lite turns off smooth scrolling and animation (useful on a slow presentation laptop).
  var lite = /[?&]lite\b/.test(window.location.search);
  var reduce = lite || window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var narrowQuery = window.matchMedia("(max-width: 899px)");

  function $(sel, ctx) { return (ctx || doc).querySelector(sel); }
  function $$(sel, ctx) { return Array.prototype.slice.call((ctx || doc).querySelectorAll(sel)); }
  function clamp(v, a, b) { return Math.min(b, Math.max(a, v)); }
  function docTop(el) { return el.getBoundingClientRect().top + window.scrollY; }
  function theme() { return root.getAttribute("data-theme") === "night" ? "night" : "day"; }

  var gsap = window.gsap;
  var hasGsap = !!(gsap && window.ScrollTrigger);
  // Without the animation libraries (or with reduced motion) everything shows its final
  // state, and the pinned sections (board, Copilot) become ordinary sections.
  if (!hasGsap || reduce) {
    root.classList.remove("intro");
    root.classList.add("no-motion");
  }
  var hasDraw = hasGsap && !!window.DrawSVGPlugin;
  var hasPath = hasGsap && !!window.MotionPathPlugin;
  if (hasGsap) {
    gsap.registerPlugin(window.ScrollTrigger);
    if (hasDraw) gsap.registerPlugin(window.DrawSVGPlugin);
    if (hasPath) gsap.registerPlugin(window.MotionPathPlugin);
  }

  /* ---------- 1. Chapter illustrations build when they come into view ---------- */
  var chapters = $$(".chapter");
  if (reduce || !("IntersectionObserver" in window)) {
    chapters.forEach(function (c) { c.classList.add("is-in"); });
  } else {
    var seenChapters = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        entry.target.classList.add("is-in");
        seenChapters.unobserve(entry.target);
      });
    }, { threshold: 0.3 });
    chapters.forEach(function (c) { seenChapters.observe(c); });
  }

  /* ---------- 2. Smooth scroll ---------- */
  var lenis = null;
  if (!reduce && hasGsap && window.Lenis) {
    lenis = new window.Lenis({ lerp: 0.1, wheelMultiplier: 1, smoothWheel: true });
    root.classList.add("lenis");
    lenis.on("scroll", window.ScrollTrigger.update);
    gsap.ticker.add(function (time) { lenis.raf(time * 1000); });
    gsap.ticker.lagSmoothing(0);
    $$('a[href^="#"]').forEach(function (a) {
      a.addEventListener("click", function (e) {
        var id = a.getAttribute("href");
        if (id.length < 2) return;
        var target = doc.getElementById(id.slice(1));
        if (!target) return;
        e.preventDefault();
        lenis.scrollTo(target, { offset: id === "#main" ? 0 : -8 });
      });
    });
    var mnav = $("[data-mnav]");
    if (mnav) mnav.addEventListener("toggle", function () { if (mnav.open) lenis.stop(); else lenis.start(); });
  }

  /* ---------- 3. Measure the page ---------- */
  var dayEls = $$("[data-day]");
  var horizon = $("#horizon");
  var clockEl = $(".clock");
  var bar = $(".clock-bar");
  var barLabel = $(".clock-bar__label");
  var phaseEl = $(".clock__phase");
  var ticks = $$(".clock__tick");
  var odo = $$(".odo__strip");

  var vh = window.innerHeight;
  var dayMarks = [];
  var flipAt = Infinity;
  var maxScroll = 1;

  function measure() {
    vh = window.innerHeight;
    maxScroll = Math.max(1, doc.documentElement.scrollHeight - vh);
    dayMarks = dayEls.map(function (el) { return { el: el, top: docTop(el) }; });
    if (horizon) flipAt = docTop(horizon) + (horizon.offsetHeight - vh) * 0.5;
    ticks.forEach(function (t) {
      var target = doc.getElementById(t.getAttribute("href").slice(1));
      if (target) t.style.setProperty("--at", clamp(docTop(target) / maxScroll, 0, 1).toFixed(3));
    });
  }

  /* ---------- 4. Sprint clock ---------- */
  var current = { day: null, phase: null, id: null };
  function setOdometer(day) {
    var digits = String(day).padStart(2, "0").split("");
    odo.forEach(function (strip, i) { strip.style.setProperty("--d", digits[i] || "0"); });
  }
  function updateClock(y) {
    var p = clamp(y / maxScroll, 0, 1);
    if (clockEl) clockEl.style.setProperty("--progress", p.toFixed(4));
    if (bar) bar.style.setProperty("--progress", p.toFixed(4));
    if (!dayMarks.length) return;
    var cur = dayMarks[0];
    for (var i = 0; i < dayMarks.length; i++) if (dayMarks[i].top <= y + vh * 0.5) cur = dayMarks[i];
    var shipped = y >= maxScroll - 4;
    var day = shipped ? "10" : cur.el.dataset.day;
    var phase = shipped ? "Shipped" : cur.el.dataset.phase;
    var id = cur.el.id;
    if (day === current.day && phase === current.phase && id === current.id) return;
    current = { day: day, phase: phase, id: id };
    setOdometer(day);
    if (clockEl) clockEl.classList.toggle("is-shipped", shipped);
    if (phaseEl) {
      phaseEl.classList.add("is-swapping");
      setTimeout(function () {
        phaseEl.textContent = phase + (shipped ? " ›››" : "");
        phaseEl.classList.remove("is-swapping");
      }, 160);
    }
    if (barLabel) barLabel.textContent = "Day " + day + " · " + phase;
    ticks.forEach(function (t) {
      var target = doc.getElementById(t.getAttribute("href").slice(1));
      var isCurrent = target && target.id === id;
      t.classList.toggle("is-current", !!isCurrent);
      t.classList.toggle("is-passed", !isCurrent && target && docTop(target) <= y + vh * 0.5);
    });
  }

  /* ---------- 5. Light and dark ---------- */
  // Light theme: light from top to bottom. Dark theme: night until the horizon, then day.
  var past = null;
  function applyLight() {
    root.setAttribute("data-light", theme() === "night" && !past ? "night" : "day");
  }
  doc.addEventListener("siq:theme", function () { applyLight(); onScroll(); });

  /* ---------- 6. The scroll handler ---------- */
  function onScroll() {
    var y = window.scrollY;
    var isPast = y >= flipAt;
    if (isPast !== past) {
      past = isPast;
      root.classList.toggle("past-horizon", past);
      applyLight();
    }
    updateClock(y);
  }

  var ticking = false;
  function queue() {
    if (ticking) return;
    ticking = true;
    requestAnimationFrame(function () { ticking = false; onScroll(); });
  }
  if (lenis) lenis.on("scroll", onScroll);
  else window.addEventListener("scroll", queue, { passive: true });

  function refresh() { measure(); current = { day: null }; onScroll(); }
  if (hasGsap) window.ScrollTrigger.addEventListener("refresh", refresh);
  window.addEventListener("resize", function () { if (!hasGsap) refresh(); });
  window.addEventListener("load", function () { if (hasGsap) window.ScrollTrigger.refresh(); else refresh(); });
  if (doc.fonts && doc.fonts.ready) doc.fonts.ready.then(function () { if (hasGsap) window.ScrollTrigger.refresh(); else refresh(); });
  refresh();
  // Re-sync everything to the current scroll position at once (jump links, testing).
  window.SprintHome = {
    sync: refresh,
    go: function (y) {
      if (lenis) lenis.scrollTo(y, { immediate: true, force: true });
      else window.scrollTo(0, y);
      refresh();
    }
  };

  if (!hasGsap || reduce) return;

  /* ---------- 8. Hero: the SprintIQ network ---------- */
  // First visit: the badge appears, the connections grow out of it, and the eight
  // nodes settle in. Then small pulses keep travelling along the connections: into
  // SprintIQ from Scrum, Kanban, the backlog and the Copilot, and out to the four
  // models. The pulses pause whenever the hero is off screen.
  var hx = $(".hx");
  if (hx) {
    var pulseTls = [];
    var startPulses = function () {
      if (!hasPath || pulseTls.length) return;
      var layer = $(".hx-pulses", hx);
      $$(".hx-link", hx).forEach(function (path, i) {
        var dot = doc.createElementNS("http://www.w3.org/2000/svg", "circle");
        dot.setAttribute("r", "4");
        dot.setAttribute("class", "hx-pulse");
        dot.style.opacity = "0";
        layer.appendChild(dot);
        var out = path.hasAttribute("data-out");
        var t = gsap.timeline({ repeat: -1, delay: 0.2 + i * 0.32, repeatDelay: 0.7 + (i % 3) * 0.55 });
        t.to(dot, {
          motionPath: { path: path, align: path, alignOrigin: [0.5, 0.5], start: out ? 1 : 0, end: out ? 0 : 1 },
          duration: 1.7, ease: "power1.inOut"
        }, 0)
          .fromTo(dot, { opacity: 0 }, { opacity: 1, duration: 0.2 }, 0)
          .to(dot, { opacity: 0, duration: 0.3 }, 1.4);
        pulseTls.push(t);
      });
      window.ScrollTrigger.create({
        trigger: ".hero", start: "top top", end: "bottom top",
        onToggle: function (self) { pulseTls.forEach(function (t) { if (self.isActive) t.resume(); else t.pause(); }); }
      });
    };

    var seen = false;
    try { seen = sessionStorage.getItem("siq-intro") === "1"; } catch (e) { seen = false; }
    if (!seen && root.classList.contains("intro")) {
      try { sessionStorage.setItem("siq-intro", "1"); } catch (e) { /* fine */ }
      var intro = gsap.timeline({
        defaults: { ease: "power3.out" },
        onComplete: function () { root.classList.remove("intro"); startPulses(); }
      })
        .fromTo(".hero__title", { opacity: 0, y: 18 }, { opacity: 1, y: 0, duration: 0.9 }, 0)
        .fromTo([".hero__lead", ".hero__cta", ".hero__meta"], { opacity: 0, y: 10 }, { opacity: 1, y: 0, duration: 0.6, stagger: 0.08 }, 0.2)
        .set(hx, { opacity: 1 }, 0.1)
        .fromTo(".hx-center", { opacity: 0, scale: 0.88, transformOrigin: "50% 50%" }, { opacity: 1, scale: 1, duration: 0.8 }, 0.15)
        .fromTo(".hx-deco", { opacity: 0 }, { opacity: 1, duration: 0.9, stagger: 0.04 }, 0.3);
      if (hasDraw) intro.fromTo(".hx-link", { drawSVG: "100% 100%" }, { drawSVG: "0% 100%", duration: 0.7, stagger: 0.05, ease: "power2.inOut" }, 0.45);
      else intro.fromTo(".hx-link", { opacity: 0 }, { opacity: 1, duration: 0.6, stagger: 0.05 }, 0.45);
      intro.fromTo(".hx-jn", { opacity: 0, scale: 0, transformOrigin: "50% 50%" }, { opacity: 1, scale: 1, duration: 0.3, stagger: 0.03 }, 0.8)
        .fromTo(".hx-node", { opacity: 0, scale: 0.8, transformOrigin: "50% 50%" }, { opacity: 1, scale: 1, duration: 0.55, stagger: 0.06 }, 0.85)
        .fromTo(".hx-tag", { opacity: 0, y: 4 }, { opacity: 1, y: 0, duration: 0.45, stagger: 0.05 }, 1.05);
    } else {
      root.classList.remove("intro");
      startPulses();
    }
  }

  /* ---------- 9. SVG: problem icons and chapter glyphs draw themselves ---------- */
  if (hasDraw) {
    $$(".problem__icon").forEach(function (icon) {
      var paths = $$("path, rect, circle", icon);
      gsap.set(paths, { drawSVG: "0%" });
      gsap.to(paths, {
        drawSVG: "100%", duration: 0.9, ease: "power2.out", stagger: 0.12,
        scrollTrigger: { trigger: icon, start: "top 88%", toggleActions: "play none none reverse" }
      });
    });
    $$(".chapter__glyph").forEach(function (glyph) {
      var paths = $$("path, circle", glyph);
      gsap.set(paths, { drawSVG: "0%" });
      gsap.to(paths, {
        drawSVG: "100%", duration: 1.1, ease: "power2.inOut", stagger: 0.1,
        scrollTrigger: { trigger: glyph.closest(".chapter"), start: "top 45%", toggleActions: "play none none reverse" }
      });
    });
  }

  /* ---------- 10. Horizon: the S draws itself, a card rides it, chevrons fly ---------- */
  if ($(".horizon__mark") && hasDraw) {
    var hzTl = gsap.timeline({
      scrollTrigger: { trigger: "#horizon", start: "top 70%", end: "bottom bottom", scrub: 0.6 }
    });
    hzTl.fromTo(".hz-s", { drawSVG: "100% 100%" }, { drawSVG: "0% 100%", ease: "none", duration: 1 }, 0);
    if (hasPath) {
      hzTl.fromTo(".hz-dot", { opacity: 0 }, { opacity: 1, duration: 0.08 }, 0)
        .to(".hz-dot", { motionPath: { path: ".hz-s", align: ".hz-s", alignOrigin: [0.5, 0.5], start: 1, end: 0 }, ease: "none", duration: 1 }, 0)
        .to(".hz-dot", { opacity: 0, duration: 0.08 }, 1);
    }
    hzTl.fromTo(".hz-chev", { opacity: 0, x: -4, y: 4 }, { opacity: 1, x: 0, y: 0, stagger: 0.12, duration: 0.3 }, 0.95)
      .fromTo(".horizon__label", { opacity: 0, y: 10 }, { opacity: 1, y: 0, duration: 0.3 }, 1);
  }

  /* ---------- 11. Platform: cards hop lanes as you scroll ---------- */
  var board = $(".board");
  if (board) {
    var tl = gsap.timeline({
      scrollTrigger: {
        trigger: "#platform",
        start: function () { return narrowQuery.matches ? "top 70%" : "top top"; },
        end: function () { return narrowQuery.matches ? "bottom 40%" : "bottom bottom"; },
        scrub: 0.8,
        invalidateOnRefresh: true
      }
    });
    // Each hop lights the card's model node in the AI layer underneath.
    var nodes = {};
    $$(".board__node", board).forEach(function (n) { nodes[n.dataset.node] = n; });
    var boardCards = $$(".board__card", board);
    boardCards.forEach(function (card, idx) {
      var path = card.dataset.path.split(",").map(Number);
      var offset = (idx % 3) * 0.12;
      var node = nodes[card.dataset.model];
      for (var s = 1; s < path.length; s++) {
        if (path[s] === path[s - 1]) continue;
        var at = s - 1 + offset;
        tl.to(card, { "--lane": path[s], duration: 0.8, ease: "power2.inOut" }, at)
          .to(card, { "--lift": 1, duration: 0.4, ease: "power2.out", yoyo: true, repeat: 1 }, at)
          .to(card, { "--on": 1, duration: 0.2 }, at + 0.7)
          .to(card, { "--on": 0, duration: 0.2 }, at + 1.1);
        if (node) tl.to(node, { "--pulse": 1, duration: 0.2 }, at + 0.6).to(node, { "--pulse": 0, duration: 0.35 }, at + 1.1);
      }
    });
    tl.to({}, { duration: 0.4 });

    // Lane counts follow the cards; a count that changes gives a small bump.
    var laneCounts = $$("[data-lane-count]", board);
    tl.eventCallback("onUpdate", function () {
      var c = [0, 0, 0, 0];
      boardCards.forEach(function (card) {
        var lane = Math.round(parseFloat(gsap.getProperty(card, "--lane")) || 0);
        c[clamp(lane, 0, 3)]++;
      });
      laneCounts.forEach(function (em, i) {
        if (em.textContent === String(c[i])) return;
        em.textContent = c[i];
        em.classList.remove("is-bump");
        void em.offsetWidth;
        em.classList.add("is-bump");
      });
    });
  }

  /* ---------- 12. Copilot: the chat types itself as you scroll ---------- */
  var chat = $("#copilot .chat");
  if (chat) {
    var steps = $$("[data-step]", chat);
    var typed = $("[data-type]", chat);
    var full = typed ? typed.getAttribute("data-type") : "";
    root.classList.add("anim-chat");
    var renderChat = function (p) {
      if (typed) {
        var n = Math.round(clamp((p - 0.04) / 0.18, 0, 1) * full.length);
        typed.innerHTML = full.slice(0, n) + (n < full.length && n > 0 ? '<span class="caret"></span>' : "");
      }
      steps.forEach(function (st) {
        st.classList.toggle("is-shown", p >= parseFloat(st.dataset.step));
        if (st.dataset.done) st.classList.toggle("is-done", p >= parseFloat(st.dataset.done));
      });
    };
    window.ScrollTrigger.create({
      trigger: "#copilot",
      start: function () { return narrowQuery.matches ? "top 75%" : "top top"; },
      end: function () { return narrowQuery.matches ? "bottom 70%" : "bottom bottom"; },
      invalidateOnRefresh: true,
      onUpdate: function (self) { renderChat(self.progress); },
      onRefresh: function (self) { renderChat(self.progress); }
    });
  }

  /* ---------- 13. How it's built: the stack opens up ---------- */
  if ($(".stack3d")) {
    gsap.fromTo(".stack3d", { "--gap": "12px" }, {
      "--gap": "62px",
      ease: "none",
      scrollTrigger: { trigger: "#built", start: "top 85%", end: "center 45%", scrub: 0.6 }
    });
  }
})();
