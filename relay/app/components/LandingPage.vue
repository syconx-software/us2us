<script setup lang="ts">
import { exampleQrPath, exampleSafetyNumber } from '~/content/illustrations'
import { SECTION, site, type Locale } from '~/content/site'

// The landing page of the design ("us2us Web"). Everything is readable
// without JavaScript; /js/site.js only adds the scroll steps, the live
// countdown and the 3D backdrop (sections carry `data-scene` for it).
// Adjacent spans get a space or a visually hidden ": " between them, so the
// text does not run together for screen readers, reader views or no CSS
// (whitespace between flex and grid items does not change the layout).
const props = defineProps<{ locale: Locale }>()
const text = computed(() => site[props.locale].home)
const qr = exampleQrPath()
const safety = exampleSafetyNumber()
</script>

<template>
  <SiteShell :locale="locale" page="home" :title="text.title" :description="text.description">
    <!-- HERO -->
    <section :id="SECTION.top" class="section hero" data-scene="hero" aria-labelledby="hero-title">
      <div class="container hero-inner">
        <div class="hero-copy">
          <p class="tag tag-outline hero-badge"><SiteIcon name="lock-simple" />{{ text.hero.badge }}</p>
          <div class="hero-heading">
            <p class="kicker">{{ text.hero.kicker }}</p>
            <h1 id="hero-title" class="display"><SiteDots :text="text.hero.headline" /></h1>
          </div>
          <p class="hero-lead">{{ text.hero.intro }}</p>
          <StoreButtons :locale="locale" />
          <ul class="facts">
            <li v-for="fact in text.hero.facts" :key="fact.text"><SiteIcon :name="fact.icon" />{{ fact.text }}</li>
          </ul>
        </div>
      </div>
    </section>

    <!-- BAND -->
    <section class="section band" :aria-label="text.band.label">
      <div class="band-inner">
        <div class="band-line" aria-hidden="true">
          <span class="band-end" />
          <span class="band-wire"><span class="packet packet-r" /><span class="packet packet-l" /></span>
          <span class="band-end" />
        </div>
        <ul class="stats">
          <li v-for="stat in text.band.stats" :key="stat.label" class="stat">
            <span class="stat-value">{{ stat.value }}</span>{{ ' ' }}
            <span class="stat-copy">
              <span class="stat-label">{{ stat.label }}</span><span class="visually-hidden">: </span>
              <span class="stat-text">{{ stat.text }}</span>
            </span>
          </li>
        </ul>
      </div>
    </section>

    <!-- HOW IT WORKS -->
    <section :id="SECTION.how" class="section section-full" data-scene="line" aria-labelledby="how-title">
      <div class="container section-pad">
        <div class="section-copy copy-600">
          <p class="eyebrow">{{ text.how.eyebrow }}</p>
          <h2 id="how-title" class="h2"><SiteDots :text="text.how.title" /></h2>
          <p class="lead lead-lg">{{ text.how.intro }}</p>
        </div>
        <ol class="points">
          <li v-for="point in text.how.points" :key="point.n" class="card elev-sm point">
            <div class="point-head"><SiteIcon :name="point.icon" class="point-icon" /><span class="point-n" aria-hidden="true">{{ point.n }}</span></div>
            <h3 class="card-title point-title">{{ point.title }}</h3>
            <p class="card-body point-body">{{ point.body }}</p>
          </li>
        </ol>
      </div>
    </section>

    <!-- STATUSES (sticky, one status per scroll step with JS) -->
    <section :id="SECTION.statuses" class="section track track-status" data-scene="status" aria-labelledby="status-title">
      <div class="pin">
        <div class="container">
          <div class="section-copy copy-560">
            <p class="eyebrow">{{ text.statuses.eyebrow }}</p>
            <h2 id="status-title" class="h2 h2-lg h2-wrap"><SiteDots :text="text.statuses.title" /></h2>
            <p class="lead">{{ text.statuses.intro }}</p>
            <ul class="status-list" :aria-label="text.statuses.label" data-status-steps>
              <li v-for="item in text.statuses.items" :key="item.label" class="status" :class="`tone-${item.tone}`">
                <span class="status-mark" />
                <span class="status-icon"><SiteIcon :name="item.icon" /></span>
                <span class="status-text">
                  <span class="status-label">{{ item.label }}</span><span class="visually-hidden">: </span>
                  <span class="status-desc">{{ item.text }}</span>
                </span>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </section>

    <!-- GHOST MODE (sticky while the 3D scene runs) -->
    <section :id="SECTION.ghost" class="section track track-ghost" data-scene="ghost" aria-labelledby="ghost-title">
      <div class="pin">
        <div class="container">
          <div class="section-copy copy-560">
            <p class="eyebrow">{{ text.ghost.eyebrow }}</p>
            <h2 id="ghost-title" class="h2 h2-lg"><SiteDots :text="text.ghost.title" /></h2>
            <p class="lead">{{ text.ghost.intro }}</p>
            <div class="card elev-md ghost-card">
              <span class="avatar" aria-hidden="true">{{ text.ghost.contact.charAt(0).toUpperCase() }}</span>
              <span class="ghost-who">
                <span class="ghost-name">{{ text.ghost.contact }} <SiteIcon name="seal-check-fill" /></span>
                {{ ' ' }}<span class="ghost-presence">{{ text.ghost.presence }}</span>
              </span>
              <span class="tag tag-accent countdown">
                <SiteIcon name="ghost" /><span class="visually-hidden">{{ text.ghost.timerLabel }}: </span><span data-countdown>23:41:07</span>
              </span>
            </div>
            <div class="window-row">
              <span id="ghost-window" class="window-label">{{ text.ghost.windowLabel }}</span>
              <ul class="seg window-seg" aria-labelledby="ghost-window">
                <li v-for="(window, index) in text.ghost.windows" :key="window" class="seg-opt" :aria-current="index === text.ghost.selectedWindow ? 'true' : undefined">{{ window }}</li>
              </ul>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- FEATURES -->
    <section :id="SECTION.features" class="section" data-scene="features" aria-labelledby="features-title">
      <div class="container section-pad">
        <div class="section-copy copy-640">
          <p class="eyebrow">{{ text.features.eyebrow }}</p>
          <h2 id="features-title" class="h2"><SiteDots :text="text.features.title" /></h2>
        </div>
        <ul class="features">
          <li v-for="feature in text.features.items" :key="feature.title" class="card elev-sm feature">
            <span class="feature-icon"><SiteIcon :name="feature.icon" /></span>
            <h3 class="card-title feature-title">{{ feature.title }}</h3>
            <p class="card-body feature-body">{{ feature.body }}</p>
          </li>
        </ul>
        <div class="kinds">
          <h3 class="kinds-title">{{ text.features.kindsTitle }}</h3>
          <ul class="kind-list">
            <li v-for="kind in text.features.kinds" :key="kind.label" class="tag tag-neutral kind"><SiteIcon :name="kind.icon" />{{ kind.label }}</li>
          </ul>
        </div>
      </div>
    </section>

    <!-- VERIFY -->
    <section :id="SECTION.verify" class="section section-full" data-scene="verify" aria-labelledby="verify-title">
      <div class="container section-pad verify-grid">
        <div class="section-copy copy-540">
          <p class="eyebrow">{{ text.verify.eyebrow }}</p>
          <h2 id="verify-title" class="h2"><SiteDots :text="text.verify.title" /></h2>
          <p class="lead">{{ text.verify.intro }}</p>
        </div>
        <figure class="card elev-lg verify-card">
          <div class="verify-head">
            <div class="verify-who">
              <span class="verify-name">{{ text.verify.cardTitle }}</span><span class="visually-hidden">: </span>
              <span class="verify-sub">{{ text.verify.cardSubtitle }}</span>
            </div>
            <span class="tag tag-accent verify-tag"><SiteIcon name="seal-check-fill" />{{ text.verify.verified }}</span>
          </div>
          <div class="verify-body" aria-hidden="true">
            <div class="qr"><svg viewBox="0 0 29 29" shape-rendering="crispEdges" focusable="false"><path :d="qr" /></svg></div>
            <div class="safety">
              <span v-for="(group, index) in safety" :key="index">{{ group }}</span>
            </div>
          </div>
          <span class="btn btn-primary btn-block verify-scan" aria-hidden="true"><SiteIcon name="scan" />{{ text.verify.scan }}</span>
          <figcaption class="verify-note">{{ text.verify.example }}</figcaption>
        </figure>
      </div>
    </section>

    <!-- CTA -->
    <section :id="SECTION.download" class="section cta" data-scene="cta" aria-labelledby="cta-title">
      <div class="container section-pad">
        <div class="section-copy copy-620">
          <h2 id="cta-title" class="h2 h2-xl"><SiteDots :text="text.cta.title" /></h2>
          <p class="lead lead-lg">{{ text.cta.intro }}</p>
          <StoreButtons :locale="locale" />
        </div>
      </div>
    </section>
  </SiteShell>
</template>
