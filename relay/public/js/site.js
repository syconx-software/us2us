// us2us progressive enhancement; the pages are complete without it. Adds the
// section-keeping language switch, slow scrolling to in-page sections (so the
// 3D backdrop's transitions play out), the pinned status steps, the countdown
// and, when affordable, the 3D backdrop (scene.js, at idle time). The theme
// toggle is theme.js's; this file follows its state.

const root = document.documentElement
const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)')
const currentTheme = () => (root.getAttribute('data-theme') === 'light' ? 'light' : 'dark')
const motionAllowed = () => !reducedMotion.matches
let scene = null

// ── language switch: keep the section being read ─────────────────────────
// The section that contains the line at 35% of the viewport, if it has an id.
function sectionInView() {
  const y = innerHeight * 0.35
  for (const section of document.querySelectorAll('main section[id]')) {
    const box = section.getBoundingClientRect()
    if (box.top <= y && box.bottom > y) return section.id === 'top' ? null : section.id
  }
  return null
}

document.addEventListener('click', (event) => {
  const link = event.target instanceof Element ? event.target.closest('a[data-lang-link]') : null
  if (link) {
    const id = sectionInView()
    link.hash = id ? `#${id}` : ''
  }
})

// ── slow scrolling to sections ───────────────────────────────────────────
// The browser's smooth scroll is too quick for the 3D backdrop, which moves
// with the scroll position: a jump past several sections would skip their
// transitions. Same-page section links glide instead, about 0.4 s per screen
// (1–5 s in total), with an ease-in-out curve. Any wheel, touch or key from
// the visitor takes over at once. With reduced motion the browser jumps.
const SCROLL_MS_MIN = 1000
const SCROLL_MS_MAX = 5000
const SCROLL_MS_PER_SCREEN = 400
let glide = 0

const easeInOut = t => (t < 0.5 ? 4 * t * t * t : 1 - (-2 * t + 2) ** 3 / 2)

function stopGlide() {
  if (!glide) return
  cancelAnimationFrame(glide)
  glide = 0
  root.classList.remove('gliding')
}
for (const type of ['wheel', 'touchstart', 'keydown', 'mousedown']) addEventListener(type, stopGlide, { passive: true })

function glideTo(target) {
  stopGlide()
  const offset = parseFloat(getComputedStyle(root).scrollPaddingTop) || 0
  const max = root.scrollHeight - innerHeight
  const from = scrollY
  const to = target.id === 'top' ? 0 : Math.min(max, Math.max(0, target.getBoundingClientRect().top + from - offset))
  const distance = to - from
  if (Math.abs(distance) < 2) return
  const duration = Math.min(SCROLL_MS_MAX, Math.max(SCROLL_MS_MIN, SCROLL_MS_MIN + (Math.abs(distance) / innerHeight) * SCROLL_MS_PER_SCREEN))
  const start = performance.now()
  // .gliding turns html's scroll-behavior: smooth off, so each step lands
  // exactly (the two-argument scrollTo follows that CSS in every browser).
  root.classList.add('gliding')
  const step = (now) => {
    const t = Math.min(1, (now - start) / duration)
    scrollTo(0, from + distance * easeInOut(t))
    if (t < 1) glide = requestAnimationFrame(step)
    else {
      glide = 0
      root.classList.remove('gliding')
      // Keyboard and screen-reader users continue from the section.
      target.focus({ preventScroll: true })
    }
  }
  glide = requestAnimationFrame(step)
}

