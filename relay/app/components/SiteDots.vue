<script setup lang="ts">
// Heading text whose sentence-ending full stops are drawn in the body font,
// as in the design (Doto's own full stop is a single faint dot).
const props = defineProps<{ text: string }>()
const parts = computed(() => props.text.split(/\.(?=\s|$)/))
</script>

<template>
  <template v-for="(part, index) in parts" :key="index">{{ part }}<span v-if="index < parts.length - 1" class="dot">.</span></template>
</template>
