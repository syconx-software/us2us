<script setup lang="ts">
import { LICENCES_ANCHOR, paths, SECURITY_ANCHOR, site, type Locale } from '~/content/site'

// As in the design, the links are items of the footer row itself (so on a
// phone the first one shares the brand's line); the row is the landmark.
// The first link is the "why we built it" page, which the header shows
// under a short label on phones (and not at all under 360px). The licence
// note links to the support page's licences section. Under the row, a
// fine-print line says that the site sets no cookies and tracks nobody
// (true by construction: server/lib/website.ts allows only this origin).
// Above the row, "other products" links to our other apps' sites
// (site.footer.products); not-found.ts repeats this markup. The invite page
// leaves it out: it links only to this site (test/unit/invite.test.ts).
const props = defineProps<{ locale: Locale }>()
const text = computed(() => site[props.locale].footer)
const links = computed(() => paths[props.locale])
</script>

<template>
  <footer class="site-footer">
    <div class="container footer-inner">
      <section class="footer-products" :aria-labelledby="`footer-products-${locale}`">
        <h2 :id="`footer-products-${locale}`" class="eyebrow footer-products-title">{{ text.products.title }}</h2>
        <ul class="footer-product-list">
          <li v-for="item in text.products.items" :key="item.href">
            <a class="card elev-sm footer-product" :href="item.href" rel="noreferrer">
              <span class="card-title">{{ item.name }} <span class="footer-product-arrow" aria-hidden="true">↗</span></span>
              <span class="card-body">{{ item.text }}</span>
              <span class="footer-product-host">{{ item.host }}</span>
            </a>
          </li>
        </ul>
      </section>
      <div class="hr" />
      <nav class="footer-row" :aria-label="text.label">
        <p class="footer-brand">
          <SiteMark small /><span class="wordmark wordmark-sm">us2us</span>{{ ' ' }}<span class="footer-note">© 2026</span>
        </p>
        <a class="footer-link" :href="links.why">{{ site[locale].nav.why }}</a>
        <a class="footer-link" :href="links.privacy">{{ text.privacy }}</a>
        <a class="footer-link" :href="links.terms">{{ text.terms }}</a>
        <a class="footer-link" :href="`${links.privacy}#${SECURITY_ANCHOR[locale]}`">{{ text.security }}</a>
        <a class="footer-link" :href="links.support">{{ text.press }}</a>
        <a class="footer-link" :href="links.support">{{ text.contact }}</a>
        <a class="footer-link footer-legal" :href="`${links.support}#${LICENCES_ANCHOR[locale]}`">{{ text.licence }}</a>
      </nav>
      <p class="footer-fine">{{ text.noTracking }}</p>
    </div>
  </footer>
</template>
