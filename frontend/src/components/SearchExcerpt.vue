<script setup lang="ts">
import { computed } from 'vue';
import { searchExcerpt } from '@/utils/searchExcerpt';
const props = defineProps<{ content: string; query: string }>();
const parts = computed(() => searchExcerpt(props.content, props.query));
</script>
<template><p class="search-excerpt"><template v-for="(part, index) in parts" :key="index"><mark v-if="part.match">{{ part.text }}</mark><template v-else>{{ part.text }}</template></template></p></template>
<style scoped>
.search-excerpt { margin: 0; color: var(--app-text-regular); font-size: 14px; line-height: 1.65; overflow-wrap: anywhere; }
mark { background: #fff0b5; color: #533e04; border-radius: 2px; }
</style>
