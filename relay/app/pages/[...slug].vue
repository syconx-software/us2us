<script setup lang="ts">
// In development, unknown paths render the bilingual error page with a real
// 404 status (and without the router warning that would log every mistyped
// URL). The production server has no Vue renderer; it answers them with the
// static page of server/middleware/not-found.ts.
throw createError({ statusCode: 404, statusMessage: 'Not Found' })
</script>

<template>
  <div />
</template>
