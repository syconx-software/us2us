<script setup lang="ts">
import { site, SUPPORT_EMAIL, type Locale } from '~/content/site'
import { citeId, sourceId, why, WHY_PUBLISHED, WHY_SECTION, WHY_SOURCES, whyDate, whyDocument, whyHref } from '~/content/why'

// "Neden yaptık / Why we built it": the SyconX team's statement, laid out as
// a press release in the privacy page's frame. Eyebrow, headline, standfirst
// and dateline; the body's sections with their pull quotes; a calm close;
// the "About SyconX" box with the press contact; and the numbered sources,
// which the "[n]" marks in the text link to. All text is in content/why.ts.
// Source links leave the site, so they carry rel="noopener noreferrer" (and
// open in the same tab, like every link here).
const props = defineProps<{ locale: Locale }>()
const text = computed(() => why[props.locale])
const doc = computed(() => whyDocument(props.locale))
const beta = computed(() => site[props.locale].home.stores.beta)
const published = computed(() => whyDate(WHY_PUBLISHED, props.locale))
const sources = computed(() => WHY_SOURCES.map((source, index) => ({
  ...source,
  n: index + 1,
  date: whyDate(source.date, props.locale),
  iso: source.date,
  cited: doc.value.cited.includes(index + 1),
})))
</script>

<template>
  <SiteShell :locale="locale" page="why" :title="`${text.title} · us2us`" :description="text.description" :social="text.social" :published="WHY_PUBLISHED">
    <div class="container page-pad">
      <article class="prose why">
        <p class="eyebrow">{{ text.eyebrow }}</p>
        <h1 class="display display-page why-headline"><SiteDots :text="text.headline" /></h1>
        <p class="lead why-standfirst"><WhyParts :parts="doc.standfirst" :mark="text.sources.mark" /></p>
        <p class="updated why-dateline">{{ text.dateline.place }}, <time :datetime="WHY_PUBLISHED">{{ published }}</time> — {{ text.dateline.by }}</p>

        <section v-for="section in doc.sections" :id="section.id" :key="section.id" class="prose-section" :aria-labelledby="`${section.id}-title`">
          <h2 :id="`${section.id}-title`" class="h2 h2-prose"><SiteDots :text="section.title" /></h2>
          <template v-for="(block, index) in section.blocks" :key="index">
            <p v-if="block.kind === 'paragraph'"><WhyParts :parts="block.parts" :mark="text.sources.mark" /></p>
            <figure v-else class="pull-quote">
              <blockquote><p><WhyParts :parts="block.parts" :mark="text.sources.mark" /></p></blockquote>
              <figcaption>{{ block.by }}</figcaption>
            </figure>
          </template>
        </section>

        <section class="prose-section why-cta" aria-labelledby="why-cta-title">
          <h2 id="why-cta-title" class="h2 h2-prose"><SiteDots :text="text.cta.title" /></h2>
          <p class="lead"><WhyParts :parts="doc.cta" :mark="text.sources.mark" /></p>
          <p class="why-cta-links">
            <a v-for="(link, index) in text.cta.links" :key="link.label" :class="['btn', index === 0 ? 'btn-primary' : 'btn-secondary']" :href="whyHref(locale, link)">{{ link.label }}</a>
          </p>
          <p class="stores-note"><span class="beta-dot" aria-hidden="true" />{{ beta }}</p>
        </section>

        <section :id="WHY_SECTION.about" class="card elev-sm about-card" :aria-labelledby="`${WHY_SECTION.about}-title`">
          <h2 :id="`${WHY_SECTION.about}-title`" class="card-title about-title">{{ text.about.title }}</h2>
          <p v-for="(parts, index) in doc.about" :key="index" class="about-body"><WhyParts :parts="parts" :mark="text.sources.mark" /></p>
          <p class="about-press">
            <span class="contact-label">{{ text.about.pressLabel }}</span>{{ ' ' }}<a :href="`mailto:${SUPPORT_EMAIL}`">{{ SUPPORT_EMAIL }}</a>
          </p>
        </section>

        <section :id="WHY_SECTION.sources" class="prose-section" :aria-labelledby="`${WHY_SECTION.sources}-title`">
          <h2 :id="`${WHY_SECTION.sources}-title`" class="h2 h2-prose">{{ text.sources.title }}</h2>
          <p>{{ text.sources.intro }}</p>
          <ol class="sources">
            <li v-for="source in sources" :id="sourceId(source.n)" :key="source.n" class="source">
              <span class="source-n">{{ source.n }}</span>
              <div class="source-body">
                <a class="source-title" :href="source.url" :hreflang="source.lang" :lang="source.lang" rel="noopener noreferrer">{{ source.title }}</a>
                <!-- A no-break space before each "·": where the line wraps, the dot ends the line instead of opening the next. -->
                <p class="source-meta">
                  <span :lang="source.lang">{{ source.publisher }}</span>{{ ' · ' }}<time :datetime="source.iso">{{ source.date }}</time>{{ ' · ' }}{{ text.sources.language[source.lang] }}
                </p>
                <a v-if="source.cited" class="source-back" :href="`#${citeId(source.n)}`">{{ text.sources.back }}</a>
              </div>
            </li>
          </ol>
        </section>
      </article>
    </div>
  </SiteShell>
</template>
