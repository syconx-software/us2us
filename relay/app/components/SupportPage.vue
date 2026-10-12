<script setup lang="ts">
import { BETA_ANCHOR, LICENCES_ANCHOR, site, SUPPORT_EMAIL, type Locale } from '~/content/site'

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

        <!-- Beta testers: what keeps their data safe, and where bugs and security problems go. -->
        <section :id="BETA_ANCHOR" class="prose-section" aria-labelledby="beta-title">
          <h2 id="beta-title" class="h2 h2-prose">{{ text.beta.title }}</h2>
          <p>{{ text.beta.intro }}</p>
          <ul class="notes">
            <li v-for="note in text.beta.notes" :key="note.title" class="card elev-sm note" :class="note.tone && `note-${note.tone}`">
              <span class="feature-icon note-icon"><SiteIcon :name="note.icon" /></span>
              <div class="note-copy">
                <h3 class="card-title note-title">{{ note.title }}</h3>
                <p class="note-body">{{ note.body }}</p>
                <a v-if="note.email" class="note-email" :href="`mailto:${note.email}`">{{ note.email }}</a>
              </div>
            </li>
          </ul>
        </section>

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