document.addEventListener('click', (event) => {
  if (!motionAllowed() || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  const link = event.target instanceof Element ? event.target.closest('a[href*="#"]') : null
  if (!link || link.hasAttribute('data-lang-link') || link.origin !== location.origin || link.pathname !== location.pathname) return
  const id = decodeURIComponent(link.hash.slice(1))
  const target = id && document.getElementById(id)
  if (!target) return
  event.preventDefault()
  if (!target.hasAttribute('tabindex')) target.setAttribute('tabindex', '-1')
  history.pushState(null, '', link.hash)
  glideTo(target)
})

// ── pinned sections ──────────────────────────────────────────────────────
// A section pins (.is-pinned) only when its content fits between the header
// and the bottom of the smallest viewport (100svh), so nothing hides behind
// the header on short phones or with large text. The status section always
// may; the ghost section only while the 3D scene runs (.scene).
const tracks = [...document.querySelectorAll('.track')]
const header = document.querySelector('.site-header')
const probe = tracks.length ? document.createElement('div') : null
if (probe) {
  probe.className = 'svh-probe'
  probe.setAttribute('aria-hidden', 'true')
  document.body.append(probe)
}
const PIN_MARGIN = 12 + 24 // .track.is-pinned > .pin > .container: header + 12px above, 24px below

function fitsPinned(track) {
  const copy = track.querySelector('.section-copy')
  if (!copy || !probe) return false
  return copy.offsetHeight + (header ? header.offsetHeight : 0) + PIN_MARGIN <= probe.offsetHeight
}

// Runs `change` and scrolls by however much the section in view moved, so a
// section above changing height does not move the reader.
function keepPlace(change) {
  const anchor = [...document.querySelectorAll('main > section')].find(section => section.getBoundingClientRect().bottom > 0)
  const before = anchor?.getBoundingClientRect().top
  change()
  const delta = anchor ? anchor.getBoundingClientRect().top - before : 0
  if (Math.abs(delta) > 0.5) scrollBy({ top: delta, behavior: 'instant' })
}

const steps = document.querySelector('[data-status-steps]')
const statusItems = steps ? [...steps.children] : []
let step = -1

function updateSteps() {
  const track = steps?.closest('.track')
  if (!track || !track.classList.contains('is-pinned')) return
  const box = track.getBoundingClientRect()
  const progress = Math.min(1, Math.max(0, -box.top / Math.max(1, box.height - innerHeight)))
  const next = Math.min(statusItems.length - 1, Math.floor(progress * statusItems.length))
  if (next === step) return
  step = next
  statusItems.forEach((item, index) => item.classList.toggle('is-active', index === next))
}

function measurePins() {
  if (!tracks.length) return
  keepPlace(() => {
    for (const track of tracks) {
      const allowed = track.classList.contains('track-status') || root.classList.contains('scene')
      track.classList.toggle('is-pinned', allowed && fitsPinned(track))
    }
  })
  if (steps) {
    const stepping = !!steps.closest('.track')?.classList.contains('is-pinned')
    steps.classList.toggle('is-stepping', stepping)
    if (!stepping) statusItems.forEach(item => item.classList.remove('is-active'))
    step = -1
    updateSteps()
  }
}

let queued = false
addEventListener('scroll', () => {
  if (!queued) {
    queued = true
    requestAnimationFrame(() => { queued = false; updateSteps() })
  }
}, { passive: true })
let lastWidth = innerWidth
let lastProbe = probe?.offsetHeight
addEventListener('resize', () => {
  // Mobile toolbars resize the window while scrolling; 100svh and the width
  // stay the same then, and so does the answer.
  if (innerWidth === lastWidth && probe?.offsetHeight === lastProbe) return updateSteps()
  lastWidth = innerWidth
  lastProbe = probe?.offsetHeight
  measurePins()
})
document.fonts?.ready.then(measurePins)

// ── ghost mode: a live countdown while it is on screen and motion is
// allowed (the page shows 23:41:07 without it) ───────────────────────────
const countdown = document.querySelector('[data-countdown]')
let left = 23 * 3600 + 41 * 60 + 7
let timer = 0
let countdownVisible = false
const pad = n => String(n).padStart(2, '0')

function syncCountdown() {
  const run = !!countdown && countdownVisible && motionAllowed() && !document.hidden
  if (run && !timer) {
    timer = setInterval(() => {
      left = left > 0 ? left - 1 : 86399
      countdown.textContent = `${pad(Math.floor(left / 3600))}:${pad(Math.floor(left / 60) % 60)}:${pad(left % 60)}`
    }, 1000)
  } else if (!run && timer) {
    clearInterval(timer)
    timer = 0
  }
}
if (countdown && 'IntersectionObserver' in window) {
  new IntersectionObserver((entries) => {
    countdownVisible = entries.some(entry => entry.isIntersecting)
    syncCountdown()
  }).observe(countdown)
}

// ── 3D backdrop: only with WebGL, motion allowed, no Save-Data, enough
// memory, a visible tab, after load at idle time, never while prerendering.
// Without it the ghost section does not pin.
let canvas = null
let loading = false
const hasScene = !!document.querySelector('[data-scene]')
const lowMemory = typeof navigator.deviceMemory === 'number' && navigator.deviceMemory < 4
const sceneWelcome = () => hasScene && motionAllowed() && !navigator.connection?.saveData && !lowMemory && 'WebGLRenderingContext' in window

function setSceneClass(on) {
  if (root.classList.contains('scene') === on) return
  keepPlace(() => root.classList.toggle('scene', on))
  measurePins()
}

function startScene() {
  if (scene || loading || !sceneWelcome()) return
  if (document.hidden) return scheduleScene()
  const element = document.createElement('canvas')
  element.className = 'scene-canvas'
  element.setAttribute('aria-hidden', 'true')
  const dpr = window.devicePixelRatio || 1
  let context = null
  try {
    const attributes = { alpha: true, antialias: dpr < 2, depth: true, stencil: false, powerPreference: 'low-power' }
    context = element.getContext('webgl2', attributes) || element.getContext('webgl', attributes)
  } catch {}
  if (!context) return setSceneClass(false)
  loading = true
  document.querySelector('.bg-dots')?.after(element)
  // scene.js gets the same ?v= as this file, so a release never mixes them.
  import(`./scene.js${new URL(import.meta.url).search}`)
    .then(module => module.mountUs2usScene(element, { theme: currentTheme(), context }))
    .then((controller) => {
      loading = false
      if (!sceneWelcome() || !element.isConnected) { controller.dispose(); element.remove(); return }
      scene = controller
      canvas = element
    })
    .catch(() => {
      loading = false
      element.remove()
      setSceneClass(false)
    })
}

function stopScene() {
  if (scene) scene.dispose()
  canvas?.remove()
  scene = null
  canvas = null
}

function scheduleScene() {
  if (!sceneWelcome()) return
  if (document.prerendering) {
    document.addEventListener('prerenderingchange', scheduleScene, { once: true })
    return
  }
  // A background tab does not need a backdrop (nor a WebGL context) yet.
  if (document.hidden) {
    document.addEventListener('visibilitychange', scheduleScene, { once: true })
    return
  }
  const idle = () => (window.requestIdleCallback ? requestIdleCallback(startScene, { timeout: 3000 }) : setTimeout(startScene, 300))
  if (document.readyState === 'complete') idle()
  else addEventListener('load', idle, { once: true })
}

// The visitor's reduced-motion setting changed.
function syncMotion() {
  if (!motionAllowed()) stopScene()
  setSceneClass(sceneWelcome())
  scheduleScene()
  syncCountdown()
}
reducedMotion.addEventListener('change', syncMotion)
document.addEventListener('visibilitychange', syncCountdown)
let theme = currentTheme()
new MutationObserver(() => {
  if (theme === currentTheme()) return
  theme = currentTheme()
  scene?.setTheme(theme)
}).observe(root, { attributes: true, attributeFilter: ['data-theme'] })

addEventListener('pagehide', stopScene)
addEventListener('pageshow', (event) => {
  if (event.persisted) scheduleScene()
})

// The ghost section may pin as soon as a scene is likely; startScene()
// takes the class away again if WebGL turns out not to work.
if (sceneWelcome()) root.classList.add('scene')
measurePins()
scheduleScene()
