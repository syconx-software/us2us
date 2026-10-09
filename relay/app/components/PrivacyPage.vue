<script setup lang="ts">
import { SECURITY_ANCHOR, site, SUPPORT_EMAIL, type Locale } from '~/content/site'

const props = defineProps<{ locale: Locale }>()
const text = computed(() => site[props.locale].privacy)
const sections = computed(() => [
  { title: text.value.neverTitle, items: text.value.never },
  { title: text.value.seesTitle, items: text.value.sees },
  { title: text.value.appleGoogleTitle, items: text.value.appleGoogle },
  { title: text.value.phoneTitle, items: text.value.phone },
  { title: text.value.securityTitle, items: text.value.security, security: true },
  { title: text.value.limitsTitle, items: text.value.limits },
  { title: text.value.noTrackingTitle, items: text.value.noTracking },
  { title: text.value.deletionTitle, items: text.value.deletion },
])
// The security section answers to #guvenlik and #security in both languages.
const other = computed<Locale>(() => (props.locale === 'tr' ? 'en' : 'tr'))
</script>

<template>
  <SiteShell :locale="locale" page="privacy" :title="`${text.title} · us2us`" :description="text.description">
    <div class="container page-pad">
      <article class="prose">
        <p class="eyebrow">us2us</p>
        <h1 class="display display-page"><SiteDots :text="text.title" /></h1>
        <p class="updated">{{ text.updated }}</p>
        <p v-for="paragraph in text.summary" :key="paragraph" class="lead">{{ paragraph }}</p>

        <h2 class="h2 h2-prose">{{ text.storedTitle }}</h2>
        <p>{{ text.storedIntro }}</p>
        <div class="card elev-sm table-card">
          <table class="table">
            <thead>
              <tr><th scope="col">{{ text.columns.data }}</th><th scope="col">{{ text.columns.lifetime }}</th></tr>
            </thead>
            <tbody>
              <tr v-for="row in text.stored" :key="row.data">
                <td>{{ row.data }}</td>
                <td>{{ row.lifetime }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <section v-for="section in sections" :id="section.security ? SECURITY_ANCHOR[other] : undefined" :key="section.title" class="prose-section">
          <h2 :id="section.security ? SECURITY_ANCHOR[locale] : undefined" class="h2 h2-prose">{{ section.title }}</h2>
          <ul class="dot-list">
            <li v-for="item in section.items" :key="item">{{ item }}</li>
          </ul>
        </section>

        <h2 class="h2 h2-prose">{{ text.contactTitle }}</h2>
        <p>{{ text.contact }} <a :href="`mailto:${SUPPORT_EMAIL}`">{{ SUPPORT_EMAIL }}</a></p>
      </article>
    </div>
  </SiteShell>
</template>
