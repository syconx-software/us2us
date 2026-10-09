<script setup lang="ts">
import { LICENCES_ANCHOR, site, SUPPORT_EMAIL, type Locale } from '~/content/site'

const props = defineProps<{ locale: Locale }>()
const text = computed(() => site[props.locale].support)
// The licences section answers to #lisanslar and #licences in both languages
// (as the privacy page's security section does).
const other = computed<Locale>(() => (props.locale === 'tr' ? 'en' : 'tr'))
</script>

<template>
  <SiteShell :locale="locale" page="support" :title="`${text.title} · us2us`" :description="text.description">
    <div class="container page-pad">
      <article class="prose">
        <p class="eyebrow">us2us</p>
        <h1 class="display display-page">{{ text.title }}</h1>
        <p class="lead">{{ text.intro }}</p>
        <p class="card elev-sm contact-card">
          <span class="contact-label">{{ text.emailLabel }}</span>
          <a class="contact-email" :href="`mailto:${SUPPORT_EMAIL}`">{{ SUPPORT_EMAIL }}</a>
        </p>

        <h2 class="h2 h2-prose">{{ text.faqTitle }}</h2>
        <div class="faq">
          <details v-for="item in text.faq" :key="item.question" class="faq-item">
            <summary>{{ item.question }}</summary>
            <p>{{ item.answer }}</p>
          </details>
        </div>

        <section :id="LICENCES_ANCHOR[other]" class="prose-section">
          <h2 :id="LICENCES_ANCHOR[locale]" class="h2 h2-prose">{{ text.licencesTitle }}</h2>
          <p>{{ text.licencesIntro }}</p>
          <p>{{ text.licencesListIntro }}</p>
          <ul class="dot-list">
            <li v-for="item in text.licences" :key="item.href">{{ item.name }}: <a :href="item.href">{{ item.licence }}</a></li>
          </ul>
        </section>
      </article>
    </div>
  </SiteShell>
</template>
