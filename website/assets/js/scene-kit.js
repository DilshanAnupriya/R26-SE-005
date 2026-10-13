/* ==========================================================================
   SprintIQ research website — 3D scene kit (three.js r149, classic build)
   Shared plumbing for the home page's hero scenes: renderer, camera flight
   between scroll stops, day/night theming, labels, glow sprites, fallback.

   A concept registers itself:   Scene.register("name", function (kit) {
                                   …build objects in kit.world…
                                   return { stops: [...9 × {pos, look}], center, update(state) };
                                 });
   The home page drives it:      Scene.init(canvas, "name", "day"|"night")
                                 Scene.setStop(s)  (0 hero … 8 horizon)
                                 Scene.setTheme(t) Scene.setDawn(d)
                                 Scene.setVelocity(v) Scene.setCopilot(p) Scene.pause(bool)
   Fires "scene:fallback" on window when the device can't keep up.
   ========================================================================== */
(function () {
  "use strict";

  var concepts = {};
  function noop() {}
  function clamp(v, a, b) { return Math.min(b, Math.max(a, v)); }
  function smooth(a, b, v) { var t = clamp((v - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); }
  function lerp(a, b, t) { return a + (b - a) * t; }

  var Scene = {
    ready: false,
    name: null,
    register: function (name, build) { concepts[name] = build; },
    has: function (name) { return !!concepts[name]; },
    init: init,
    setStop: noop,
    setDawn: noop,
    setVelocity: noop,
    setCopilot: noop,
    setTheme: noop,
    pause: noop,
    util: { clamp: clamp, smooth: smooth, lerp: lerp }
  };
  window.Scene = Scene;

  function webglAvailable() {
    try {
      var c = document.createElement("canvas");
      return !!(window.WebGLRenderingContext && (c.getContext("webgl2") || c.getContext("webgl")));
    } catch (e) {
      return false;
    }
  }

  function roundRect(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  function init(canvas, name, theme) {
    var THREE = window.THREE;
    var build = concepts[name];
    if (Scene.ready) return true;
    if (!canvas || !THREE || !build || !webglAvailable()) return false;

    var renderer;
    try {
      renderer = new THREE.WebGLRenderer({ canvas: canvas, antialias: true, alpha: true, powerPreference: "high-performance" });
    } catch (e) {
      return false;
    }
    var narrowQuery = window.matchMedia("(max-width: 899px)");
    var narrow = narrowQuery.matches;
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, narrow ? 1.5 : 1.75));
    renderer.setClearColor(0x000000, 0);

    var scene = new THREE.Scene();
    var camera = new THREE.PerspectiveCamera(35, 1, 0.1, 300);
    var world = new THREE.Group();
    scene.add(world);
    var themeFns = [];

    /* ---------- Shared resources ---------- */
    var glowCanvas = document.createElement("canvas");
    glowCanvas.width = glowCanvas.height = 128;
    var g = glowCanvas.getContext("2d");
    var grd = g.createRadialGradient(64, 64, 0, 64, 64, 64);
    grd.addColorStop(0, "rgba(255,255,255,1)");
    grd.addColorStop(0.2, "rgba(255,255,255,0.6)");
    grd.addColorStop(0.5, "rgba(255,255,255,0.15)");
    grd.addColorStop(1, "rgba(255,255,255,0)");
    g.fillStyle = grd;
    g.fillRect(0, 0, 128, 128);
    var glowTex = new THREE.CanvasTexture(glowCanvas);

    var C = {
      indigo: new THREE.Color(0x2563eb), violet: new THREE.Color(0x0ea5e9), aqua: new THREE.Color(0x19e6c1),
      aquaDeep: new THREE.Color(0x0fb89a), blue: new THREE.Color(0x06b6d4), teal: new THREE.Color(0x38bdf8),
      amber: new THREE.Color(0xffb020), red: new THREE.Color(0xff5a5f), mist: new THREE.Color(0x97a1b3),
      star: new THREE.Color(0xeef2f7), white: new THREE.Color(0xffffff), ink: new THREE.Color(0x0b0e14),
      night: new THREE.Color(0x07090e), raised: new THREE.Color(0x10131b), line: new THREE.Color(0x242a36)
    };

    var kit = {
      THREE: THREE, scene: scene, world: world, camera: camera, renderer: renderer,
      narrow: narrow, theme: theme === "night" ? "night" : "day", glowTex: glowTex, C: C,
      util: Scene.util,
      v3: function (x, y, z) { return new THREE.Vector3(x, y, z); },

      /* Run fn(theme) now and whenever the theme changes. */
      onTheme: function (fn) { themeFns.push(fn); fn(kit.theme); return fn; },

      /* Pick a value by theme. */
      pick: function (night, day) { return kit.theme === "night" ? night : day; },

      /* A soft glow: additive at night, a tinted halo by day. */
      glow: function (color, scale, opacity, dayFactor) {
        var m = new THREE.SpriteMaterial({ map: glowTex, color: color, transparent: true, depthWrite: false });
        var s = new THREE.Sprite(m);
        s.scale.setScalar(scale);
        s.userData.base = opacity;
        s.userData.dayFactor = dayFactor == null ? 0.45 : dayFactor;
        kit.onTheme(function (t) {
          m.blending = t === "night" ? THREE.AdditiveBlending : THREE.NormalBlending;
          m.opacity = t === "night" ? opacity : opacity * s.userData.dayFactor;
          m.needsUpdate = true;
        });
        return s;
      },
      /* Opacity for a glow at strength k (0–1), respecting the theme. */
      glowOpacity: function (sprite, k) {
        sprite.material.opacity = sprite.userData.base * k * (kit.theme === "night" ? 1 : sprite.userData.dayFactor);
      },

      line: function (points, color, opacity, dashed) {
        var geo = new THREE.BufferGeometry().setFromPoints(points);
        var mat = dashed
          ? new THREE.LineDashedMaterial({ color: color, transparent: true, opacity: opacity, dashSize: 0.14, gapSize: 0.1, depthWrite: false })
          : new THREE.LineBasicMaterial({ color: color, transparent: true, opacity: opacity, depthWrite: false });
        var l = new THREE.Line(geo, mat);
        if (dashed) l.computeLineDistances();
        return l;
      },

      /* Rounded rectangle extruded along z, centred (w along x, h along y). */
      roundedBox: function (w, h, d, r) {
        var s = new THREE.Shape();
        var x = -w / 2, y = -h / 2;
        r = Math.min(r, w / 2, h / 2);
        s.moveTo(x + r, y);
        s.lineTo(x + w - r, y);
        s.quadraticCurveTo(x + w, y, x + w, y + r);
        s.lineTo(x + w, y + h - r);
        s.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
        s.lineTo(x + r, y + h);
        s.quadraticCurveTo(x, y + h, x, y + h - r);
        s.lineTo(x, y + r);
        s.quadraticCurveTo(x, y, x + r, y);
        var geo = new THREE.ExtrudeGeometry(s, { depth: d, bevelEnabled: false, curveSegments: 5 });
        geo.translate(0, 0, -d / 2);
        return geo;
      },

      /* A flat rounded slab lying on the floor (w along x, d along z, h tall). */
      slab: function (w, d, h, r) {
        var geo = kit.roundedBox(w, d, h, r);
        geo.rotateX(-Math.PI / 2);
        return geo;
      },

      /* Text sprite drawn on a canvas; redrawn when the theme changes. */
      label: function (text, o) {
        o = o || {};
        var c = document.createElement("canvas");
        var ctx = c.getContext("2d");
        var size = o.size || 56;
        var font = (o.weight || 600) + " " + size + "px " + (o.family || '"Geist Mono", ui-monospace, Menlo, monospace');
        var tex = new THREE.CanvasTexture(c);
        var mat = new THREE.SpriteMaterial({ map: tex, transparent: true, depthWrite: false, depthTest: o.depthTest !== false });
        var sp = new THREE.Sprite(mat);
        var scale = o.scale || 0.3;
        kit.onTheme(function (t) {
          // Measure on every draw: the web font may arrive after the first one.
          ctx.font = font;
          var pad = o.pad == null ? size * 0.45 : o.pad;
          var w = Math.ceil(ctx.measureText(text).width + pad * 2);
          var h = Math.ceil(size * 1.6);
          if (c.width !== w || c.height !== h) {
            c.width = w;
            c.height = h;
            tex.dispose();
          }
          sp.scale.set(scale * w / h, scale, 1);
          ctx.clearRect(0, 0, w, h);
          if (o.bg) {
            ctx.fillStyle = t === "night" ? o.bg[0] : o.bg[1];
            roundRect(ctx, 1, 1, w - 2, h - 2, Math.min(h / 2, 18));
            ctx.fill();
          }
          ctx.font = font;
          ctx.textAlign = "center";
          ctx.textBaseline = "middle";
          ctx.fillStyle = t === "night" ? (o.night || "#eef2f7") : (o.day || "#0b0e14");
          ctx.fillText(text, w / 2, h / 2 + size * 0.06);
          tex.needsUpdate = true;
        });
        return sp;
      },

      /* Stars at night, nothing by day. */
      stars: function (count) {
        var pos = new Float32Array(count * 3);
        for (var i = 0; i < count; i++) {
          var r = 26 + Math.random() * 34;
          var th = Math.random() * Math.PI * 2;
          var ph = Math.acos(2 * Math.random() - 1);
          pos[i * 3] = r * Math.sin(ph) * Math.cos(th);
          pos[i * 3 + 1] = r * Math.sin(ph) * Math.sin(th);
          pos[i * 3 + 2] = r * Math.cos(ph) - 8;
        }
        var geo = new THREE.BufferGeometry();
        geo.setAttribute("position", new THREE.BufferAttribute(pos, 3));
        var pts = new THREE.Points(geo, new THREE.PointsMaterial({
          size: 0.2, map: glowTex, color: C.star, transparent: true, opacity: 0.9, depthWrite: false, sizeAttenuation: true
        }));
        scene.add(pts);
        return pts;
      },

      /* A soft contact shadow under an object (day only). */
      shadow: function (w, d, opacity) {
        var c = document.createElement("canvas");
        c.width = c.height = 128;
        var x = c.getContext("2d");
        var gr = x.createRadialGradient(64, 64, 4, 64, 64, 64);
        gr.addColorStop(0, "rgba(11,14,20,0.55)");
        gr.addColorStop(1, "rgba(11,14,20,0)");
        x.fillStyle = gr;
        x.fillRect(0, 0, 128, 128);
        var m = new THREE.MeshBasicMaterial({ map: new THREE.CanvasTexture(c), transparent: true, depthWrite: false });
        var mesh = new THREE.Mesh(new THREE.PlaneGeometry(w, d), m);
        mesh.rotation.x = -Math.PI / 2;
        kit.onTheme(function (t) { m.opacity = t === "night" ? 0 : (opacity == null ? 0.5 : opacity); });
        return mesh;
      },

      /* Chevron (the logo's forward mark), pointing +x, extruded. */
      chevronGeometry: function (size) {
        var s = new THREE.Shape();
        var k = size || 1;
        s.moveTo(-0.13 * k, 0.17 * k);
        s.lineTo(0.07 * k, 0);
        s.lineTo(-0.13 * k, -0.17 * k);
        s.lineTo(-0.06 * k, -0.17 * k);
        s.lineTo(0.14 * k, 0);
        s.lineTo(-0.06 * k, 0.17 * k);
        s.closePath();
        var geo = new THREE.ExtrudeGeometry(s, { depth: 0.04 * k, bevelEnabled: false });
        geo.translate(0, 0, -0.02 * k);
        return geo;
      }
    };

    /* ---------- Light ---------- */
    var ambient = new THREE.AmbientLight(0xffffff, 0.6);
    var hemi = new THREE.HemisphereLight(0xffffff, 0xd8dfea, 0.5);
    var key = new THREE.DirectionalLight(0xffffff, 0.85);
    key.position.set(5, 10, 8);
    var fillA = new THREE.PointLight(0x2563eb, 1.2, 18);
    fillA.position.set(-5, 2, 5);
    var fillB = new THREE.PointLight(0x19e6c1, 0.9, 16);
    fillB.position.set(6, 5, 3);
    scene.add(ambient, hemi, key, fillA, fillB);
    kit.onTheme(function (t) {
      var night = t === "night";
      ambient.color.set(night ? 0x60a5fa : 0xffffff);
      ambient.intensity = night ? 0.55 : 0.55;
      hemi.intensity = night ? 0 : 0.65;
      key.intensity = night ? 0.85 : 0.75;
      fillA.intensity = night ? 1.2 : 0.25;
      fillB.intensity = night ? 0.9 : 0.2;
    });
    kit.lights = { ambient: ambient, hemi: hemi, key: key };

    /* ---------- Build the concept ---------- */
    var concept = build(kit);
    var center = concept.center || kit.v3(0, 0, 0);
    var wideStops = concept.stops;
    var narrowStops = wideStops.map(function (st) {
      var dir = st.pos.clone().sub(st.look).normalize();
      return { pos: center.clone().addScaledVector(dir, concept.narrowDistance || 22), look: center.clone() };
    });
    var stops = narrow ? narrowStops : wideStops;

    var camPos = stops[0].pos.clone();
    var camLook = stops[0].look.clone();
    var targetPos = camPos.clone();
    var targetLook = camLook.clone();
    var stopValue = 0;
    var sDamped = 0;
    var pointer = { x: 0, y: 0, tx: 0, ty: 0 };
    var dawn = 0;
    var copilot = 0;
    var speed = 0, targetSpeed = 0;

    Scene.setStop = function (value) {
      stopValue = clamp(value, 0, stops.length - 1);
      var a = Math.min(Math.floor(stopValue), stops.length - 2);
      var f = stopValue - a;
      f = f * f * (3 - 2 * f);
      targetPos.lerpVectors(stops[a].pos, stops[a + 1].pos, f);
      targetLook.lerpVectors(stops[a].look, stops[a + 1].look, f);
    };
    Scene.setDawn = function (d) { dawn = clamp(d, 0, 1); };
    Scene.setVelocity = function (v) { targetSpeed = Math.min(Math.abs(v) / 2500, 1); };
    Scene.setCopilot = function (p) { copilot = clamp(p, 0, 1); };
    Scene.setTheme = function (t) {
      kit.theme = t === "night" ? "night" : "day";
      themeFns.forEach(function (fn) { fn(kit.theme); });
    };
    if (document.fonts && document.fonts.ready) {
      document.fonts.ready.then(function () { Scene.setTheme(kit.theme); });
    }

    function resize() {
      var w = canvas.clientWidth || window.innerWidth;
      var h = canvas.clientHeight || window.innerHeight;
      renderer.setSize(w, h, false);
      camera.aspect = w / h;
      camera.fov = w / h < 0.8 ? 46 : 35;
      camera.updateProjectionMatrix();
      if (narrowQuery.matches !== narrow) {
        narrow = narrowQuery.matches;
        kit.narrow = narrow;
        stops = narrow ? narrowStops : wideStops;
        Scene.setStop(stopValue);
      }
    }
    window.addEventListener("resize", resize);
    resize();

    if (window.matchMedia("(hover: hover)").matches) {
      window.addEventListener("pointermove", function (e) {
        pointer.tx = (e.clientX / window.innerWidth - 0.5) * 2;
        pointer.ty = (e.clientY / window.innerHeight - 0.5) * 2;
      }, { passive: true });
    }

    /* ---------- Loop ---------- */
    var clock = new THREE.Clock();
    var raf = 0;
    var paused = false;
    // ?keep3d skips the slow-device fallback (for testing, or a laptop you trust).
    var perf = { frames: 0, time: 0, stage: /[?&]keep3d\b/.test(window.location.search) ? 2 : 0 };
    var state = { t: 0, dt: 0, s: 0, dawn: 0, copilot: 0, speed: 0, theme: kit.theme, narrow: narrow };

    function fallback() {
      cancelAnimationFrame(raf);
      paused = true;
      Scene.ready = false;
      try { renderer.dispose(); } catch (e) { /* context already gone */ }
      window.dispatchEvent(new CustomEvent("scene:fallback"));
    }
    canvas.addEventListener("webglcontextlost", function (e) { e.preventDefault(); fallback(); });

    function frame() {
      raf = requestAnimationFrame(frame);
      var dt = Math.min(clock.getDelta(), 0.05);
      step(dt, clock.elapsedTime);
    }

    function step(dt, t) {

      if (perf.stage < 2 && t > 1.2) {
        perf.frames++;
        perf.time += dt;
        if (perf.frames === 90) {
          var fps = perf.frames / perf.time;
          if (fps < 26 && perf.stage === 0) { renderer.setPixelRatio(1); perf.stage = 1; }
          else if (fps < 20 && perf.stage === 1) { fallback(); return; }
          else perf.stage = 2;
          perf.frames = 0;
          perf.time = 0;
        }
      }

      var k = 1 - Math.exp(-dt / 0.35);
      pointer.x += (pointer.tx - pointer.x) * k;
      pointer.y += (pointer.ty - pointer.y) * k;
      camPos.lerp(targetPos, k);
      camLook.lerp(targetLook, k);
      sDamped += (stopValue - sDamped) * (1 - Math.exp(-dt / 0.3));
      speed += (targetSpeed - speed) * (1 - Math.exp(-dt / 0.5));
      targetSpeed *= Math.exp(-dt / 0.6);

      camera.position.set(camPos.x + pointer.x * 0.25, camPos.y - pointer.y * 0.15 + Math.sin(t * 0.25) * 0.06, camPos.z);
      camera.lookAt(camLook);

      state.t = t;
      state.dt = dt;
      state.s = sDamped;
      state.dawn = dawn;
      state.copilot = copilot;
      state.speed = speed;
      state.theme = kit.theme;
      state.narrow = narrow;
      concept.update(state);

      renderer.render(scene, camera);
    }

    /* Jump straight to the current scroll position (after jump links, and for testing). */
    Scene.snap = function () {
      sDamped = stopValue;
      camPos.copy(targetPos);
      camLook.copy(targetLook);
      step(0, clock.elapsedTime);
    };

    Scene.pause = function (p) {
      if (p && !paused) {
        paused = true;
        cancelAnimationFrame(raf);
      } else if (!p && paused && Scene.ready) {
        paused = false;
        clock.getDelta();
        frame();
      }
    };
    document.addEventListener("visibilitychange", function () {
      if (document.hidden) cancelAnimationFrame(raf);
      else if (!paused && Scene.ready) { clock.getDelta(); cancelAnimationFrame(raf); frame(); }
    });

    Scene.ready = true;
    Scene.name = name;
    Scene.setStop(0);
    frame();
    return true;
  }
})();
