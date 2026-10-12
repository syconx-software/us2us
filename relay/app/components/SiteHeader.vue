<script setup lang="ts">
import { paths, site, type Locale, type PageId } from '~/content/site'

// The fixed header of every page: mark, section links (wide screens) and
// the "why we built it" page (at every width but the narrowest; on phones
// under its short label, which screen readers skip for the full one), the
// TR/EN switch (real links between the two language versions; site.js
// keeps the reader's section) and the theme toggle, wired by /js/theme.js
// and shown only with JS. Motion follows the visitor's reduced-motion
// setting.
const props = defineProps<{ locale: Locale, page: PageId | 'error' }>()
const text = computed(() => site[props.locale].nav)
const home = computed(() => paths[props.locale].home)
const whyPage = computed(() => paths[props.locale].why)
const languages = computed(() => (['tr', 'en'] as const).map(code => ({
  code,
  href: paths[code][props.page === 'error' ? 'home' : props.page],
  name: site[code].languageName,
  current: code === props.locale,
})))
</script>

<template>
  <header class="site-header">
    <div class="site-nav">
      <a class="brand" :href="page === 'home' ? '#top' : home" :aria-label="text.home"><SiteMark /><span class="wordmark">us2us</span></a>
      <nav class="section-links" :aria-label="text.label">
        <a v-for="link in text.sections" :key="link.id" :href="page === 'home' ? `#${link.id}` : `${home}#${link.id}`">{{ link.label }}</a>
        <a class="nav-page" :href="whyPage" :aria-current="page === 'why' ? 'page' : undefined"><span class="nav-page-full">{{ text.why }}</span><span class="nav-page-short" aria-hidden="true">{{ text.whyShort }}</span></a>
      </nav>
      <div class="header-controls">
        <nav class="seg lang-switch" :aria-label="`${text.languageLabel} / ${locale === 'tr' ? 'Language' : 'Dil'}`">
          <a
            v-for="language in languages"
            :key="language.code"
            class="seg-opt"
            :href="language.href"
            :hreflang="language.code"
            :lang="language.code"
            :aria-current="language.current ? 'true' : undefined"
            data-lang-link
          >{{ language.code.toUpperCase() }}<span class="visually-hidden">{{ ` ${language.name}` }}</span></a>
        </nav>
        <button type="button" class="btn btn-secondary btn-icon theme-toggle" :aria-label="text.themeLabel" aria-pressed="false" data-theme-toggle>
          <SiteIcon name="sun" class="theme-sun" /><SiteIcon name="moon" class="theme-moon" />
        </button>
      </div>
    </div>
  </header>
</template>
