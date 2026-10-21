<script setup lang="ts">
import { computed } from 'vue';
import type { Citation } from '@/api/intelligence';
import { renderAnswerCitations } from './answerCitations';

const props = defineProps<{ answer: string; citations: Citation[] }>();
const emit = defineEmits<{ locateCitation: [rank: number] }>();
const html = computed(() => renderAnswerCitations(props.answer, props.citations));
function locate(event: MouseEvent) {
  const button = (event.target as Element).closest<HTMLButtonElement>('button[data-citation-rank]');
  if (button) emit('locateCitation', Number(button.dataset.citationRank));
}
</script>

<template>
  <div class="answer-markdown" @click="locate" v-html="html"></div>
</template>

<style scoped>
.answer-markdown :deep(.answer-citation) { display:inline; max-width:100%; margin:0 3px; padding:1px 6px; border:1px solid var(--app-border-strong, #c9dbe9); border-radius:4px; background:var(--app-color-action-soft, #edf5fc); color:var(--app-color-action, #005eb8); font:inherit; font-size:.88em; line-height:inherit; text-align:left; overflow-wrap:anywhere; cursor:pointer; }
.answer-markdown :deep(.answer-citation:focus-visible) { outline:2px solid var(--app-color-action); outline-offset:2px; }
</style>
