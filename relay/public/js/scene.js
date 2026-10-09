// us2us 3D backdrop (two cups, one line), from the design's us2us-scene.js.
// Scroll-driven via [data-scene]; loaded by site.js; three.js from this origin.
// Kept light on the battery: at most 1.5× device pixels (and about 2400 px
// across), full frame rate only while the visitor scrolls or moves the
// pointer and 30 fps otherwise, and no frames at all while the tab is hidden
// or no [data-scene] section is on screen.
import * as THREE from './vendor/three-0.160.0.module.min.js'

const PAL = {
  dark: { cup: 0x2b2e3e, edge: 0x9184d9, line: 0xb5abfc, core: 0xe7e5fe, glow: 0x9184d9, dust: 0x9397ab, dead: 0x75798c, sky: 0x5d5294, ground: 0x161826, key: 0xe9e9ed, rimL: 0x9184d9, add: true, wireOp: 0.34, dustOp: 0.5, glowOp: 0.10 },
  light: { cup: 0xdfe2f0, edge: 0x796cbf, line: 0x5d5294, core: 0x5d5294, glow: 0x796cbf, dust: 0x75798c, dead: 0x9397ab, sky: 0xf3f5fe, ground: 0xb2b6ca, key: 0xf3f5fe, rimL: 0xb5abfc, add: false, wireOp: 0.55, dustOp: 0.32, glowOp: 0.14 },
}

