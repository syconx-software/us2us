// us2us: runs in <head> before the first paint (a classic script, so it runs
// wherever JavaScript does). Applies the saved theme (dark by default, as in
// the design), wires the theme toggle and adds the `js` class that shows it.
// Everything that needs more (status steps, countdown, 3D scene, the slow
// section scrolling) is site.js's job, which sets its own classes once it runs.
(function (root, doc) {
  var THEME = 'us2us-theme'
  var COLORS = { dark: '#161826', light: '#f3f5fe' }

  function read(key) {
    try { return localStorage.getItem(key) } catch (e) { return null }
  }
  function write(key, value) {
    try {
      if (value) localStorage.setItem(key, value)
      else localStorage.removeItem(key)
    } catch (e) {}
  }
  function press(selector, on) {
    var buttons = doc.querySelectorAll(selector)
    for (var i = 0; i < buttons.length; i++) buttons[i].setAttribute('aria-pressed', on ? 'true' : 'false')
  }
  function sync() {
    var light = root.getAttribute('data-theme') === 'light'
    press('[data-theme-toggle]', light)
    var meta = doc.querySelector('meta[name="theme-color"]')
    if (meta) meta.setAttribute('content', light ? COLORS.light : COLORS.dark)
  }
  function setTheme(theme) {
    if (theme === 'light') root.setAttribute('data-theme', 'light')
    else root.removeAttribute('data-theme')
  }

  setTheme(read(THEME))
  root.classList.add('js')

  doc.addEventListener('click', function (event) {
    var target = event.target
    if (!target || !target.closest) return
    if (!target.closest('[data-theme-toggle]')) return
    var theme = root.getAttribute('data-theme') === 'light' ? 'dark' : 'light'
    setTheme(theme)
    write(THEME, theme)
    sync()
  })
  // Another tab changed the theme.
  addEventListener('storage', function (event) {
    if (event.key !== THEME) return
    setTheme(event.newValue)
    sync()
  })
  doc.addEventListener('DOMContentLoaded', sync)
})(document.documentElement, document)
