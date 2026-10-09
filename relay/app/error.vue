<script setup lang="ts">
import type { NuxtError } from '#app'
import { notFoundText, paths } from '~/content/site'

// Development only: the production server has no Vue renderer and answers
// unknown paths with the static page of app/content/not-found.ts.
const props = defineProps<{ error: NuxtError }>()
const notFound = computed(() => props.error.statusCode === 404)

useHead({ htmlAttrs: { lang: 'en' }, title: notFound.value ? 'Not found · us2us' : 'Error · us2us' })
</script>

<template>
  <div class="bg-glow" aria-hidden="true" />
  <div class="bg-dots" aria-hidden="true" />
  <SiteHeader locale="en" page="error" />
  <main id="main" class="site-main">
    <div class="container page-pad error-page">
      <p class="eyebrow">{{ error.statusCode }}</p>
      <h1 class="display display-page">{{ notFound ? 'Not found' : 'Error' }}</h1>
      <p class="lead" lang="tr">{{ notFound ? notFoundText.tr.message : 'Bir şeyler ters gitti.' }}</p>
      <p class="lead">{{ notFound ? notFoundText.en.message : 'Something went wrong.' }}</p>
      <p class="error-links"><a class="btn btn-secondary" :href="paths.tr.home" lang="tr">{{ notFoundText.tr.home }}</a> <a class="btn btn-secondary" :href="paths.en.home">{{ notFoundText.en.home }}</a></p>
    </div>
  </main>
  <SiteFooter locale="en" />
</template>
