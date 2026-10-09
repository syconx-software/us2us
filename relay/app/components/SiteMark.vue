<script setup lang="ts">
// The us2us mark: two ends and the line between them.
defineProps<{ small?: boolean }>()
</script>

<template>
  <span class="mark" :class="{ 'mark-sm': small }" aria-hidden="true"><span /><span /><span /></span>
</template>
