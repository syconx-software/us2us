<script setup lang="ts">
import { citeId, sourceId, type WhyPart } from '~/content/why'

// A run of the statement's text (content/why.ts, parsed by whyParts): plain
// text, a citation mark "[n]" that links to source n (the first one of each
// source is where the source's "back" link returns), or a link to one of our
// own pages. The hidden word makes a mark read "Kaynak 3" / "Source 3".
// A word joiner (U+2060) opens each mark, so a line never breaks between a
// mark and the word before it, nor between two marks ("[10][11]").
defineProps<{ parts: WhyPart[], mark: string }>()
</script>

<template>
  <template v-for="(part, index) in parts" :key="index">
    <template v-if="part.kind === 'text'">{{ part.text }}</template>
    <sup v-else-if="part.kind === 'cite'" class="cite">{{ '⁠' }}<a :id="part.first ? citeId(part.n) : undefined" :href="`#${sourceId(part.n)}`">[<span class="visually-hidden">{{ `${mark} ` }}</span>{{ part.n }}]</a></sup>
    <a v-else :href="part.href">{{ part.text }}</a>
  </template>
</template>
