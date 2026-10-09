<script setup lang="ts">
import { ICONS, type IconName } from '~/content/icons'

// An inline Phosphor icon (see content/icons.ts). Decorative: the text next
// to it carries the meaning. Sized by the CSS (`.icon` is 1em square).
const props = defineProps<{ name: IconName }>()
const paths = computed(() => ICONS[props.name])
</script>

<template>
  <svg class="icon" viewBox="0 0 256 256" fill="currentColor" aria-hidden="true" focusable="false"><path v-for="d in paths" :key="d" :d="d" /></svg>
</template>
