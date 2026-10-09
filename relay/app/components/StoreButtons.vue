<script setup lang="ts">
import { site, type Locale } from '~/content/site'

// The apps are not in the stores yet: the buttons keep the design's look but
// are not links (no href, aria-disabled) and say "coming soon". The hidden
// comma keeps the name readable ("App Store, Yakında") for screen readers.
const props = defineProps<{ locale: Locale }>()
const text = computed(() => site[props.locale].home.stores)
</script>

<template>
  <ul class="stores" :aria-label="text.label">
    <li>
      <a class="btn btn-primary store" role="link" aria-disabled="true">
        <SiteIcon name="apple-logo-fill" class="store-icon" />App Store<span class="visually-hidden">, </span><span class="tag tag-accent store-soon">{{ text.comingSoon }}</span>
      </a>
    </li>
    <li>
      <a class="btn btn-secondary store" role="link" aria-disabled="true">
        <SiteIcon name="google-play-logo-fill" class="store-icon store-icon-play" />Google Play<span class="visually-hidden">, </span><span class="tag tag-neutral store-soon">{{ text.comingSoon }}</span>
      </a>
    </li>
  </ul>
</template>
