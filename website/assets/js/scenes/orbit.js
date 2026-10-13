/* ==========================================================================
   Hero concept 3 — the orbiting sprint board.
   Story cards orbit a glowing sprint goal. Scrolling scatters them (the
   guesswork), then each model brings order: M1 sizes them, M3 snaps them
   into four developer orbits, M4 raises a cost dial around the goal, M2
   sends a storm round the outer orbit, and on Day 10 they spiral in: shipped.
   ========================================================================== */
(function () {
  "use strict";
  if (!window.Scene) return;

  window.Scene.register("orbit", function (kit) {
    var THREE = kit.THREE, C = kit.C, v3 = kit.v3, world = kit.world;
    var U = kit.util, sm = U.smooth, lerp = U.lerp;

    var R = [2.0, 2.75, 3.5, 4.25];
    var DEV = [C.indigo, C.violet, C.blue, C.teal];
    var SPEED = [0.32, 0.24, 0.18, 0.13];
    var FIB = [1, 2, 3, 5, 8, 13];

    /* ---------- Sprint goal ---------- */
    var goalMat = new THREE.MeshStandardMaterial({ color: C.indigo, emissive: C.indigo, emissiveIntensity: 0.5, roughness: 0.3, metalness: 0.2 });
    var goal = new THREE.Mesh(new THREE.CylinderGeometry(0.8, 0.8, 0.34, 6), goalMat);
    goal.position.y = 0.1;
    world.add(goal);
    var goalTop = new THREE.Mesh(new THREE.CylinderGeometry(0.55, 0.55, 0.36, 6), new THREE.MeshStandardMaterial({ color: 0xffffff, emissive: 0xffffff, emissiveIntensity: 0.15 }));
    goalTop.position.y = 0.12;
    world.add(goalTop);
    var goalGlow = kit.glow(C.violet, 4.2, 0.6, 0.35);
    goalGlow.position.y = 0.3;
    world.add(goalGlow);
    var goalTag = kit.label("SPRINT GOAL", { size: 40, scale: 0.28, bg: ["rgba(16,19,27,0.85)", "rgba(255,255,255,0.95)"] });
    goalTag.position.y = 0.95;
    world.add(goalTag);
    var shadow = kit.shadow(11, 11, 0.35);
    shadow.position.y = -0.6;
    world.add(shadow);

    /* ---------- Orbit rings (developer lanes) ---------- */
    var ringMats = [];
    var rings = R.map(function (r, i) {
      var m = new THREE.MeshBasicMaterial({ color: DEV[i], transparent: true, opacity: 0, depthWrite: false });
      ringMats.push(m);
      var ring = new THREE.Mesh(new THREE.TorusGeometry(r, 0.012, 6, 160), m);
      ring.rotation.x = Math.PI / 2;
      world.add(ring);
      return ring;
    });
    var devOrbs = R.map(function (r, i) {
      var o = new THREE.Mesh(new THREE.SphereGeometry(0.16, 20, 16), new THREE.MeshStandardMaterial({ color: DEV[i], emissive: DEV[i], emissiveIntensity: 0.45 }));
      world.add(o);
      var g = kit.glow(DEV[i], 0.9, 0.6, 0.4);
      world.add(g);
      return { orb: o, glow: g };
    });

    /* ---------- Cards ---------- */
    var N = kit.narrow ? 20 : 28;
    var cardGeo = kit.roundedBox(0.5, 0.32, 0.035, 0.05);
    var unsized = new THREE.Color();
    kit.onTheme(function (t) { unsized.set(t === "night" ? 0x3b4660 : 0xc4cbd8); });
    var tilt = new THREE.Euler(0.38, 0, 0.12);
    var cards = [];
    for (var i = 0; i < N; i++) {
      var mat = new THREE.MeshStandardMaterial({ roughness: 0.3, metalness: 0.1 });
      var mesh = new THREE.Mesh(cardGeo, mat);
      world.add(mesh);
      var cr = 2.2 + Math.random() * 2.6;
      var th = Math.random() * Math.PI * 2;
      var ph = Math.acos(2 * Math.random() - 1);
      cards.push({
        mesh: mesh,
        lane: i % 4,
        a0: i * 2.39996,
        rFree: 3.3 + Math.sin(i * 1.7) * 0.45,
        chaos: v3(cr * Math.sin(ph) * Math.cos(th), cr * Math.cos(ph) * 0.6, cr * Math.sin(ph) * Math.sin(th)),
        pts: FIB[(i * 7) % FIB.length],
        risk: i % 9 === 4 ? C.red : (i % 9 === 7 ? C.amber : null)
      });
    }

    /* ---------- M4: cost dial around the goal ---------- */
    var dial = new THREE.Group();
    world.add(dial);
    var ticks = new THREE.InstancedMesh(new THREE.BoxGeometry(0.035, 0.22, 0.035), new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true }), 55);
    var m4 = new THREE.Matrix4();
    var darkTick = new THREE.Color();
    function paintTicks(t) {
      darkTick.set(t === "night" ? 0x1e2a44 : 0xd5dbe5);
      for (var k = 0; k < 55; k++) {
        var ang = (135 + 270 * k / 54) * Math.PI / 180;
        m4.makeTranslation(Math.cos(ang) * 1.3, 0.11, Math.sin(ang) * 1.3);
        ticks.setMatrixAt(k, m4);
        ticks.setColorAt(k, k < 28 ? C.indigo : darkTick);
      }
      ticks.instanceMatrix.needsUpdate = true;
      if (ticks.instanceColor) ticks.instanceColor.needsUpdate = true;
    }
    kit.onTheme(paintTicks);
    dial.add(ticks);
    var arcGhost = new THREE.Mesh(new THREE.TorusGeometry(1.52, 0.012, 6, 120, Math.PI * 1.5 * 0.72), new THREE.MeshBasicMaterial({ color: 0x97a1b3, transparent: true }));
    var arcNew = new THREE.Mesh(new THREE.TorusGeometry(1.62, 0.025, 8, 120, Math.PI * 1.5 * 0.81), new THREE.MeshBasicMaterial({ color: C.violet, transparent: true }));
    [arcGhost, arcNew].forEach(function (a) {
      a.rotation.x = -Math.PI / 2;
      a.rotation.z = -135 * Math.PI / 180;
      dial.add(a);
    });

    /* ---------- M2: storm band on the outer orbit ---------- */
    var SN = kit.narrow ? 260 : 560;
    var sPos = new Float32Array(SN * 3), sCol = new Float32Array(SN * 3);
    for (i = 0; i < SN; i++) {
      var a = Math.random() * Math.PI * 0.9;
      var rr = 4.25 + (Math.random() + Math.random() - 1) * 0.6;
      sPos[i * 3] = Math.cos(a) * rr;
      sPos[i * 3 + 1] = (Math.random() + Math.random() - 1) * 0.6 + 0.3;
      sPos[i * 3 + 2] = Math.sin(a) * rr;
      var cc = a < 0.4 && Math.random() < 0.6 ? C.red : (Math.random() < 0.7 ? C.amber : C.mist);
      sCol[i * 3] = cc.r; sCol[i * 3 + 1] = cc.g; sCol[i * 3 + 2] = cc.b;
    }
    var stormGeo = new THREE.BufferGeometry();
    stormGeo.setAttribute("position", new THREE.BufferAttribute(sPos, 3));
    stormGeo.setAttribute("color", new THREE.BufferAttribute(sCol, 3));
    var stormMat = new THREE.PointsMaterial({ size: 0.12, map: kit.glowTex, vertexColors: true, transparent: true, depthWrite: false });
    var storm = new THREE.Points(stormGeo, stormMat);
    world.add(storm);
    kit.onTheme(function (t) { stormMat.blending = t === "night" ? THREE.AdditiveBlending : THREE.NormalBlending; });

    /* ---------- Copilot orb and threads ---------- */
    var orbPos = v3(0, 2.4, 0);
    var orb = new THREE.Mesh(new THREE.SphereGeometry(0.2, 24, 18), new THREE.MeshBasicMaterial({ color: 0xc8fff4, transparent: true }));
    orb.position.copy(orbPos);
    world.add(orb);
    var orbGlow = kit.glow(C.aqua, 2.2, 0.8, 0.5);
    orbGlow.position.copy(orbPos);
    world.add(orbGlow);
    var linked = cards.filter(function (c) { return c.risk; }).concat([cards[1], cards[6]]).slice(0, 4);
    var threads = linked.map(function () {
      var l = kit.line([orbPos, v3(0, 0, 0)], C.aqua, 0, false);
      world.add(l);
      return l;
    });

    /* ---------- Ship: chevrons rising out of the goal ---------- */
    var chevGeo = kit.chevronGeometry(2.6);
    var chevrons = [0, 1, 2].map(function () {
      var m = new THREE.Mesh(chevGeo, new THREE.MeshBasicMaterial({ color: C.aqua, transparent: true, opacity: 0 }));
      m.rotation.z = Math.PI / 2;
      world.add(m);
      return m;
    });

    var stars = kit.stars(kit.narrow ? 900 : 2200);
    kit.onTheme(function (t) { stars.visible = t === "night"; });

    /* ---------- Camera stops ---------- */
    var stops = [
      { pos: v3(-1.2, 4.4, 13.6), look: v3(-5.4, 0.2, 0) },      // 0 hero
      { pos: v3(-2.4, 2.4, 9.8), look: v3(-6.8, 0.4, 0) },       // 1 problem: the scattered cloud
      { pos: v3(0, 9.5, 19.5), look: v3(-4.2, 0, 0) },           // 2 platform
      { pos: v3(1.6, 1.9, 8.4), look: v3(-3.8, 0.6, 1.2) },      // 3 M1: sized cards up close
      { pos: v3(1.2, 11.0, 7.8), look: v3(-3.6, 0, 0) },         // 4 M3: four orbits from above
      { pos: v3(0.9, 2.8, 5.6), look: v3(-2.6, 0.3, 0) },        // 5 M4: the cost dial
      { pos: v3(6.8, 2.2, 6.8), look: v3(-0.6, 0.6, 0.6) },      // 6 M2: storm on the outer orbit
      { pos: v3(2.6, 7.8, 12.4), look: v3(-3.6, 1.2, 0) },       // 7 Copilot
      { pos: v3(0.6, 9.8, 8.2), look: v3(-1.6, 0.6, 0) }         // 8 horizon
    ];

    var free = new THREE.Vector3(), lanePos = new THREE.Vector3(), out = new THREE.Vector3(), tmpC = new THREE.Color();
    function update(st) {
      var s = st.s, t = st.t;
      var night = st.theme === "night";
      var chaos = sm(0.2, 0.9, s) * (1 - sm(2.0, 2.8, s));
      var m1 = sm(2.05, 2.8, s);
      var m3 = sm(3.05, 3.85, s);
      var m4on = sm(4.0, 4.7, s) * (1 - sm(7.2, 7.8, s));
      var risk = sm(5.1, 5.9, s) * (1 - sm(7.3, 7.8, s));
      var cp = sm(6.1, 6.9, s) * (1 - sm(7.3, 7.9, s));
      var ship = sm(7.15, 7.95, s);
      var spin = t * (0.12 + st.speed * 0.4);

      cards.forEach(function (c, i) {
        var a = c.a0 + spin;
        free.set(Math.cos(a) * c.rFree, Math.sin(a * 2 + i) * 0.18, Math.sin(a) * c.rFree).applyEuler(tilt);
        var la = c.a0 + t * SPEED[c.lane] * (1 + ship * 6);
        var lr = R[c.lane] * (1 - ship * 0.82);
        lanePos.set(Math.cos(la) * lr, ship * 0.6, Math.sin(la) * lr);
        out.copy(free).lerp(c.chaos.clone().add(v3(Math.sin(t * 0.3 + i) * 0.15, Math.cos(t * 0.4 + i) * 0.1, 0)), chaos).lerp(lanePos, m3);
        c.mesh.position.copy(out);
        var face = Math.atan2(out.z, out.x);
        c.mesh.rotation.set(0, Math.PI / 2 - face, chaos * Math.sin(i) * 0.6);

        var sizeX = lerp(0.85, 0.55 + c.pts / 13 * 1.1, m1);
        var sc = (1 - ship * 0.9);
        c.mesh.scale.set(sizeX * sc, sc, sc);

        tmpC.copy(DEV[c.lane]).lerp(C.white, night ? 0.2 : 0.05).lerp(unsized, chaos).lerp(C.indigo, m1 * (1 - m3)).lerp(DEV[c.lane], m3);
        if (c.risk) tmpC.lerp(c.risk, risk);
        tmpC.lerp(C.aqua, ship);
        c.mesh.material.color.copy(tmpC);
        c.mesh.material.emissive.copy(tmpC).multiplyScalar(night ? 0.3 + (c.risk ? risk * 0.4 : 0) : 0.05);
      });

      rings.forEach(function (r, i) { ringMats[i].opacity = m3 * (night ? 0.55 : 0.7) * (1 - ship); });
      devOrbs.forEach(function (d, i) {
        var a = 1.2 + i * 1.6 + t * SPEED[i];
        var r = R[i] * (1 - ship * 0.82);
        d.orb.position.set(Math.cos(a) * r, 0.05, Math.sin(a) * r);
        d.orb.scale.setScalar(Math.max(0.001, m3 * (1 - ship)));
        d.glow.position.copy(d.orb.position);
        kit.glowOpacity(d.glow, m3 * (1 - ship));
      });

      dial.visible = m4on > 0.01;
      dial.scale.setScalar(0.6 + m4on * 0.4);
      ticks.material.opacity = m4on;
      arcGhost.material.opacity = m4on * 0.6;
      arcNew.material.opacity = m4on * (0.75 + Math.sin(t * 2) * 0.25);

      stormMat.opacity = risk * (night ? 0.85 : 0.7);
      storm.visible = risk > 0.01;
      storm.rotation.y = -t * 0.2;

      orb.material.opacity = cp;
      orb.visible = cp > 0.01;
      kit.glowOpacity(orbGlow, cp * (0.8 + Math.sin(t * 2.4) * 0.2));
      threads.forEach(function (l, k) {
        var p = l.geometry.attributes.position.array;
        var c = linked[k].mesh.position;
        p[3] = c.x; p[4] = c.y; p[5] = c.z;
        l.geometry.attributes.position.needsUpdate = true;
        l.material.opacity = cp * sm(0.3 + k * 0.12, 0.42 + k * 0.12, st.copilot) * 0.9;
      });

      var g = C.indigo.clone().lerp(C.aqua, ship);
      goalMat.color.copy(g);
      goalMat.emissive.copy(g);
      goal.rotation.y = t * 0.2;
      goalTop.rotation.y = t * 0.2;
      goalGlow.material.color.copy(C.violet).lerp(C.aqua, ship);
      kit.glowOpacity(goalGlow, 0.7 + ship * 0.3 + Math.sin(t * 1.6) * 0.1);
      chevrons.forEach(function (m, i) {
        var phase = (t * 0.6 + i * 0.25) % 1;
        m.position.set(0, 0.7 + i * 0.45 + phase * 0.5 * ship, 0);
        m.material.opacity = ship * [0.5, 0.75, 1][i];
        m.visible = ship > 0.01;
      });

      stars.material.opacity = 0.9 * (1 - st.dawn * 0.85);
    }

    return { stops: stops, center: v3(0, 0.3, 0), narrowDistance: 16, update: update };
  });
})();
