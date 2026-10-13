/* ==========================================================================
   Hero concept 2 — the 3D burndown landscape.
   Remaining work falls from 34 points on Day 00 to zero on Day 10. A glowing
   burndown line draws as the sprint advances, with a forecast band (the
   envelope) fanning out ahead of "today". Each model adds a layer: M1 stacks
   the sized stories into the starting height, M3 splits the line per
   developer, M4 adds a cost burn-up wall, M2 brings a storm over Day 07.
   ========================================================================== */
(function () {
  "use strict";
  if (!window.Scene) return;

  window.Scene.register("burndown", function (kit) {
    var THREE = kit.THREE, C = kit.C, v3 = kit.v3, world = kit.world;
    var U = kit.util, sm = U.smooth, lerp = U.lerp, clamp = U.clamp;

    var dayX = function (d) { return -6 + d * 1.2; };
    var Y = function (p) { return p / 34 * 4.0; };
    var DATA = [34, 33, 30, 29, 25, 22, 21, 16, 12, 7, 0];
    var DEV = [C.indigo, C.violet, C.blue, C.teal];
    var SPLIT = [10, 8, 9, 7];

    /* ---------- Shader for lines that reveal up to "today" ---------- */
    var VS = [
      "varying vec3 vN; varying vec3 vV; varying vec3 vW; varying vec2 vUv;",
      "void main() {",
      "  vUv = uv;",
      "  vec4 w = modelMatrix * vec4(position, 1.0);",
      "  vW = w.xyz;",
      "  vec4 mv = viewMatrix * w;",
      "  vN = normalize(normalMatrix * normal);",
      "  vV = normalize(-mv.xyz);",
      "  gl_Position = projectionMatrix * mv;",
      "}"
    ].join("\n");
    var FS = [
      "uniform float uCut; uniform float uDay; uniform float uAlpha; uniform vec3 uSolid; uniform float uUseSolid;",
      "varying vec3 vN; varying vec3 vV; varying vec3 vW; varying vec2 vUv;",
      "vec3 sgrad(float t) {",
      "  vec3 a = vec3(0.357, 0.361, 0.941); vec3 b = vec3(0.545, 0.361, 0.965); vec3 c = vec3(0.098, 0.902, 0.757);",
      "  return t < 0.55 ? mix(a, b, t / 0.55) : mix(b, c, (t - 0.55) / 0.45);",
      "}",
      "void main() {",
      "  if (vW.x > uCut) discard;",
      "  vec3 col = mix(sgrad(clamp((vW.x + 6.0) / 12.0, 0.0, 1.0)), uSolid, uUseSolid);",
      "  float facing = abs(dot(vN, vV));",
      "  vec3 c = col * (0.55 + 0.45 * facing) + col * pow(1.0 - facing, 2.0) * 0.8 * (1.0 - uDay);",
      "  c += vec3(1.0) * pow(facing, 14.0) * 0.35 * (1.0 - uDay * 0.7);",
      "  gl_FragColor = vec4(c, uAlpha);",
      "}"
    ].join("\n");
    var GLOW_FS = [
      "uniform float uCut; uniform float uAlpha;",
      "varying vec3 vN; varying vec3 vV; varying vec3 vW; varying vec2 vUv;",
      "void main() {",
      "  if (vW.x > uCut) discard;",
      "  float a = pow(abs(dot(vN, vV)), 3.0) * uAlpha;",
      "  vec3 col = mix(vec3(0.357, 0.361, 0.941), vec3(0.098, 0.902, 0.757), clamp((vW.x + 6.0) / 12.0, 0.0, 1.0));",
      "  gl_FragColor = vec4(col, a);",
      "}"
    ].join("\n");
    var lineMats = [];
    function lineMat(solid) {
      var m = new THREE.ShaderMaterial({
        uniforms: { uCut: { value: -99 }, uDay: { value: 1 }, uAlpha: { value: 1 }, uSolid: { value: solid ? solid.clone() : new THREE.Color() }, uUseSolid: { value: solid ? 1 : 0 } },
        vertexShader: VS, fragmentShader: FS, transparent: true
      });
      lineMats.push(m);
      return m;
    }
    kit.onTheme(function (t) { lineMats.forEach(function (m) { m.uniforms.uDay.value = t === "night" ? 0 : 1; }); });

    /* ---------- Floor and chart planes ---------- */
    var slabMat = new THREE.MeshStandardMaterial({ roughness: 0.6, transparent: true });
    kit.onTheme(function (t) { slabMat.color.set(t === "night" ? 0x0c1428 : 0xffffff); slabMat.opacity = t === "night" ? 0.82 : 1; });
    var slab = new THREE.Mesh(kit.slab(15.2, 5.8, 0.14, 0.4), slabMat);
    slab.position.set(-0.4, -0.08, -1.5);
    world.add(slab);
    var shadow = kit.shadow(18, 8, 0.4);
    shadow.position.set(-0.4, -0.2, -1.3);
    world.add(shadow);

    var gridMat = new THREE.LineBasicMaterial({ transparent: true, depthWrite: false });
    kit.onTheme(function (t) { gridMat.color.set(t === "night" ? 0x2a3550 : 0xcfd6e2); gridMat.opacity = t === "night" ? 0.6 : 1; });
    var gp = [];
    for (var d = 0; d <= 10; d++) gp.push(v3(dayX(d), 0.005, -4.1), v3(dayX(d), 0.005, 1.1));
    [0, 10, 20, 30].forEach(function (p) { gp.push(v3(dayX(0), Y(p), -0.02), v3(dayX(10), Y(p), -0.02)); });
    world.add(new THREE.LineSegments(new THREE.BufferGeometry().setFromPoints(gp), gridMat));
    for (d = 0; d <= 10; d += 2) {
      var dl = kit.label((d < 10 ? "D0" : "D") + d, { size: 44, scale: 0.28, night: "#97a1b3", day: "#4b5565" });
      dl.position.set(dayX(d), 0.05, 1.45);
      world.add(dl);
    }
    [10, 20, 30].forEach(function (p) {
      var yl = kit.label(p + " pts", { size: 40, scale: 0.24, night: "#97a1b3", day: "#4b5565" });
      yl.position.set(dayX(0) - 0.5, Y(p), 0);
      world.add(yl);
    });

    var ideal = kit.line([v3(dayX(0), Y(34), 0), v3(dayX(10), 0, 0)], 0x97a1b3, 0.8, true);
    world.add(ideal);

    /* ---------- The burndown line ---------- */
    var pts = DATA.map(function (p, i) { return v3(dayX(i), Y(p), 0); });
    var curve = new THREE.CatmullRomCurve3(pts, false, "catmullrom", 0.3);
    var main = new THREE.Mesh(new THREE.TubeGeometry(curve, 320, 0.095, 12, false), lineMat(null));
    world.add(main);
    var glowMat = new THREE.ShaderMaterial({
      uniforms: { uCut: { value: -99 }, uAlpha: { value: 0.3 } }, vertexShader: VS, fragmentShader: GLOW_FS,
      transparent: true, depthWrite: false, blending: THREE.AdditiveBlending
    });
    var mainGlow = new THREE.Mesh(new THREE.TubeGeometry(curve, 200, 0.3, 14, false), glowMat);
    world.add(mainGlow);
    kit.onTheme(function (t) { mainGlow.visible = t === "night"; });

    // Area under the line, revealed with it.
    var areaPos = [], areaUv = [], areaIdx = [];
    var N = 120;
    for (var i = 0; i <= N; i++) {
      var p = curve.getPoint(i / N);
      areaPos.push(p.x, p.y, -0.01, p.x, 0, -0.01);
      areaUv.push(i / N, 1, i / N, 0);
      if (i < N) areaIdx.push(i * 2, i * 2 + 1, i * 2 + 2, i * 2 + 1, i * 2 + 3, i * 2 + 2);
    }
    var areaGeo = new THREE.BufferGeometry();
    areaGeo.setAttribute("position", new THREE.Float32BufferAttribute(areaPos, 3));
    areaGeo.setAttribute("uv", new THREE.Float32BufferAttribute(areaUv, 2));
    areaGeo.setIndex(areaIdx);
    areaGeo.computeVertexNormals();
    var areaMat = new THREE.ShaderMaterial({
      uniforms: { uCut: { value: -99 }, uColor: { value: C.violet.clone() }, uAlpha: { value: 0.3 } },
      vertexShader: VS,
      fragmentShader: "uniform float uCut; uniform vec3 uColor; uniform float uAlpha; varying vec3 vW; varying vec2 vUv; varying vec3 vN; varying vec3 vV;\nvoid main(){ if (vW.x > uCut) discard; gl_FragColor = vec4(uColor, uAlpha * vUv.y); }",
      transparent: true, depthWrite: false, side: THREE.DoubleSide
    });
    world.add(new THREE.Mesh(areaGeo, areaMat));
    kit.onTheme(function (t) { areaMat.uniforms.uAlpha.value = t === "night" ? 0.3 : 0.22; });

    /* ---------- Forecast band (the envelope) ---------- */
    var BN = 30;
    var bandGeo = new THREE.BufferGeometry();
    bandGeo.setAttribute("position", new THREE.BufferAttribute(new Float32Array((BN + 1) * 2 * 3), 3));
    var bIdx = [];
    for (i = 0; i < BN; i++) bIdx.push(i * 2, i * 2 + 1, i * 2 + 2, i * 2 + 1, i * 2 + 3, i * 2 + 2);
    bandGeo.setIndex(bIdx);
    var bandMat = new THREE.MeshBasicMaterial({ color: C.violet, transparent: true, depthWrite: false, side: THREE.DoubleSide });
    var band = new THREE.Mesh(bandGeo, bandMat);
    world.add(band);
    var mid = kit.line([v3(0, 0, 0.02), v3(1, 0, 0.02)], C.violet, 0.9, true);
    world.add(mid);
    kit.onTheme(function (t) { bandMat.blending = t === "night" ? THREE.AdditiveBlending : THREE.NormalBlending; });

    function lineY(day) { return curve.getPoint(clamp(day / 10, 0, 1)).y; }
    function updateBand(today, breathe) {
      var arr = bandGeo.attributes.position.array;
      var y0 = lineY(today);
      for (var k = 0; k <= BN; k++) {
        var dd = lerp(today, 10, k / BN);
        var f = (dd - today) / Math.max(0.001, 10 - today);
        var c = y0 * (1 - f);
        var w = (dd - today) * 0.17 * breathe;
        var x = dayX(dd);
        arr[k * 6] = x; arr[k * 6 + 1] = Math.max(0, c - w); arr[k * 6 + 2] = 0.01;
        arr[k * 6 + 3] = x; arr[k * 6 + 4] = c + w; arr[k * 6 + 5] = 0.01;
      }
      bandGeo.attributes.position.needsUpdate = true;
      bandGeo.computeBoundingSphere();
      var mp = mid.geometry.attributes.position.array;
      mp[0] = dayX(today); mp[1] = y0; mp[3] = dayX(10); mp[4] = 0;
      mid.geometry.attributes.position.needsUpdate = true;
      mid.computeLineDistances();
    }

    /* ---------- Today marker ---------- */
    var dot = new THREE.Mesh(new THREE.SphereGeometry(0.13, 20, 16), new THREE.MeshBasicMaterial({ color: 0xffffff }));
    world.add(dot);
    var dotGlow = kit.glow(C.aqua, 1.4, 0.9, 0.55);
    world.add(dotGlow);
    var playMat = new THREE.MeshBasicMaterial({ color: C.aqua, transparent: true, depthWrite: false, side: THREE.DoubleSide });
    var play = new THREE.Mesh(new THREE.PlaneGeometry(5.4, 4.6), playMat);
    play.rotation.y = Math.PI / 2;
    world.add(play);
    var todayTag = kit.label("TODAY", { size: 40, scale: 0.26, bg: ["#19e6c1", "#0a7a68"], night: "#07090e", day: "#ffffff" });
    world.add(todayTag);

    /* ---------- M1: Fibonacci tower = starting height ---------- */
    var FIB = [13, 8, 5, 3, 2, 1, 1, 1];
    var towerX = dayX(0) - 1.0;
    var base = 0;
    var blocks = FIB.map(function (p, j) {
      var h = Y(p) - 0.04;
      var col = new THREE.Color().copy(C.indigo).lerp(C.violet, j / FIB.length);
      var m = new THREE.Mesh(kit.roundedBox(0.62, h, 0.62, 0.06), new THREE.MeshStandardMaterial({ color: col, emissive: col, emissiveIntensity: 0.2, roughness: 0.35 }));
      var target = base + h / 2 + 0.02;
      base += Y(p);
      world.add(m);
      var tag = kit.label(String(p), { size: 46, scale: Math.min(0.3, 0.16 + h * 0.2), night: "#ffffff", day: "#ffffff" });
      world.add(tag);
      return { mesh: m, tag: tag, y: target, h: h, j: j };
    });

    /* ---------- M3: per-developer ribbons ---------- */
    var ribbons = SPLIT.map(function (share, i) {
      var z = -0.85 - i * 0.7;
      var dp = DATA.map(function (p, dd) {
        var wobble = Math.sin(dd * 1.3 + i * 2) * 0.6;
        return v3(dayX(dd), Y(Math.max(0, share * p / 34 + (dd > 0 && dd < 10 ? wobble : 0))), z);
      });
      var cv = new THREE.CatmullRomCurve3(dp, false, "catmullrom", 0.3);
      var mesh = new THREE.Mesh(new THREE.TubeGeometry(cv, 160, 0.04, 8, false), lineMat(DEV[i]));
      world.add(mesh);
      var orb = new THREE.Mesh(new THREE.SphereGeometry(0.15, 18, 14), new THREE.MeshStandardMaterial({ color: DEV[i], emissive: DEV[i], emissiveIntensity: 0.4 }));
      orb.position.copy(dp[0]).add(v3(-0.35, 0, 0));
      world.add(orb);
      return { mesh: mesh, orb: orb };
    });

    /* ---------- M4: cost burn-up wall ---------- */
    var wall = new THREE.Group();
    wall.position.set(0, 0, -3.75);
    world.add(wall);
    var wallMat = new THREE.MeshStandardMaterial({ transparent: true, roughness: 0.4 });
    kit.onTheme(function (t) { wallMat.color.set(t === "night" ? 0x0f1a33 : 0xe9eef5); });
    var wallMesh = new THREE.Mesh(kit.roundedBox(13.4, 4.6, 0.08, 0.3), wallMat);
    wallMesh.position.set(-0.1, 2.35, -0.05);
    wall.add(wallMesh);
    var cy = function (v) { return 0.35 + v * 3.6; };
    var planned = kit.line([v3(dayX(0), cy(0), 0.04), v3(dayX(10), cy(1), 0.04)], 0x97a1b3, 0.9, true);
    wall.add(planned);
    var costPts = [];
    for (var k = 0; k <= 10; k++) costPts.push(v3(dayX(k), cy(k / 10 * 1.06 + Math.sin(k * 1.4) * 0.015), 0.06));
    var costCurve = new THREE.CatmullRomCurve3(costPts);
    var costMat = lineMat(C.blue);
    var cost = new THREE.Mesh(new THREE.TubeGeometry(costCurve, 120, 0.05, 8, false), costMat);
    wall.add(cost);
    var refc = kit.line([v3(0, 0, 0.07), v3(1, 0, 0.07)], C.violet, 1, true);
    wall.add(refc);
    var costTitle = kit.label("COST · PLANNED, SPENT, RE-FORECAST", { size: 40, scale: 0.26, night: "#97a1b3", day: "#4b5565" });
    costTitle.position.set(dayX(0) + 1.9, 4.35, 0.1);
    wall.add(costTitle);

    /* ---------- M2: the storm over Day 07 ---------- */
    var SN = kit.narrow ? 260 : 620;
    var sPos = new Float32Array(SN * 3), sCol = new Float32Array(SN * 3);
    for (i = 0; i < SN; i++) {
      var gx = (Math.random() + Math.random() - 1) * 1.8;
      sPos[i * 3] = dayX(7) + gx;
      sPos[i * 3 + 1] = 3.1 + (Math.random() + Math.random() - 1) * 0.55;
      sPos[i * 3 + 2] = (Math.random() + Math.random() - 1) * 1.2;
      var cc = Math.abs(gx) < 0.6 && Math.random() < 0.6 ? C.red : (Math.random() < 0.7 ? C.amber : C.mist);
      sCol[i * 3] = cc.r; sCol[i * 3 + 1] = cc.g; sCol[i * 3 + 2] = cc.b;
    }
    var stormGeo = new THREE.BufferGeometry();
    stormGeo.setAttribute("position", new THREE.BufferAttribute(sPos, 3));
    stormGeo.setAttribute("color", new THREE.BufferAttribute(sCol, 3));
    var stormMat = new THREE.PointsMaterial({ size: 0.13, map: kit.glowTex, vertexColors: true, transparent: true, depthWrite: false, sizeAttenuation: true });
    var storm = new THREE.Points(stormGeo, stormMat);
    world.add(storm);
    kit.onTheme(function (t) { stormMat.blending = t === "night" ? THREE.AdditiveBlending : THREE.NormalBlending; stormMat.size = t === "night" ? 0.13 : 0.1; });
    var stormGlow = kit.glow(C.red, 3.2, 0.5, 0.35);
    stormGlow.position.set(dayX(7), 3.1, 0);
    world.add(stormGlow);
    var bolt = kit.line([v3(dayX(7) - 0.1, 2.9, 0.2), v3(dayX(7) + 0.15, 2.35, 0.2), v3(dayX(7) - 0.05, 2.25, 0.2), v3(dayX(7) + 0.12, Y(16) + 0.25, 0.2)], C.amber, 0, false);
    world.add(bolt);

    /* ---------- Copilot orb ---------- */
    var orbPos = v3(-0.5, 6.4, 1.2);
    var orb = new THREE.Mesh(new THREE.SphereGeometry(0.2, 24, 18), new THREE.MeshBasicMaterial({ color: 0xc8fff4, transparent: true }));
    orb.position.copy(orbPos);
    world.add(orb);
    var orbGlow = kit.glow(C.aqua, 2.2, 0.8, 0.5);
    orbGlow.position.copy(orbPos);
    world.add(orbGlow);
    var thToday = kit.line([orbPos, v3(0, 0, 0)], C.aqua, 0, false);
    var thStorm = kit.line([orbPos, v3(dayX(7), 3.4, 0)], C.aqua, 0, false);
    var thCost = kit.line([orbPos, v3(dayX(10), cy(1.08), -3.7)], C.aqua, 0, false);
    world.add(thToday, thStorm, thCost);

    /* ---------- Ship ---------- */
    var chevGeo = kit.chevronGeometry(2.4);
    var chevrons = [0, 1, 2].map(function () {
      var m = new THREE.Mesh(chevGeo, new THREE.MeshBasicMaterial({ color: C.aqua, transparent: true, opacity: 0 }));
      m.rotation.x = -Math.PI / 2;
      world.add(m);
      return m;
    });
    var shipGlow = kit.glow(C.aqua, 5, 0.7, 0.4);
    shipGlow.position.set(dayX(10), 0.2, 0);
    world.add(shipGlow);

    var stars = kit.stars(kit.narrow ? 900 : 2200);
    kit.onTheme(function (t) { stars.visible = t === "night"; });

    /* ---------- Camera stops ---------- */
    var stops = [
      { pos: v3(9.6, 7.8, 22.5), look: v3(-5.2, 1.3, -1.0) },    // 0 hero
      { pos: v3(0.2, 4.6, 12.0), look: v3(-9.4, 2.2, -0.6) },    // 1 problem: Day 00, a wide guess
      { pos: v3(5.0, 7.0, 21.0), look: v3(-3.0, 2.0, -1.0) },    // 2 platform
      { pos: v3(-1.6, 3.6, 9.4), look: v3(-10.4, 2.0, -0.4) },   // 3 M1: the Fibonacci tower
      { pos: v3(4.0, 8.6, 12.0), look: v3(-5.6, 0.8, -1.9) },    // 4 M3: developer ribbons
      { pos: v3(3.0, 4.4, 9.2), look: v3(-4.6, 2.0, -3.7) },     // 5 M4: cost wall
      { pos: v3(9.0, 5.2, 9.6), look: v3(-0.6, 2.4, -0.3) },     // 6 M2: the storm
      { pos: v3(6.0, 11.0, 18.0), look: v3(-4.6, 2.4, -1.0) },   // 7 Copilot
      { pos: v3(13.0, 4.0, 8.6), look: v3(1.6, 1.0, -0.7) }      // 8 horizon
    ];

    function update(st) {
      var s = st.s, t = st.t;
      var night = st.theme === "night";
      var today = 3 - 3 * sm(0.15, 0.9, s) + sm(2.2, 3.0, s) + sm(3.2, 4.0, s) +
        3 * sm(4.2, 5.0, s) + 2 * sm(5.2, 6.0, s) + sm(6.2, 7.0, s) + 2 * sm(7.1, 7.95, s);
      var cut = dayX(today) + 0.001;
      var ship = sm(7.2, 7.95, s);

      lineMats.forEach(function (m) { m.uniforms.uCut.value = cut; });
      glowMat.uniforms.uCut.value = cut;
      glowMat.uniforms.uAlpha.value = 0.3 - st.dawn * 0.1;
      areaMat.uniforms.uCut.value = cut;
      costMat.uniforms.uCut.value = cut;

      var breathe = 1 + Math.sin(t * 1.2) * 0.06 * (s < 1 ? 1 : 0.3);
      updateBand(today, breathe);
      bandMat.opacity = (night ? 0.22 : 0.16) * (1 - ship);
      mid.material.opacity = 0.9 * (1 - ship);
      band.visible = today < 9.98;

      var y0 = lineY(today);
      dot.position.set(dayX(today), y0, 0);
      dotGlow.position.copy(dot.position);
      kit.glowOpacity(dotGlow, 0.8 + Math.sin(t * 3) * 0.2);
      play.position.set(dayX(today), 2.3, -1.4);
      playMat.opacity = night ? 0.08 : 0.06;
      todayTag.position.set(dayX(today), 4.9, -1.4);

      // M1: blocks drop into the starting tower.
      blocks.forEach(function (b) {
        var m = sm(2.0 + b.j * 0.07, 2.55 + b.j * 0.07, s);
        b.mesh.position.set(towerX, b.y + (1 - m) * 3.2, 0);
        b.mesh.scale.setScalar(Math.max(0.001, m));
        b.mesh.visible = m > 0.001;
        b.tag.position.set(towerX, b.mesh.position.y, 0.34);
        b.tag.material.opacity = m;
        b.tag.visible = m > 0.01 && b.h > 0.2;
      });

      // M3: ribbons per developer.
      var m3 = sm(3.05, 3.8, s);
      ribbons.forEach(function (r, i) {
        r.mesh.material.uniforms.uAlpha.value = m3;
        r.mesh.visible = m3 > 0.01;
        r.orb.scale.setScalar(Math.max(0.001, m3));
        r.orb.position.y = Math.sin(t * 1.5 + i) * 0.05 + r.mesh.geometry.parameters.path.getPoint(0).y;
      });

      // M4: cost wall with re-forecast from today.
      var m4 = sm(4.0, 4.7, s) * (1 - sm(7.6, 8, s) * 0.5);
      wall.visible = m4 > 0.01;
      wallMat.opacity = m4 * (night ? 0.75 : 0.95);
      planned.material.opacity = m4 * 0.9;
      costMat.uniforms.uAlpha.value = m4;
      costTitle.material.opacity = m4;
      var cNow = costCurve.getPoint(clamp(today / 10, 0, 1));
      var rp = refc.geometry.attributes.position.array;
      rp[0] = cNow.x; rp[1] = cNow.y; rp[3] = dayX(10); rp[4] = cy(1.1);
      refc.geometry.attributes.position.needsUpdate = true;
      refc.computeLineDistances();
      refc.material.opacity = m4 * (today < 9.9 ? 1 : 0);

      // M2: the storm.
      var risk = sm(5.1, 5.9, s) * (1 - sm(7.4, 7.9, s));
      stormMat.opacity = risk * (night ? 0.85 : 0.75);
      storm.visible = risk > 0.01;
      storm.rotation.y = Math.sin(t * 0.3) * 0.04;
      storm.position.y = Math.sin(t * 0.8) * 0.06;
      kit.glowOpacity(stormGlow, risk * (0.6 + Math.sin(t * Math.PI / 0.8) * 0.4));
      bolt.material.opacity = risk * (Math.sin(t * 7) > 0.92 ? 1 : 0);

      // Copilot.
      var cp = sm(6.1, 6.9, s) * (1 - sm(7.3, 7.9, s));
      orb.material.opacity = cp;
      orb.visible = cp > 0.01;
      kit.glowOpacity(orbGlow, cp * (0.8 + Math.sin(t * 2.4) * 0.2));
      var tp = thToday.geometry.attributes.position.array;
      tp[3] = dot.position.x; tp[4] = dot.position.y; tp[5] = dot.position.z;
      thToday.geometry.attributes.position.needsUpdate = true;
      thToday.material.opacity = cp * sm(0.3, 0.45, st.copilot);
      thStorm.material.opacity = cp * sm(0.52, 0.64, st.copilot);
      thCost.material.opacity = cp * sm(0.64, 0.78, st.copilot);

      // Ship.
      chevrons.forEach(function (m, i) {
        var phase = (t * 0.7 + i * 0.22) % 1;
        m.position.set(dayX(10) + 0.7 + i * 0.55 + phase * 0.4 * ship, 0.15, 0);
        m.material.opacity = ship * [0.5, 0.75, 1][i];
        m.visible = ship > 0.01;
      });
      kit.glowOpacity(shipGlow, ship * (0.8 + Math.sin(t * 2) * 0.2));

      stars.material.opacity = 0.9 * (1 - st.dawn * 0.85);
      world.rotation.y = st.narrow ? Math.sin(t * 0.1) * 0.12 : 0;
    }

    return { stops: stops, center: v3(-0.8, 2.0, -1.2), narrowDistance: 22, update: update };
  });
})();
