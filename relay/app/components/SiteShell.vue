<script setup lang="ts">
import { FONTS } from '~/content/assets'
import { paths, SITE_ORIGIN, site, type Locale, type PageId } from '~/content/site'

// Page frame shared by every page: head tags (title, description, canonical,
// hreflang, Open Graph), the skip link, the fixed backdrop, header and footer.
// The stylesheet, icons and scripts are in nuxt.config.ts (app.head). A page
// with a publication date (YYYY-MM-DD, the "why we built it" statement) is an
// Open Graph article. `social` is the link-preview text when it should differ
// from the meta description.
const props = defineProps<{ locale: Locale, page: PageId, title: string, description: string, published?: string, social?: string }>()

const text = computed(() => site[props.locale])
const url = (locale: Locale) => `${SITE_ORIGIN}${paths[locale][props.page]}`

useHead(() => ({
  htmlAttrs: { lang: props.locale },
  title: props.title,
  meta: [
    { name: 'description', content: props.description },
    { property: 'og:type', content: props.published ? 'article' : 'website' },
    ...(props.published ? [{ property: 'article:published_time', content: props.published }] : []),
    { property: 'og:site_name', content: 'us2us' },
    { property: 'og:title', content: props.title },
    { property: 'og:description', content: props.social ?? props.description },
    { property: 'og:url', content: url(props.locale) },
    { property: 'og:locale', content: props.locale === 'tr' ? 'tr_TR' : 'en_US' },
    { property: 'og:locale:alternate', content: props.locale === 'tr' ? 'en_US' : 'tr_TR' },
    { name: 'twitter:card', content: 'summary' },
  ],
  link: [
    { rel: 'canonical', href: url(props.locale) },
    { rel: 'alternate', hreflang: 'tr', href: url('tr') },
    { rel: 'alternate', hreflang: 'en', href: url('en') },
    { rel: 'alternate', hreflang: 'x-default', href: props.page === 'home' ? `${SITE_ORIGIN}/` : url('en') },
    // Turkish text needs ğ, ş and İ from the first line on: a few-KB cut of
    // the latin-ext subset with just those letters (public/css/site.css).
    ...(props.locale === 'tr' ? [{ rel: 'preload', as: 'font', type: 'font/woff2', href: FONTS.interTurkish, crossorigin: '' } as const] : []),
  ],
}))
</script>

<template>
  <a class="skip-link" href="#main">{{ text.skipLink }}</a>
  <div class="bg-glow" aria-hidden="true" />
  <div class="bg-dots" aria-hidden="true" />
  <SiteHeader :locale="locale" :page="page" />
  <main id="main" class="site-main" :class="`page-${page}`" tabindex="-1">
    <slot />
  </main>
  <SiteFooter :locale="locale" />
</template>
