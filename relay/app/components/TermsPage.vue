<script setup lang="ts">
import { SUPPORT_EMAIL, type Locale } from '~/content/site'
import { terms, TERMS_SECTION } from '~/content/terms'

// The terms of use, in the privacy page's frame: title, effective date,
// summary, the zero-tolerance statement on its own, then the sections. All
// text is in content/terms.ts; the section ids are the same in both
// languages.
const props = defineProps<{ locale: Locale }>()
const text = computed(() => terms[props.locale])
</script>

<template>
  <SiteShell :locale="locale" page="terms" :title="`${text.title} · us2us`" :description="text.description">
    <div class="container page-pad">
      <article class="prose">
        <p class="eyebrow">us2us</p>
        <h1 class="display display-page"><SiteDots :text="text.title" /></h1>
        <p class="updated">{{ text.effective }}</p>
        <p v-for="paragraph in text.summary" :key="paragraph" class="lead">{{ paragraph }}</p>
        <p class="lead"><strong>{{ text.zeroTolerance }}</strong></p>

        <section v-for="section in text.sections" :id="section.id" :key="section.id" class="prose-section">
          <h2 class="h2 h2-prose">{{ section.title }}</h2>
          <p v-for="paragraph in section.paragraphs" :key="paragraph">{{ paragraph }}</p>
          <ul v-if="section.items" class="dot-list">
            <li v-for="item in section.items" :key="item">{{ item }}</li>
          </ul>
        </section>

        <section :id="TERMS_SECTION.contact" class="prose-section">
          <h2 class="h2 h2-prose">{{ text.contactTitle }}</h2>
          <p>{{ text.contact }} <a :href="`mailto:${SUPPORT_EMAIL}`">{{ SUPPORT_EMAIL }}</a></p>
        </section>
      </article>
    </div>
  </SiteShell>
</template>
