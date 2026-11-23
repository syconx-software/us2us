<script setup lang="ts">
import { APP_STORE_URL, GOOGLE_PLAY_URL, site, type Locale } from '~/content/site'

const props = defineProps<{ locale: Locale }>()
const text = computed(() => site[props.locale].home.stores)
</script>

<template>
  <ul class="stores" :aria-label="text.label">
    <li>
      <a class="btn btn-primary store" :href="APP_STORE_URL" rel="noopener noreferrer">
        <SiteIcon name="apple-logo-fill" class="store-icon" />App Store
      </a>
    </li>
    <li>
      <a class="btn btn-secondary store" :href="GOOGLE_PLAY_URL" rel="noopener noreferrer">
        <SiteIcon name="google-play-logo-fill" class="store-icon store-icon-play" />Google Play
      </a>
    </li>
  </ul>
  <p class="stores-note"><span class="beta-dot" aria-hidden="true" />{{ text.availability }}</p>
</template>
