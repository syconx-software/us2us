// Nuxt adds an inline <script type="speculationrules"> to pages served
// without scripts (prefetch and prerender of every same-origin link, "/*",
// because of the development-only catch-all page). The site does without it,
// so the CSP can be `script-src 'self'` with no inline exception.
export default defineNuxtPlugin({
  name: 'us2us:no-speculation-rules',
  setup(nuxtApp) {
    nuxtApp.ssrContext?.head.hooks?.hook('tags:resolve', (context) => {
      context.tags = context.tags.filter(tag => !(tag.tag === 'script' && tag.props.type === 'speculationrules'))
    })
  },
})
