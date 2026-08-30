<script setup lang="ts">
import { CheckSquare, CornerDownRight, UserCheck } from 'lucide-vue-next';
import type { TaskReviewFinding } from '@/api/taskReviews';

defineProps<{
  tests: TaskReviewFinding[];
  approvals: TaskReviewFinding[];
}>();
const emit = defineEmits<{
  select: [finding: TaskReviewFinding];
}>();
</script>

<template>
  <div class="obligation-grid">
    <section>
      <header><CheckSquare :size="14" /><span>必须执行的测试</span><b>{{ tests.length }}</b></header>
      <button v-for="item in tests" :key="item.key" type="button" @click="emit('select', item)">
        <span><strong class="mono">{{ item.key }}</strong><small>尚未回报执行结果 · {{ item.knowledgeIds.length }} 条知识要求</small></span>
        <em>待执行</em><CornerDownRight :size="13" />
      </button>
      <p v-if="!tests.length">没有必须执行的测试要求。</p>
    </section>
    <section>
      <header><UserCheck :size="14" /><span>需要的审批</span><b>{{ approvals.length }}</b></header>
      <button v-for="item in approvals" :key="item.key" type="button" @click="emit('select', item)">
        <span><strong class="mono">{{ item.key }}</strong><small>{{ item.knowledgeIds.length }} 条知识共同要求</small></span>
        <em>待审批</em><CornerDownRight :size="13" />
      </button>
      <p v-if="!approvals.length">没有额外审批要求。</p>
    </section>
  </div>
</template>

<style scoped>
.obligation-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.obligation-grid section { min-width: 0; border: 1px solid #e5ddd3; border-radius: 7px; overflow: hidden; background: #fffdf9; }
.obligation-grid header { display: grid; grid-template-columns: 18px 1fr auto; align-items: center; gap: 6px; min-height: 35px; padding: 0 10px; color: #76572f; border-bottom: 1px solid #eee4d7; font-size: 11px; }
.obligation-grid header b { font-size: 12px; }
.obligation-grid button { display: grid; grid-template-columns: minmax(0, 1fr) auto 14px; align-items: center; gap: 7px; width: 100%; padding: 10px; color: #6b604f; text-align: left; border: 0; border-bottom: 1px solid #f1e9df; background: transparent; }
.obligation-grid button:hover { background: #fff7ea; }
.obligation-grid button:focus-visible { outline: 3px solid #b96a192b; outline-offset: -3px; }
.obligation-grid button > span { display: grid; min-width: 0; gap: 3px; }
.obligation-grid strong { overflow: hidden; color: #3c352b; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.obligation-grid small { color: #887a68; font-size: 10px; }
.obligation-grid em { padding: 3px 6px; color: #9d5e16; border-radius: 4px; background: #f9e8d2; font-size: 10px; font-style: normal; white-space: nowrap; }
.obligation-grid p { margin: 0; padding: 12px; color: #8b8174; font-size: 11px; }
@media (max-width: 760px) { .obligation-grid { grid-template-columns: 1fr; } }
</style>
