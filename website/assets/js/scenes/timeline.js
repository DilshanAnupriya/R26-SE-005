/* ==========================================================================
   Hero concept 1 — the 3D sprint timeline.
   An isometric Gantt chart of one sprint (Day 00 → Day 10). The hero shows a
   live plan with a "today" line sweeping across it. Scrolling collapses it to
   a backlog pile, then each model rebuilds the plan: M1 sizes the bars (with
   a ghost range), M3 drops them into developer lanes, M4 draws the budget on
   the back wall, M2 flags risk, the Copilot links it all, and Day 10 ships.
   ========================================================================== */
(function () {
  "use strict";
  if (!window.Scene) return;

  window.Scene.register("timeline", function (kit) {
    var THREE = kit.THREE, C = kit.C, v3 = kit.v3, world = kit.world;
    var U = kit.util, sm = U.smooth, lerp = U.lerp;

    var dayX = function (d) { return -5 + d; };
    var LANES = [-1.95, -0.65, 0.65, 1.95];
    var DEV = [C.indigo, C.violet, C.blue, C.teal];
    var BAR_H = 0.24, BAR_D = 0.62;

    /* ---------- Board ---------- */
    var slabMat = new THREE.MeshStandardMaterial({ roughness: 0.55, metalness: 0.05, transparent: true });
    kit.onTheme(function (t) {
      slabMat.color.set(t === "night" ? 0x0c1428 : 0xffffff);
      slabMat.opacity = t === "night" ? 0.85 : 1;
    });
    var slab = new THREE.Mesh(kit.slab(12.6, 6.1, 0.16, 0.35), slabMat);
    slab.position.set(-0.55, -0.09, 0);
    world.add(slab);
    var shadow = kit.shadow(15, 8.5, 0.45);
    shadow.position.set(-0.55, -0.2, 0.2);
    world.add(shadow);

    var laneMat = new THREE.MeshBasicMaterial({ transparent: true, depthWrite: false });
    kit.onTheme(function (t) { laneMat.color.set(t === "night" ? 0x10204a : 0xeef2f7); laneMat.opacity = t === "night" ? 0.55 : 1; });
    LANES.forEach(function (z) {
      var lane = new THREE.Mesh(new THREE.PlaneGeometry(10.4, 1.05), laneMat);
      lane.rotation.x = -Math.PI / 2;
      lane.position.set(0, 0.002, z);
      world.add(lane);
    });

    var gridPts = [];
    for (var d = 0; d <= 10; d++) {
      gridPts.push(v3(dayX(d), 0.006, -2.6), v3(dayX(d), 0.006, 2.6));
    }
    var gridMat = new THREE.LineBasicMaterial({ transparent: true, depthWrite: false });
    kit.onTheme(function (t) { gridMat.color.set(t === "night" ? 0x2a3550 : 0xcfd6e2); gridMat.opacity = t === "night" ? 0.7 : 1; });
    world.add(new THREE.LineSegments(new THREE.BufferGeometry().setFromPoints(gridPts), gridMat));

    for (d = 0; d <= 10; d += 1) {
      var lab = kit.label((d < 10 ? "D0" : "D") + d, { size: 40, weight: 500, scale: 0.2, night: "#97a1b3", day: "#8a94a6" });
      lab.position.set(dayX(d), 0.05, 3.05);
      world.add(lab);
    }

    /* ---------- Developers (lane heads) ---------- */
    var devs = LANES.map(function (z, i) {
      var orb = new THREE.Mesh(new THREE.SphereGeometry(0.22, 24, 18),
        new THREE.MeshStandardMaterial({ color: DEV[i], emissive: DEV[i], emissiveIntensity: 0.35, roughness: 0.35 }));
      orb.position.set(dayX(0) - 0.85, 0.28, z);
      world.add(orb);
      var halo = kit.glow(DEV[i], 1.0, 0.5, 0.35);
      halo.position.copy(orb.position);
      world.add(halo);
      var load = new THREE.Mesh(new THREE.BoxGeometry(0.1, 1, 0.1).translate(0, 0.5, 0),
        new THREE.MeshStandardMaterial({ color: DEV[i], emissive: DEV[i], emissiveIntensity: 0.25 }));
      load.position.set(dayX(0) - 0.4, 0.02, z - 0.32);
      load.scale.y = 0.001;
      world.add(load);
      return { orb: orb, halo: halo, load: load };
    });

    /* ---------- Tasks ---------- */
    var TASKS = [
      { lane: 0, start: 0, len: 2.5, range: 0.8, pts: 5 },
      { lane: 1, start: 0.5, len: 3.5, range: 1.2, pts: 8 },
      { lane: 2, start: 0, len: 2, range: 0.5, pts: 3 },
      { lane: 3, start: 1, len: 3, range: 1.0, pts: 5 },
      { lane: 0, start: 3, len: 3, range: 1.0, pts: 8 },
      { lane: 2, start: 2.5, len: 4, range: 1.4, pts: 13 },
      { lane: 1, start: 4.5, len: 2, range: 0.6, pts: 3 },
      { lane: 3, start: 4.5, len: 3.5, range: 1.1, pts: 8, risk: C.red },
      { lane: 0, start: 6.5, len: 2, range: 0.6, pts: 3 },
      { lane: 1, start: 7, len: 2.5, range: 0.8, pts: 5, risk: C.amber },
      { lane: 2, start: 7, len: 2.5, range: 0.7, pts: 5 }
    ];
    var loads = [0, 0, 0, 0];
    TASKS.forEach(function (t) { loads[t.lane] += t.pts; });

    var barGeo = kit.roundedBox(1, BAR_H, BAR_D, 0.09);
    barGeo.translate(0.5, 0, 0);
    var ghostGeo = new THREE.BoxGeometry(1, BAR_H * 0.9, BAR_D * 0.86).translate(0.5, 0, 0);
    var unsized = new THREE.Color(), sized = C.indigo, done = C.aqua, tmpC = new THREE.Color();
    kit.onTheme(function (t) { unsized.set(t === "night" ? 0x2a3550 : 0xc4cbd8); });

    TASKS.forEach(function (task, i) {
      task.bar = new THREE.Mesh(barGeo, new THREE.MeshStandardMaterial({ roughness: 0.32, metalness: 0.1, emissive: 0x000000 }));
      world.add(task.bar);
      task.ghost = new THREE.Mesh(ghostGeo, new THREE.MeshBasicMaterial({ color: C.violet, transparent: true, opacity: 0, depthWrite: false }));
      world.add(task.ghost);
      task.tag = kit.label(String(task.pts), { size: 48, scale: 0.3, bg: ["rgba(16,19,27,0.85)", "rgba(255,255,255,0.95)"], night: "#eef2f7", day: "#0b0e14" });
      world.add(task.tag);
      task.stack = v3(-8.5 + ((i * 37) % 5) * 0.03, 0.12 + i * 0.085, 1.6 + ((i * 53) % 7) * 0.02);
      task.stackRot = ((i * 29) % 9 - 4) * 0.03;
      task.stage = v3(-8.0, 0.35 + i * 0.36, 3.55);
      task.lanePos = v3(dayX(task.start), BAR_H / 2 + 0.01, LANES[task.lane]);
      task.end = task.start + task.len;
      if (task.risk) {
        var pole = new THREE.Mesh(new THREE.CylinderGeometry(0.018, 0.018, 0.9, 8).translate(0, 0.45, 0),
          new THREE.MeshStandardMaterial({ color: 0x97a1b3 }));
        var flag = new THREE.Mesh(new THREE.PlaneGeometry(0.38, 0.24).translate(0.19, 0.78, 0),
          new THREE.MeshBasicMaterial({ color: task.risk, side: THREE.DoubleSide }));
        task.flag = new THREE.Group();
        task.flag.add(pole, flag);
        task.flagGlow = kit.glow(task.risk, 1.6, 0.6, 0.5);
        world.add(task.flag, task.flagGlow);
      }
    });

    /* ---------- Today line ---------- */
    var playMat = new THREE.MeshBasicMaterial({ color: C.aqua, transparent: true, opacity: 0, depthWrite: false, side: THREE.DoubleSide });
    var play = new THREE.Mesh(new THREE.PlaneGeometry(5.5, 1.5), playMat);
    play.rotation.y = Math.PI / 2;
    world.add(play);
    var playEdge = new THREE.Mesh(new THREE.BoxGeometry(0.03, 0.03, 5.5), new THREE.MeshBasicMaterial({ color: C.aqua, transparent: true }));
    world.add(playEdge);
    var todayTag = kit.label("TODAY", { size: 40, scale: 0.26, bg: ["#19e6c1", "#0a7a68"], night: "#07090e", day: "#ffffff" });
    world.add(todayTag);

    /* ---------- Budget wall (M4) ---------- */
    var wall = new THREE.Group();
    wall.position.set(0, 0, -3.05);
    world.add(wall);
    var wallMat = new THREE.MeshStandardMaterial({ transparent: true, roughness: 0.4 });
    kit.onTheme(function (t) { wallMat.color.set(t === "night" ? 0x0f1a33 : 0xedf1f7); });
    var wallMesh = new THREE.Mesh(kit.roundedBox(11.2, 3.3, 0.08, 0.25), wallMat);
    wallMesh.position.set(-0.3, 1.75, 0);
    wall.add(wallMesh);
    var wy = function (v) { return 0.45 + v * 2.5; };
    var planned = kit.line([v3(dayX(0), wy(0), 0.06), v3(dayX(10), wy(1), 0.06)], 0x97a1b3, 0.9, true);
    wall.add(planned);
    var spentPts = [];
    for (var k = 0; k <= 10; k++) {
      var dd = k * 0.5;
      spentPts.push(v3(dayX(dd), wy(dd / 10 * 1.08 + Math.sin(dd * 1.7) * 0.012), 0.08));
    }
    var spentCurve = new THREE.CatmullRomCurve3(spentPts);
    var spentGeo = new THREE.TubeGeometry(spentCurve, 60, 0.035, 8, false);
    var spent = new THREE.Mesh(spentGeo, new THREE.MeshBasicMaterial({ color: C.indigo }));
    wall.add(spent);
    var fcCurve = new THREE.CatmullRomCurve3([spentPts[10], v3(dayX(7), wy(0.76), 0.08), v3(dayX(10), wy(1.1), 0.08)]);
    var fcGeo = new THREE.TubeGeometry(fcCurve, 40, 0.035, 8, false);
    var forecast = new THREE.Mesh(fcGeo, new THREE.MeshBasicMaterial({ color: C.violet }));
    wall.add(forecast);
    var fcDot = new THREE.Mesh(new THREE.SphereGeometry(0.08, 16, 12), new THREE.MeshBasicMaterial({ color: C.aqua }));
    fcDot.position.copy(fcCurve.getPoint(1));
    wall.add(fcDot);
    var wallTitle = kit.label("BUDGET · SPENT VS RE-FORECAST", { size: 40, scale: 0.26, night: "#97a1b3", day: "#4b5565" });
    wallTitle.position.set(dayX(0) + 1.4, 3.15, 0.1);
    wall.add(wallTitle);
    var wallParts = [planned.material, spent.material, forecast.material, fcDot.material, wallTitle.material];
    wallParts.forEach(function (m) { m.transparent = true; });

    /* ---------- Copilot orb ---------- */
    var orbPos = v3(-0.5, 4.8, 0.6);
    var orb = new THREE.Mesh(new THREE.SphereGeometry(0.2, 24, 18), new THREE.MeshBasicMaterial({ color: 0xc8fff4, transparent: true }));
    orb.position.copy(orbPos);
    world.add(orb);
    var orbGlow = kit.glow(C.aqua, 2.2, 0.8, 0.5);
    orbGlow.position.copy(orbPos);
    world.add(orbGlow);
    var threads = [TASKS[7], TASKS[9]].map(function (t) {
      var l = kit.line([orbPos, t.lanePos.clone().add(v3(t.len / 2, 0.3, 0))], C.aqua, 0, false);
      world.add(l);
      return l;
    });
    var wallThread = kit.line([orbPos, v3(dayX(10), wy(1.1), -2.95)], C.aqua, 0, false);
    world.add(wallThread);

    /* ---------- Ship chevrons ---------- */
    var chevGeo = kit.chevronGeometry(2.2);
    var chevrons = [0, 1, 2].map(function (i) {
      var m = new THREE.Mesh(chevGeo, new THREE.MeshBasicMaterial({ color: C.aqua, transparent: true, opacity: 0 }));
      m.rotation.x = -Math.PI / 2;
      world.add(m);
      return m;
    });
    var shipGlow = kit.glow(C.aqua, 5, 0.6, 0.4);
    shipGlow.position.set(dayX(10) + 1.2, 0.4, 0);
    world.add(shipGlow);

    var stars = kit.stars(kit.narrow ? 900 : 2200);
    kit.onTheme(function (t) { stars.visible = t === "night"; });

    /* ---------- Camera stops ---------- */
    var stops = [
      { pos: v3(6.2, 11.0, 19.5), look: v3(-4.9, -0.6, 0.6) },   // 0 hero: the live plan, on the right
      { pos: v3(-3.8, 4.2, 9.6), look: v3(-11.2, 0.4, 1.4) },    // 1 problem: the backlog pile
      { pos: v3(6.0, 14.0, 22.0), look: v3(-3.6, 0, 0) },        // 2 platform: pulled back, dimmed
      { pos: v3(0.8, 4.4, 15.0), look: v3(-10.8, 1.9, 2.6) },    // 3 M1: the sized ladder
      { pos: v3(4.2, 11.0, 12.0), look: v3(-4.6, -0.4, 0.2) },   // 4 M3: lanes from above
      { pos: v3(2.6, 4.2, 9.8), look: v3(-4.6, 1.6, -2.9) },     // 5 M4: the budget wall
      { pos: v3(8.6, 3.6, 8.4), look: v3(-0.6, 0.3, 1.0) },      // 6 M2: the risky bars
      { pos: v3(5.0, 13.0, 16.5), look: v3(-4.4, 1.2, 0) },      // 7 Copilot
      { pos: v3(12.0, 6.0, 9.0), look: v3(1.0, 0.2, 0) }         // 8 horizon: shipped
    ];

    var tmpV = new THREE.Vector3();
    function update(st) {
      var s = st.s, t = st.t;
      var night = st.theme === "night";

      // Phase weights driven by the scroll position.
      var toPile = sm(0.1, 0.85, s);                       // hero plan collapses to the backlog
      var today = 5 * sm(4.05, 4.9, s) + 2 * sm(5.05, 5.9, s) + 3 * sm(7.1, 7.95, s);
      var heroToday = (t * 0.9) % 11;
      var liveToday = lerp(heroToday, 0, toPile);
      var showToday = s < 1 ? 1 - toPile : sm(3.6, 4.1, s);
      var todayX = dayX(s < 1 ? liveToday : today);
      var riskOn = sm(5.1, 5.9, s) * (1 - sm(7.5, 7.95, s));
      var ship = sm(7.15, 7.95, s);
      var cpOn = sm(6.1, 6.9, s) * (1 - sm(7.3, 7.9, s));

      TASKS.forEach(function (task, i) {
        var m1 = sm(2.05 + i * 0.045, 2.7 + i * 0.045, s);
        var m3 = sm(3.05 + i * 0.05, 3.6 + i * 0.05, s);
        var bob = Math.sin(t * 1.4 + i) * 0.03 * (toPile - m1);

        // Position: plan → pile → sized ladder → lane.
        if (m3 > 0) tmpV.copy(task.stage).lerp(task.lanePos, m3);
        else if (m1 > 0) tmpV.copy(task.stack).lerp(task.stage, m1);
        else tmpV.copy(task.lanePos).lerp(task.stack, toPile);
        tmpV.y += Math.sin(Math.PI * m3) * 1.1 + bob;
        task.bar.position.copy(tmpV);
        task.bar.rotation.y = task.stackRot * toPile * (1 - m1);

        // Length: real plan → unsized card → estimate.
        var cardLen = 0.95;
        var len = lerp(lerp(task.len, cardLen, toPile), task.len, m1);
        var height = lerp(lerp(1, 0.32, toPile), 1, m1);
        task.bar.scale.set(len, height, 1);

        // Colour: plan colours → grey (a guess) → indigo (sized) → developer → risk / done.
        var dev = DEV[task.lane];
        var nowDay = s < 1 ? liveToday : today;
        var isDone = s < 1 ? (heroToday > task.end && toPile < 0.5) : (nowDay >= task.end - 0.01);
        tmpC.copy(dev).lerp(unsized, toPile).lerp(sized, m1 * (1 - m3)).lerp(dev, m3);
        if (task.risk) tmpC.lerp(task.risk, riskOn);
        if (isDone && (s < 1 || m3 > 0.99)) tmpC.lerp(done, task.risk ? ship : 0.85);
        task.bar.material.color.copy(tmpC);
        task.bar.material.emissive.copy(tmpC).multiplyScalar(night ? 0.28 : 0.06);

        // Ghost range (the honest range) appears when sized, fades once shipped.
        var ghostOn = m1 * (1 - toPile * (1 - m1)) * (1 - ship * 0.8);
        task.ghost.position.copy(task.bar.position).add(v3(len, 0, 0));
        task.ghost.scale.set(task.range * m1 + 0.0001, 1, 1);
        task.ghost.material.opacity = ghostOn * (night ? 0.35 : 0.28);
        task.ghost.material.color.copy(task.risk ? task.risk : C.violet);

        // Point tag above the bar while sizing and assigning.
        task.tag.position.copy(task.bar.position).add(v3(len / 2, 0.42, 0));
        task.tag.material.opacity = m1 * (1 - sm(4.2, 4.8, s));
        task.tag.visible = task.tag.material.opacity > 0.01;

        if (task.flag) {
          var f = riskOn;
          task.flag.position.copy(task.bar.position).add(v3(len * 0.75, BAR_H / 2, 0));
          task.flag.scale.set(1, Math.max(0.001, f), 1);
          task.flag.visible = f > 0.01;
          task.flag.rotation.y = Math.sin(t * 2 + i) * 0.25;
          task.flagGlow.position.copy(task.bar.position).add(v3(len / 2, 0.2, 0));
          kit.glowOpacity(task.flagGlow, f * (0.6 + Math.sin(t * Math.PI / 0.8) * 0.4));
        }
      });

      // Developer workload rises as tasks are assigned.
      var assigned = sm(3.1, 3.9, s);
      devs.forEach(function (dv, i) {
        dv.load.scale.y = Math.max(0.001, (loads[i] / 24) * 1.2 * Math.max(assigned, 1 - toPile));
        kit.glowOpacity(dv.halo, 0.5 + Math.sin(t * 2 + i) * 0.2);
      });

      // Today line.
      playMat.opacity = showToday * (night ? 0.16 : 0.12);
      play.position.set(todayX, 0.75, 0);
      playEdge.position.set(todayX, 1.5, 0);
      playEdge.material.opacity = showToday;
      playEdge.visible = showToday > 0.01;
      todayTag.visible = showToday > 0.01;
      todayTag.position.set(todayX, 1.85, -2.6);
      todayTag.material.opacity = showToday;
      playEdge.material.color.copy(night ? C.aqua : C.aquaDeep);
      playMat.color.copy(night ? C.aqua : C.aquaDeep);

      // Budget wall.
      var wallOn = sm(4.0, 4.7, s) * (1 - sm(7.6, 8, s) * 0.6);
      wallMat.opacity = wallOn * (night ? 0.75 : 0.95);
      wall.visible = wallOn > 0.01;
      var spentDraw = sm(4.1, 4.9, s);
      spentGeo.setDrawRange(0, Math.floor(spentGeo.index.count * spentDraw / 6) * 6);
      var fcDraw = sm(4.6, 5.1, s);
      fcGeo.setDrawRange(0, Math.floor(fcGeo.index.count * fcDraw / 6) * 6);
      wallParts.forEach(function (m) { m.opacity = wallOn; });
      fcDot.visible = fcDraw > 0.98;

      // Copilot links the risky work and the budget.
      orb.material.opacity = cpOn;
      orb.visible = cpOn > 0.01;
      kit.glowOpacity(orbGlow, cpOn * (0.8 + Math.sin(t * 2.4) * 0.2));
      threads[0].material.opacity = cpOn * sm(0.36, 0.5, st.copilot) * 0.9;
      threads[1].material.opacity = cpOn * sm(0.5, 0.64, st.copilot) * 0.9;
      wallThread.material.opacity = cpOn * sm(0.62, 0.78, st.copilot) * 0.8;

      // Ship: chevrons fly out past Day 10.
      chevrons.forEach(function (m, i) {
        var phase = (t * 0.7 + i * 0.22) % 1;
        m.position.set(dayX(10) + 0.9 + i * 0.5 + phase * 0.4 * ship, 0.3, 0);
        m.material.opacity = ship * [0.5, 0.75, 1][i];
        m.visible = ship > 0.01;
      });
      kit.glowOpacity(shipGlow, ship * (0.7 + Math.sin(t * 2) * 0.2));

      stars.material.opacity = 0.9 * (1 - st.dawn * 0.85);
      world.rotation.y = st.narrow ? Math.sin(t * 0.1) * 0.12 : 0;
    }

    return { stops: stops, center: v3(-1.2, 0.6, 0.4), narrowDistance: 21, update: update };
  });
})();
