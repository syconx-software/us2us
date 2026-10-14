<script setup lang="ts">
import { LICENCES_ANCHOR, paths, SECURITY_ANCHOR, site, type Locale } from '~/content/site'

// As in the design, the links are items of the footer row itself (so on a
// phone the first one shares the brand's line); the row is the landmark.
// The first link is the "why we built it" page, which the header shows
// under a short label on phones (and not at all under 360px). The licence
// note links to the support page's licences section. Under the row, a
// fine-print line says that the site sets no cookies and tracks nobody
// (true by construction: server/lib/website.ts allows only this origin).
const props = defineProps<{ locale: Locale }>()
const text = computed(() => site[props.locale].footer)
const links = computed(() => paths[props.locale])
</script>

<template>
  <footer class="site-footer">
    <div class="container footer-inner">
      <div class="hr" />
      <nav class="footer-row" :aria-label="text.label">
        <p class="footer-brand">
          <SiteMark small /><span class="wordmark wordmark-sm">us2us</span>{{ ' ' }}<span class="footer-note">© 2026</span>
        </p>
        <a class="footer-link" :href="links.why">{{ site[locale].nav.why }}</a>
        <a class="footer-link" :href="links.privacy">{{ text.privacy }}</a>
        <a class="footer-link" :href="`${links.privacy}#${SECURITY_ANCHOR[locale]}`">{{ text.security }}</a>
        <a class="footer-link" :href="links.support">{{ text.press }}</a>
        <a class="footer-link" :href="links.support">{{ text.contact }}</a>
        <a class="footer-link footer-legal" :href="`${links.support}#${LICENCES_ANCHOR[locale]}`">{{ text.licence }}</a>
      </nav>
      <p class="footer-fine">{{ text.noTracking }}</p>
    </div>
  </footer>
</template>
