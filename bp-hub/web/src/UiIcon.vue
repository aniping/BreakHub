<script setup>
defineProps({
  name: { type: String, required: true },
})
</script>

<template>
  <svg class="ui-icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    <template v-if="name === 'overview'">
      <path d="M4 11.5 12 5l8 6.5" />
      <path d="M6.5 10.5V20h11v-9.5M10 20v-5h4v5" />
    </template>
    <template v-else-if="name === 'interfaces'">
      <rect x="4" y="5" width="16" height="14" rx="1" />
      <path d="M4 9h16M9 9v10" />
    </template>
    <template v-else-if="name === 'breakpoints'">
      <circle cx="12" cy="12" r="7" />
      <circle cx="12" cy="12" r="2" />
    </template>
    <template v-else-if="name === 'interactions'">
      <path d="M5 8h12M14 5l3 3-3 3M19 16H7M10 13l-3 3 3 3" />
    </template>
    <template v-else-if="name === 'sessions'">
      <rect x="5" y="4" width="14" height="16" rx="1" />
      <path d="M8 8h8M8 12h8M8 16h5" />
    </template>
    <template v-else-if="name === 'settings'">
      <circle cx="12" cy="12" r="3" />
      <path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M18.4 5.6 17 7M7 17l-1.4 1.4" />
    </template>
    <template v-else-if="name === 'add'">
      <path d="M12 5v14M5 12h14" />
    </template>
    <template v-else-if="name === 'clear'">
      <path d="M5 7h14M9 7V5h6v2M8 7l1 12h6l1-12M10 10v6M14 10v6" />
    </template>
    <template v-else-if="name === 'play'">
      <path d="m8 5 11 7-11 7Z" />
    </template>
    <template v-else-if="name === 'stop'">
      <rect x="6" y="6" width="12" height="12" rx="1" />
    </template>
    <template v-else-if="name === 'release'">
      <path d="M8 11V8a4 4 0 0 1 7.7-1.5M6 11h12v9H6zM12 14v3" />
    </template>
    <template v-else-if="name === 'refresh'">
      <path d="M19 8a7 7 0 1 0 .3 7M19 4v4h-4" />
    </template>
    <template v-else-if="name === 'logout'">
      <path d="M10 5H5v14h5M14 8l4 4-4 4M9 12h9" />
    </template>
    <template v-else-if="name === 'close'">
      <path d="m6 6 12 12M18 6 6 18" />
    </template>
    <template v-else-if="name === 'chevron'">
      <path d="m9 6 6 6-6 6" />
    </template>
    <template v-else-if="name === 'filter'">
      <path d="M4 6h16M7 12h10M10 18h4" />
    </template>
  </svg>
</template>

<style scoped>
.ui-icon {
  display: block;
  width: 16px;
  height: 16px;
  flex: 0 0 auto;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.75;
}
</style>