const S = {
  hero: { cx: 0, cy: 0.55, cz: 7.2, tx: 0, ty: 0, tz: 0, wx: 1.45, ry: -0.5, L: 0.95, flow: 1, back: 0, tunnel: 0, seal: 0, story: 0, dim: 0 },
  line: { cx: 0, cy: 0.32, cz: 3.7, tx: 0, ty: 0, tz: 0, wx: 0.95, ry: -0.24, L: 1.2, flow: 1, back: 0, tunnel: 1, seal: 1, story: 0, dim: 0 },
  status: { cx: 0, cy: 0.42, cz: 4.9, tx: 0, ty: 0, tz: 0, wx: 1.3, ry: -0.12, L: 1.2, flow: 0, back: 0, tunnel: 0.5, seal: 0, story: 1, dim: 0 },
  ghost: { cx: 0, cy: 0.75, cz: 6.0, tx: 0, ty: 0, tz: 0, wx: 1.4, ry: -0.42, L: 1.0, flow: 0.3, back: 0, tunnel: 0, seal: 0, story: 0, dim: 0 },
  features: { cx: 0, cy: 1.9, cz: 9.5, tx: 0, ty: 0, tz: 0, wx: 0, ry: 0.2, L: 1.0, flow: 0.7, back: 0.5, tunnel: 0.3, seal: 0, story: 0, dim: 0.72 },
  verify: { cx: -2.3, cy: 0.62, cz: 1.5, tx: 0.9, ty: -0.05, tz: -0.6, wx: 0, ry: 0, L: 1.2, flow: 1, back: 0, tunnel: 1, seal: 1, story: 0, dim: 0.25 },
  cta: { cx: 0, cy: 0.35, cz: 5.6, tx: 0, ty: 0, tz: 0, wx: 1.3, ry: -0.6, L: 0.62, flow: 1, back: 1, tunnel: 0.35, seal: 0, story: 0, dim: 0 },
}
const KEYS = Object.keys(S.hero)
const clamp = (x, a, b) => Math.min(b, Math.max(a, x))
const sm = (a, b, x) => { const t = clamp((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t) }
const lerp = (a, b, t) => a + (b - a) * t

/** Starts the scene on `canvas` (its WebGL context already created by site.js). Returns { setTheme, dispose }. */
export function mountUs2usScene(canvas, opts = {}) {
  const renderer = new THREE.WebGLRenderer({ canvas, context: opts.context, alpha: true })
  const pixelRatio = (w) => Math.max(1, Math.min(window.devicePixelRatio || 1, 1.5, 2400 / Math.max(1, w)))
  renderer.setClearColor(0x000000, 0)

  const scene = new THREE.Scene()
  const camera = new THREE.PerspectiveCamera(34, 1, 0.05, 100)
  const world = new THREE.Group(); scene.add(world)
  const hemi = new THREE.HemisphereLight(0x5d5294, 0x161826, 1.1); scene.add(hemi)
  const key = new THREE.DirectionalLight(0xe9e9ed, 1.7); key.position.set(-3, 4, 5); scene.add(key)
  const rimLight = new THREE.DirectionalLight(0x9184d9, 2.4); rimLight.position.set(3, 1.5, -4); scene.add(rimLight)

  const dotTex = (() => {
    const c = document.createElement('canvas'); c.width = c.height = 64
    const g = c.getContext('2d'); const gr = g.createRadialGradient(32, 32, 0, 32, 32, 32)
    gr.addColorStop(0, 'rgba(255,255,255,1)'); gr.addColorStop(0.22, 'rgba(255,255,255,0.6)'); gr.addColorStop(1, 'rgba(255,255,255,0)')
    g.fillStyle = gr; g.fillRect(0, 0, 64, 64)
    const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; return t
  })()
  const lines = (p) => { const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.Float32BufferAttribute(p, 3)); return g }

  // ── cups
  const H = 1.1, RB = 0.36, RT = 0.52, W = 0.025, V2 = THREE.Vector2
  const cupGeo = new THREE.LatheGeometry([new V2(0.001, 0), new V2(RB, 0), new V2(RT, H), new V2(RT - W, H), new V2(RB - W, W), new V2(0.001, W)], 72)
  const cupMat = new THREE.MeshStandardMaterial({ color: 0x2b2e3e, roughness: 0.62, metalness: 0.08, side: THREE.DoubleSide, transparent: true })
  const rimGeo = new THREE.TorusGeometry(RT - W / 2, 0.018, 10, 96)
  const rimMatA = new THREE.MeshBasicMaterial({ color: 0x9184d9, transparent: true })
  const rimMatB = new THREE.MeshBasicMaterial({ color: 0x9184d9, transparent: true })
  const wireMat = new THREE.LineBasicMaterial({ color: 0x9184d9, transparent: true, opacity: 0.34, depthWrite: false })
  const wireGeo = (() => {
    const p = [], n = 18, k = 1.004
    for (let i = 0; i < n; i++) { const a = i / n * Math.PI * 2, s = Math.sin(a), c = Math.cos(a); p.push(RB * k * s, 0, RB * k * c, RT * k * s, H, RT * k * c) }
    for (const y of [0, 0.33, 0.66]) {
      const r = (RB + (RT - RB) * y) * k, yy = y * H, m = 72
      for (let i = 0; i < m; i++) { const a0 = i / m * Math.PI * 2, a1 = (i + 1) / m * Math.PI * 2; p.push(r * Math.sin(a0), yy, r * Math.cos(a0), r * Math.sin(a1), yy, r * Math.cos(a1)) }
    }
    return lines(p)
  })()
  const makeCup = (rimMat) => {
    const g = new THREE.Group()
    g.add(new THREE.Mesh(cupGeo, cupMat))
    const r = new THREE.Mesh(rimGeo, rimMat); r.rotation.x = Math.PI / 2; r.position.y = H; g.add(r)
    g.add(new THREE.LineSegments(wireGeo, wireMat))
    return g
  }
  const cupA = makeCup(rimMatA); cupA.rotation.z = Math.PI / 2; world.add(cupA)
  const cupB = makeCup(rimMatB); cupB.rotation.z = -Math.PI / 2; world.add(cupB)

  // ── line
  const strGeo = new THREE.CylinderGeometry(0.009, 0.009, 1, 8, 1, true); strGeo.rotateZ(Math.PI / 2)
  const strMat = new THREE.MeshBasicMaterial({ color: 0xb5abfc, transparent: true })
  const str = new THREE.Mesh(strGeo, strMat); world.add(str)
  const glowMats = [], coreMats = []
  const glowLineMat = new THREE.PointsMaterial({ map: dotTex, size: 0.24, color: 0x9184d9, transparent: true, opacity: 0.1, depthWrite: false, blending: THREE.AdditiveBlending })
  glowMats.push(glowLineMat)
  const glowLine = (() => {
    const n = 180, p = new Float32Array(n * 3)
    for (let i = 0; i < n; i++) p[i * 3] = i / (n - 1) - 0.5
    const g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.BufferAttribute(p, 3))
    return new THREE.Points(g, glowLineMat)
  })()
  world.add(glowLine)

  // tunnel lattice
  const latMat = new THREE.LineBasicMaterial({ color: 0x9184d9, transparent: true, opacity: 0, depthWrite: false })
  const lattice = (() => {
    const p = [], r = 0.3, rings = 13
    const v = (j, i) => { const a = (i + (j % 2) * 0.5) / 6 * Math.PI * 2; return [j / (rings - 1) - 0.5, r * Math.cos(a), r * Math.sin(a)] }
    for (let j = 0; j < rings; j++) for (let i = 0; i < 6; i++) {
      p.push(...v(j, i), ...v(j, (i + 1) % 6))
      if (j < rings - 1) { p.push(...v(j, i), ...v(j + 1, i)); p.push(...v(j, (i + 1) % 6), ...v(j + 1, i)) }
    }
    return new THREE.LineSegments(lines(p), latMat)
  })()
  world.add(lattice)
  const hexRing = (r) => {
    const p = []
    for (let i = 0; i < 6; i++) { const a0 = i / 6 * Math.PI * 2, a1 = (i + 1) / 6 * Math.PI * 2; p.push(0, r * Math.cos(a0), r * Math.sin(a0), 0, r * Math.cos(a1), r * Math.sin(a1)) }
    return lines(p)
  }
  const scanMat = new THREE.LineBasicMaterial({ color: 0xb5abfc, transparent: true, opacity: 0, depthWrite: false })
  const scan = new THREE.LineSegments(hexRing(0.3), scanMat); world.add(scan)
  const relayMat = new THREE.LineBasicMaterial({ color: 0x9184d9, transparent: true, opacity: 0, depthWrite: false })
  const relay = new THREE.LineSegments(hexRing(0.13), relayMat); world.add(relay)

  // ── pulses
  const mkSprite = (s) => {
    const m = new THREE.SpriteMaterial({ map: dotTex, color: 0xe7e5fe, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, opacity: 0 })
    glowMats.push(m); coreMats.push(m)
    const sp = new THREE.Sprite(m); sp.scale.setScalar(s); world.add(sp); return sp
  }
  const NP = 4, TR = 4
  const sealGeo = new THREE.EdgesGeometry(new THREE.OctahedronGeometry(0.075))
  const sealMat = new THREE.LineBasicMaterial({ color: 0xb5abfc, transparent: true, opacity: 0, depthWrite: false })
  const mkPulse = () => ({ head: mkSprite(0.2), trail: Array.from({ length: TR }, (_, j) => mkSprite(0.16 - j * 0.02)), seal: (() => { const s = new THREE.LineSegments(sealGeo, sealMat); world.add(s); return s })() })
  const fwd = Array.from({ length: NP }, mkPulse)
  const bwd = Array.from({ length: NP }, mkPulse)
  const story = mkSprite(0.28), receipt = mkSprite(0.16)
  const storySeal = new THREE.LineSegments(sealGeo, sealMat); storySeal.scale.setScalar(1.3); world.add(storySeal)
  const endA = mkSprite(0.55), endB = mkSprite(0.55)
  const cEdge = new THREE.Color(), cCore = new THREE.Color(), cDead = new THREE.Color(), cTmp = new THREE.Color()

  // ── dust
  const DN = 420, dPos = new Float32Array(DN * 3)
  for (let i = 0; i < DN; i++) { dPos[i * 3] = (Math.random() - 0.5) * 16; dPos[i * 3 + 1] = (Math.random() - 0.5) * 10; dPos[i * 3 + 2] = -6 + Math.random() * 9 }
  const dGeo = new THREE.BufferGeometry(); dGeo.setAttribute('position', new THREE.BufferAttribute(dPos, 3))
  const dustMat = new THREE.PointsMaterial({ map: dotTex, size: 0.045, color: 0x9397ab, transparent: true, opacity: 0.5, depthWrite: false })
  scene.add(new THREE.Points(dGeo, dustMat))

  // ── ghost particles
  const GN = 2600, gPos = new Float32Array(GN * 3), gI = new Float32Array(GN * 8)
  for (let i = 0; i < GN; i++) {
    const dx = Math.random() * 2 - 1, dy = Math.random() * 1.4 - 0.2, dz = Math.random() * 2 - 1; const l = Math.hypot(dx, dy, dz) || 1
    gI.set([i < 1100 ? 0 : i < 2200 ? 1 : 2, Math.random() * H, Math.random() * Math.PI * 2, dx / l, dy / l, dz / l, 0.6 + Math.random() * 1.4, Math.random() * 6.28], i * 8)
  }
  const gGeo = new THREE.BufferGeometry(); gGeo.setAttribute('position', new THREE.BufferAttribute(gPos, 3))
  const ghostMat = new THREE.PointsMaterial({ map: dotTex, size: 0.05, color: 0xb5abfc, transparent: true, opacity: 0, depthWrite: false, blending: THREE.AdditiveBlending })
  glowMats.push(ghostMat)
  const ghost = new THREE.Points(gGeo, ghostMat); ghost.frustumCulled = false; world.add(ghost)

  // ── theme
  let P = PAL.dark
  function setTheme(name) {
    P = PAL[name] || PAL.dark
    cupMat.color.setHex(P.cup); wireMat.color.setHex(P.edge); strMat.color.setHex(P.line)
    latMat.color.setHex(P.edge); relayMat.color.setHex(P.edge); sealMat.color.setHex(P.line); scanMat.color.setHex(P.line)
    const bl = P.add ? THREE.AdditiveBlending : THREE.NormalBlending
    for (const m of glowMats) { m.blending = bl; m.needsUpdate = true }
    for (const m of coreMats) m.color.setHex(P.core)
    glowLineMat.color.setHex(P.glow); ghostMat.color.setHex(P.add ? 0xb5abfc : P.edge); dustMat.color.setHex(P.dust)
    hemi.color.setHex(P.sky); hemi.groundColor.setHex(P.ground); key.color.setHex(P.key); rimLight.color.setHex(P.rimL)
    cEdge.setHex(P.edge); cCore.setHex(P.core); cDead.setHex(P.dead)
  }
  setTheme(opts.theme || 'dark')

  // ── scroll → state
  const sections = [...document.querySelectorAll('[data-scene]')]
  function readScroll() {
    const vh = window.innerHeight || 800; let s = null; const q = {}
    for (const el of sections) {
      const name = el.getAttribute('data-scene'), st = S[name]; if (!st) continue
      const r = el.getBoundingClientRect()
      q[name] = clamp(-r.top / Math.max(1, r.height - vh), 0, 1)
      if (!s) { s = Object.assign({}, st); continue }
      const e = sm(0, 1, (vh * 0.92 - r.top) / (vh * 0.8))
      if (e > 0) for (const k of KEYS) s[k] = lerp(s[k], st[k], e)
    }
    return [s || Object.assign({}, S.hero), q]
  }

  let raf = 0, last = performance.now(), time = 0, mx = 0, my = 0, tmx = 0, tmy = 0, w0 = 0, h0 = 0, lastOp = -1
  let lastInput = 0, lastDraw = 0
  const onPointer = e => { tmx = e.clientX / innerWidth - 0.5; tmy = e.clientY / innerHeight - 0.5; lastInput = performance.now() }
  const onScroll = () => { lastInput = performance.now() }
  window.addEventListener('pointermove', onPointer, { passive: true })
  window.addEventListener('scroll', onScroll, { passive: true })

  function placePulse(pu, t, amp, L, sealAmt, dir) {
    const x = -L + 2 * L * t
    pu.head.position.set(x, 0, 0); pu.head.material.opacity = amp
    pu.trail.forEach((sp, j) => { const tj = clamp(t - dir * (j + 1) * 0.014, 0, 1); sp.position.set(-L + 2 * L * tj, 0, 0); sp.material.opacity = amp * (0.55 - j * 0.12) })
    pu.seal.position.set(x, 0, 0); pu.seal.scale.setScalar(0.001 + amp * sealAmt)
    pu.seal.rotation.set(time * 1.1, time * 0.8, 0)
  }

  function frame(now) {
    raf = requestAnimationFrame(frame)
    // Ambient motion needs no more than 30 fps; scrolling gets every frame.
    if (now - lastInput > 1000 && now - lastDraw < 1000 / 30 - 2) return
    lastDraw = now
    const dt = Math.min(0.05, (now - last) / 1000); last = now; time += dt
    const w = canvas.clientWidth | 0, h = canvas.clientHeight | 0; if (!w || !h) return
    if (w !== w0 || h !== h0) { w0 = w; h0 = h; renderer.setPixelRatio(pixelRatio(w)); renderer.setSize(w, h, false); camera.aspect = w / h; camera.updateProjectionMatrix() }
    const [s, q] = readScroll()
    const narrow = clamp((1.2 - w / h) / 0.6, 0, 1)
    mx += (tmx - mx) * 0.05; my += (tmy - my) * 0.05
    const kk = 1 + narrow * 0.75
    camera.position.set(s.tx + (s.cx - s.tx) * kk + mx * 0.35, s.ty + (s.cy - s.ty) * kk - my * 0.2, s.tz + (s.cz - s.tz) * kk)
    camera.lookAt(s.tx, s.ty, s.tz)
    world.position.x = s.wx * (1 - narrow)
    world.rotation.y = s.ry + Math.sin(time * 0.3) * 0.07
    world.rotation.x = Math.sin(time * 0.21) * 0.03
    const op = Math.round((1 - s.dim * 0.8) * (1 - narrow * 0.45) * 100) / 100
    if (op !== lastOp) { canvas.style.opacity = String(op); lastOp = op }

    const gq = q.ghost
    const d = gq == null ? 0 : sm(0.06, 0.5, gq) * (1 - sm(0.68, 0.95, gq))
    const solid = 1 - sm(0, 0.25, d), L = s.L

    cupA.position.x = -L; cupB.position.x = L
    cupA.visible = cupB.visible = solid > 0.01
    cupMat.opacity = solid; wireMat.opacity = P.wireOp * solid; rimMatA.opacity = solid; rimMatB.opacity = solid
    str.scale.x = 2 * L; strMat.opacity = solid
    glowLine.scale.x = 2 * L; glowLineMat.opacity = P.glowOp * solid

    const tun = s.tunnel * solid
    lattice.scale.set(2 * L * 0.78, 0.55 + 0.45 * s.tunnel, 0.55 + 0.45 * s.tunnel)
    lattice.rotation.x = time * 0.25; latMat.opacity = tun * 0.42; lattice.visible = latMat.opacity > 0.004
    const sp = (time * 0.32) % 1
    scan.position.x = (sp - 0.5) * 2 * L * 0.78; scan.scale.set(1, 0.55 + 0.45 * s.tunnel, 0.55 + 0.45 * s.tunnel)
    scan.rotation.x = lattice.rotation.x; scanMat.opacity = tun * 0.9 * Math.sin(sp * Math.PI); scan.visible = scanMat.opacity > 0.004

    for (let i = 0; i < NP; i++) {
      const t = (time * 0.17 + i / NP) % 1
      placePulse(fwd[i], t, s.flow * sm(0, 0.08, t) * (1 - sm(0.9, 1, t)) * solid, L, s.seal, 1)
      const tb = 1 - ((time * 0.14 + i / NP + 0.13) % 1)
      placePulse(bwd[i], tb, s.back * sm(0, 0.08, 1 - tb) * (1 - sm(0.9, 1, 1 - tb)) * solid, L, s.seal, -1)
    }

    // message-status story
    let rA = 0, rB = 0
    const qs = q.status == null ? 0 : q.status, sv = s.story * solid
    if (sv > 0.01) {
      let tt, dead = 0, so = 1, scl = 0.28, rc = -1
      if (qs < 0.25) { tt = 0.5 * sm(0, 0.17, qs); if (qs > 0.17) scl = 0.28 + 0.05 * Math.sin(time * 4) }
      else if (qs < 0.5) tt = 0.5 + 0.5 * sm(0.25, 0.4, qs)
      else if (qs < 0.75) { tt = 1; rc = 1 - sm(0.5, 0.66, qs) }
      else { tt = 0.5; dead = sm(0.75, 0.8, qs); so = 1 - sm(0.82, 0.96, qs) }
      rB = sm(0.38, 0.42, qs) * (1 - sm(0.74, 0.76, qs))
      rA = rc >= 0 ? sm(0.6, 0.66, qs) * (1 - sm(0.74, 0.76, qs)) : 0
      story.position.set(-L + 2 * L * tt, 0, 0); story.scale.setScalar(scl)
      story.material.opacity = sv * so; story.material.color.copy(cTmp.copy(cCore).lerp(cDead, dead))
      storySeal.position.copy(story.position); storySeal.rotation.set(time, time * 0.7, 0); storySeal.scale.setScalar(0.001 + 1.3 * sv * so)
      receipt.position.set(-L + 2 * L * Math.max(rc, 0), 0, 0); receipt.material.opacity = rc > 0.02 && rc < 0.98 ? sv * 0.9 : 0
    } else { story.material.opacity = 0; receipt.material.opacity = 0; storySeal.scale.setScalar(0.001) }
    relay.rotation.x = time * 0.6; relayMat.opacity = (sv * 0.7 + tun * 0.2); relay.visible = relayMat.opacity > 0.004

    endA.position.set(-L, 0, 0); endB.position.set(L, 0, 0)
    endA.material.opacity = (0.3 + 0.7 * rA) * solid; endB.material.opacity = (0.3 + 0.7 * rB) * solid
    endB.scale.setScalar(0.55 + 0.35 * rB); endA.scale.setScalar(0.55 + 0.35 * rA)
    rimMatB.color.copy(cTmp.copy(cEdge).lerp(cCore, rB)); rimMatA.color.copy(cTmp.copy(cEdge).lerp(cCore, rA))

    // dust
    for (let i = 0; i < DN; i++) { let y = dPos[i * 3 + 1] + dt * 0.05; if (y > 5) y -= 10; dPos[i * 3 + 1] = y }
    dGeo.attributes.position.needsUpdate = true; dustMat.opacity = P.dustOp

    // ghost dissolve
    if (d > 0.002) {
      const m0 = Math.pow(d, 1.5) * 2.4
      for (let i = 0; i < GN; i++) {
        const o = i * 8, ty = gI[o], hh = gI[o + 1], th = gI[o + 2], m = m0 * gI[o + 6], ph = gI[o + 7]
        let x, y, z
        if (ty < 2) { const r = RB + (RT - RB) * hh / H; const c = r * Math.cos(th), sn = r * Math.sin(th); if (ty === 0) { x = -L - hh; y = sn } else { x = L + hh; y = -sn } z = c }
        else { x = -L + 2 * L * (hh / H); y = 0; z = 0 }
        gPos[i * 3] = x + gI[o + 3] * m + Math.sin(time * 0.7 + ph) * 0.06 * d
        gPos[i * 3 + 1] = y + gI[o + 4] * m + Math.cos(time * 0.6 + ph) * 0.06 * d
        gPos[i * 3 + 2] = z + gI[o + 5] * m
      }
      gGeo.attributes.position.needsUpdate = true
      ghostMat.opacity = sm(0, 0.1, d) * (1 - sm(0.8, 1, d)) * 0.95; ghost.visible = true
    } else ghost.visible = false

    renderer.render(scene, camera)
  }

  // Render only while the tab is visible and a [data-scene] section is on screen.
  const onScreen = new Set()
  const start = () => { if (!raf && !document.hidden && onScreen.size) { last = performance.now(); raf = requestAnimationFrame(frame) } }
  const stop = () => { cancelAnimationFrame(raf); raf = 0 }
  const onVisibility = () => (document.hidden ? stop() : start())
  document.addEventListener('visibilitychange', onVisibility)
  const watch = new IntersectionObserver((entries) => {
    for (const entry of entries) entry.isIntersecting ? onScreen.add(entry.target) : onScreen.delete(entry.target)
    if (onScreen.size) start(); else stop()
  })
  for (const el of sections) watch.observe(el)

  return {
    setTheme,
    dispose() {
      stop()
      watch.disconnect()
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener('pointermove', onPointer)
      window.removeEventListener('scroll', onScroll)
      scene.traverse(o => { if (o.geometry) o.geometry.dispose(); if (o.material) o.material.dispose() })
      dotTex.dispose()
      renderer.dispose()
      renderer.forceContextLoss()
    },
  }
}
