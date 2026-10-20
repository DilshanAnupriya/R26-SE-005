/* ==========================================================================
   SprintIQ research website — behaviour shared by every page.
   No dependencies. Every feature degrades to readable static HTML without it.
   ========================================================================== */
(function () {
  "use strict";

  var doc = document;
  var root = doc.documentElement;
  var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  function $(sel, ctx) { return (ctx || doc).querySelector(sel); }
  function $$(sel, ctx) { return Array.prototype.slice.call((ctx || doc).querySelectorAll(sel)); }
  function clamp(v, a, b) { return Math.min(b, Math.max(a, v)); }

  /* ---------- Nav: compress after 80px ---------- */
  var nav = $("[data-nav-bar]");
  if (nav) {
    var navTick = false;
    var onNavScroll = function () {
      if (navTick) return;
      navTick = true;
      requestAnimationFrame(function () {
        nav.classList.toggle("is-compact", window.scrollY > 80);
        navTick = false;
      });
    };
    window.addEventListener("scroll", onNavScroll, { passive: true });
    onNavScroll();
  }

  /* ---------- Theme toggle (day / night) ---------- */
  var toggle = $("[data-theme-toggle]");
  function applyTheme(theme, persist) {
    root.setAttribute("data-theme", theme);
    if (!root.hasAttribute("data-home")) root.setAttribute("data-light", theme);
    if (toggle) toggle.setAttribute("aria-label", theme === "night" ? "Switch to day mode" : "Switch to night mode");
    if (persist) {
      try { localStorage.setItem("siq-theme", theme); } catch (e) { /* private mode: keep it for this page only */ }
    }
    doc.dispatchEvent(new CustomEvent("siq:theme", { detail: theme }));
  }
  if (toggle) {
    applyTheme(root.getAttribute("data-theme") || "day", false);
    toggle.addEventListener("click", function () {
      applyTheme(root.getAttribute("data-theme") === "night" ? "day" : "night", true);
    });
  }

  /* ---------- Desktop drop-downs: CSS opens them; JS adds Esc, touch and aria ---------- */
  var noHover = window.matchMedia("(hover: none)");
  $$(".has-menu").forEach(function (item) {
    var trigger = $(".nav__link", item);
    var sync = function () {
      var open = item.matches(":hover, :focus-within") && !item.classList.contains("is-dismissed");
      trigger.setAttribute("aria-expanded", open ? "true" : "false");
    };
    trigger.setAttribute("aria-expanded", "false");
    item.addEventListener("mouseenter", sync);
    item.addEventListener("mouseleave", function () { item.classList.remove("is-dismissed"); sync(); });
    item.addEventListener("focusin", sync);
    item.addEventListener("focusout", function (e) {
      if (!item.contains(e.relatedTarget)) item.classList.remove("is-dismissed");
      setTimeout(sync, 0);
    });
    item.addEventListener("keydown", function (e) {
      if (e.key === "Escape") {
        item.classList.add("is-dismissed");
        trigger.focus();
        sync();
      }
    });
    // On touch screens the first tap opens the menu; a second tap follows the link.
    trigger.addEventListener("click", function (e) {
      if (noHover.matches && trigger.getAttribute("aria-expanded") !== "true") {
        e.preventDefault();
        trigger.focus();
        sync();
      }
    });
  });

  /* ---------- Mobile menu (<details>): Esc, focus loop, scroll lock ---------- */
  var mnav = $("[data-mnav]");
  if (mnav) {
    var summary = $("summary", mnav);
    mnav.addEventListener("toggle", function () {
      root.classList.toggle("menu-open", mnav.open);
      summary.setAttribute("aria-label", mnav.open ? "Close menu" : "Open menu");
    });
    $$("a", mnav).forEach(function (a) {
      a.addEventListener("click", function () { mnav.open = false; });
    });
    mnav.addEventListener("keydown", function (e) {
      if (!mnav.open) return;
      if (e.key === "Escape") {
        mnav.open = false;
        summary.focus();
      } else if (e.key === "Tab") {
        var items = [summary].concat($$("a", mnav));
        var first = items[0];
        var last = items[items.length - 1];
        if (e.shiftKey && doc.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && doc.activeElement === last) { e.preventDefault(); first.focus(); }
      }
    });
    var wide = window.matchMedia("(min-width: 1180px)");
    var closeOnWide = function () { if (wide.matches) mnav.open = false; };
    if (wide.addEventListener) wide.addEventListener("change", closeOnWide);
  }

  /* ---------- Number counters ---------- */
  function formatNumber(value, decimals) {
    return value.toLocaleString("en-US", { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
  }
  function runCounter(el) {
    if (el.dataset.counted) return;
    el.dataset.counted = "1";
    var target = parseFloat(el.dataset.count);
    var decimals = parseInt(el.dataset.decimals || "0", 10);
    if (reduceMotion || isNaN(target)) { el.textContent = formatNumber(target, decimals); return; }
    var start = performance.now();
    var duration = 900;
    var step = function (now) {
      var t = clamp((now - start) / duration, 0, 1);
      var eased = 1 - Math.pow(1 - t, 4);
      el.textContent = formatNumber(target * eased, decimals);
      if (t < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }

  /* ---------- Reveal on scroll ---------- */
  $$("[data-stagger]").forEach(function (group) {
    $$("[data-reveal]", group).forEach(function (el, i) { el.style.setProperty("--i", String(i)); });
  });
  var revealTargets = $$("[data-reveal], .env, .chart, .q-settle, [data-footer]");
  var counters = $$("[data-count]");

  if ("IntersectionObserver" in window) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        entry.target.classList.add("is-in");
        io.unobserve(entry.target);
      });
    }, { rootMargin: "0px 0px -8% 0px", threshold: 0.12 });
    revealTargets.forEach(function (el) { io.observe(el); });

    var countIO = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        runCounter(entry.target);
        countIO.unobserve(entry.target);
      });
    }, { threshold: 0.6 });
    counters.forEach(function (el) {
      if (!reduceMotion) el.textContent = formatNumber(0, parseInt(el.dataset.decimals || "0", 10));
      countIO.observe(el);
    });
  } else {
    revealTargets.forEach(function (el) { el.classList.add("is-in"); });
  }

  /* ---------- Tabs ---------- */
  $$("[data-tabs]").forEach(function (group) {
    var tabs = $$('[role="tab"]', group);
    var select = function (index, focus) {
      tabs.forEach(function (tab, i) {
        var on = i === index;
        tab.setAttribute("aria-selected", on ? "true" : "false");
        tab.tabIndex = on ? 0 : -1;
        var panel = doc.getElementById(tab.getAttribute("aria-controls"));
        if (panel) panel.hidden = !on;
        if (on && focus) tab.focus();
      });
      doc.dispatchEvent(new CustomEvent("siq:layout"));
    };
    tabs.forEach(function (tab, i) {
      tab.addEventListener("click", function () { select(i, false); });
      tab.addEventListener("keydown", function (e) {
        var next = null;
        if (e.key === "ArrowRight") next = (i + 1) % tabs.length;
        if (e.key === "ArrowLeft") next = (i - 1 + tabs.length) % tabs.length;
        if (e.key === "Home") next = 0;
        if (e.key === "End") next = tabs.length - 1;
        if (next !== null) { e.preventDefault(); select(next, true); }
      });
    });
    var initial = tabs.findIndex(function (t) { return t.getAttribute("aria-selected") === "true"; });
    select(initial < 0 ? 0 : initial, false);
  });

  /* ---------- Filters (documents) ---------- */
  $$("[data-filters]").forEach(function (bar) {
    var list = doc.getElementById(bar.getAttribute("data-filters"));
    if (!list) return;
    var items = $$("[data-item]", list);
    var empty = doc.getElementById(bar.getAttribute("data-empty"));
    var count = $("[data-filter-count]", bar);
    var state = {};
    var apply = function () {
      var shown = 0;
      items.forEach(function (item) {
        var ok = Object.keys(state).every(function (key) {
          var want = state[key];
          if (want === "all") return true;
          return (item.getAttribute("data-" + key) || "").split(" ").indexOf(want) !== -1;
        });
        item.hidden = !ok;
        if (ok) shown++;
      });
      if (empty) empty.hidden = shown !== 0;
      if (count) count.textContent = shown + (shown === 1 ? " document" : " documents");
    };
    $$("[data-filter-key]", bar).forEach(function (btn) {
      var key = btn.getAttribute("data-filter-key");
      if (!(key in state)) state[key] = "all";
      btn.addEventListener("click", function () {
        state[key] = btn.getAttribute("data-filter-value");
        $$('[data-filter-key="' + key + '"]', bar).forEach(function (b) {
          b.setAttribute("aria-pressed", b === btn ? "true" : "false");
        });
        apply();
      });
    });
    apply();
  });

  /* ---------- Pipeline rail ---------- */
  $$("[data-pipeline]").forEach(function (pipe) {
    var stages = $$(".pipeline__stage", pipe);
    var panels = $$(".pipeline__panel", pipe);
    pipe.style.setProperty("--n", String(stages.length));
    var select = function (index, focus) {
      stages.forEach(function (stage, i) {
        stage.setAttribute("aria-expanded", i === index ? "true" : "false");
        if (panels[i]) panels[i].classList.toggle("is-active", i === index);
      });
      pipe.style.setProperty("--active", String(index));
      if (focus) stages[index].focus();
    };
    stages.forEach(function (stage, i) {
      stage.addEventListener("click", function () { select(i, false); });
      stage.addEventListener("keydown", function (e) {
        if (e.key === "ArrowRight" || e.key === "ArrowDown") { e.preventDefault(); select(Math.min(i + 1, stages.length - 1), true); }
        if (e.key === "ArrowLeft" || e.key === "ArrowUp") { e.preventDefault(); select(Math.max(i - 1, 0), true); }
      });
    });
    select(0, false);
  });

  /* ---------- Table of contents: highlight the section in view ---------- */
  var tocLinks = $$(".toc a[href^='#']");
  if (tocLinks.length && "IntersectionObserver" in window) {
    var byId = {};
    tocLinks.forEach(function (a) { byId[a.getAttribute("href").slice(1)] = a; });
    var tocIO = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        tocLinks.forEach(function (a) { a.classList.remove("is-active"); });
        var link = byId[entry.target.id];
        if (link) link.classList.add("is-active");
      });
    }, { rootMargin: "-30% 0px -60% 0px" });
    Object.keys(byId).forEach(function (id) {
      var section = doc.getElementById(id);
      if (section) tocIO.observe(section);
    });
  }

  /* ---------- Milestones: open from the URL hash, expand/collapse all, spine ---------- */
  var panels = $$(".ms__panel");
  if (panels.length) {
    var openFromHash = function () {
      var id = decodeURIComponent(location.hash.slice(1));
      if (!id) return;
      var target = doc.getElementById(id);
      var panel = target && (target.matches(".ms__panel") ? target : $(".ms__panel", target));
      if (panel) {
        panel.open = true;
        setTimeout(function () { target.scrollIntoView({ block: "start" }); }, 30);
      }
    };
    openFromHash();
    window.addEventListener("hashchange", openFromHash);

    var allBtn = $("[data-expand-all]");
    if (allBtn) {
      var syncAll = function () {
        var allOpen = panels.every(function (p) { return p.open; });
        allBtn.textContent = allOpen ? "Collapse all" : "Expand all";
        allBtn.setAttribute("aria-pressed", allOpen ? "true" : "false");
      };
      allBtn.addEventListener("click", function () {
        var open = !panels.every(function (p) { return p.open; });
        panels.forEach(function (p) { p.open = open; });
        syncAll();
      });
      panels.forEach(function (p) { p.addEventListener("toggle", syncAll); });
      syncAll();
    }

    var timeline = $(".timeline");
    if (timeline) {
      var spineTick = false;
      var drawSpine = function () {
        spineTick = false;
        var rect = timeline.getBoundingClientRect();
        var p = clamp((window.innerHeight * 0.62 - rect.top) / rect.height, 0, 1);
        timeline.style.setProperty("--p", p.toFixed(4));
      };
      var queueSpine = function () {
        if (!spineTick) { spineTick = true; requestAnimationFrame(drawSpine); }
      };
      window.addEventListener("scroll", queueSpine, { passive: true });
      window.addEventListener("resize", queueSpine);
      panels.forEach(function (p) { p.addEventListener("toggle", queueSpine); });
      drawSpine();
    }
  }

  /* ---------- Copy buttons ---------- */
  $$("[data-copy]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var source = doc.getElementById(btn.getAttribute("data-copy"));
      if (!source) return;
      var text = "value" in source ? source.value : source.innerText;
      var done = function (ok) {
        var label = btn.textContent;
        btn.textContent = ok ? "Copied" : "Press Ctrl+C to copy";
        setTimeout(function () { btn.textContent = label; }, 1800);
      };
      if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(text).then(function () { done(true); }, function () { done(false); });
      } else {
        var range = doc.createRange();
        range.selectNodeContents(source);
        var sel = window.getSelection();
        sel.removeAllRanges();
        sel.addRange(range);
        var ok = false;
        try { ok = doc.execCommand("copy"); } catch (e) { ok = false; }
        done(ok);
      }
    });
  });

  /* ---------- Contact form: compose an e-mail in the visitor's mail app ---------- */
  $$("form[data-mailto]").forEach(function (form) {
    var status = $("[data-form-status]", form);
    var fields = {
      name: { el: form.elements.namedItem("name"), msg: "Enter your name." },
      email: { el: form.elements.namedItem("email"), msg: "Enter an email address like name@example.com." },
      topic: { el: form.elements.namedItem("topic"), msg: "Choose what your message is about." },
      message: { el: form.elements.namedItem("message"), msg: "Write a message of at least 10 characters." }
    };
    var validators = {
      name: function (v) { return v.trim().length > 1; },
      email: function (v) { return /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(v.trim()); },
      topic: function (v) { return v !== ""; },
      message: function (v) { return v.trim().length >= 10; }
    };
    var showError = function (key, show) {
      var f = fields[key];
      if (!f.el) return;
      var wrap = f.el.closest(".field");
      var err = $(".field__error", wrap);
      wrap.classList.toggle("is-invalid", show);
      f.el.setAttribute("aria-invalid", show ? "true" : "false");
      if (err) { err.textContent = show ? f.msg : ""; err.hidden = !show; }
    };
    Object.keys(fields).forEach(function (key) {
      var el = fields[key].el;
      if (el) el.addEventListener("blur", function () { if (el.value) showError(key, !validators[key](el.value)); });
    });
    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var firstBad = null;
      Object.keys(fields).forEach(function (key) {
        var el = fields[key].el;
        var ok = validators[key](el ? el.value : "");
        showError(key, !ok);
        if (!ok && !firstBad) firstBad = el;
      });
      if (firstBad) { firstBad.focus(); return; }

      var to = form.getAttribute("data-mailto");
      var name = fields.name.el.value.trim();
      var topic = fields.topic.el.value;
      var subject = "[R26-SE-005 SprintIQ] " + topic;
      var body =
        "Dear SprintIQ research team,\n\n" +
        fields.message.el.value.trim() + "\n\n" +
        "Kind regards,\n" + name + "\n" + fields.email.el.value.trim() +
        (form.elements.namedItem("org") && form.elements.namedItem("org").value.trim()
          ? "\n" + form.elements.namedItem("org").value.trim() : "");
      var preview = $("[data-mail-preview]", form.parentNode);
      if (preview) preview.textContent = "To: " + to + "\nSubject: " + subject + "\n\n" + body;
      window.location.href = "mailto:" + to + "?subject=" + encodeURIComponent(subject) + "&body=" + encodeURIComponent(body);
      if (status) {
        status.hidden = false;
        status.textContent = "Your e-mail app should now open with this message ready to send. If nothing opens, copy the message below and send it to " + to + ".";
      }
    });
  });

  /* ---------- Figure lightbox ---------- */
  $$("a[data-zoom]").forEach(function (link) {
    link.addEventListener("click", function (e) {
      e.preventDefault();
      var img = $("img", link);
      var box = doc.createElement("div");
      box.className = "lightbox";
      box.setAttribute("role", "dialog");
      box.setAttribute("aria-modal", "true");
      box.setAttribute("aria-label", img ? img.alt : "Figure");
      box.innerHTML = '<button class="icon-btn" type="button" aria-label="Close figure">✕</button>';
      var big = doc.createElement("img");
      big.src = link.getAttribute("href");
      big.alt = img ? img.alt : "";
      box.appendChild(big);
      var close = function () {
        box.remove();
        doc.removeEventListener("keydown", onKey);
        root.classList.remove("menu-open");
        link.focus();
      };
      var onKey = function (ev) { if (ev.key === "Escape" || ev.key === "Tab") { ev.preventDefault(); if (ev.key === "Escape") close(); } };
      box.addEventListener("click", function (ev) { if (ev.target === box || ev.target.closest("button")) close(); });
      doc.addEventListener("keydown", onKey);
      doc.body.appendChild(box);
      root.classList.add("menu-open");
      $("button", box).focus();
    });
  });

  /* ---------- Footer year ---------- */
  $$("[data-year]").forEach(function (el) { el.textContent = String(new Date().getFullYear()); });
})();
