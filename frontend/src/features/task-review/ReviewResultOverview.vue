<script setup lang="ts">
import { computed } from 'vue';
import { AlertTriangle, CheckCircle2, FileDiff, ListChecks, ShieldCheck } from 'lucide-vue-next';
import type { TaskReviewResult } from '@/api/taskReviews';

const props = defineProps<{ result: TaskReviewResult }>();
const files = computed(() => props.result.change?.changes ?? []);
const uniqueSymbolCount = computed(() => new Set(props.result.changedSymbols.map(item => `${item.filePath}\u0000${item.symbolId}`)).size);
const additions = computed(() => files.value.reduce((total, item) => total + (item.additions ?? 0), 0));
const deletions = computed(() => files.value.reduce((total, item) => total + (item.deletions ?? 0), 0));
const obligationCount = computed(() => props.result.requiredTests.length + props.result.requiredApprovals.length);
const incomplete = computed(() => Boolean(props.result.change?.partial || props.result.change?.limitations.length || props.result.unknowns.length));
const sourceLabel = computed(() => ({ WORKTREE: '未提交改动', SINGLE_COMMIT: '单次提交', COMMIT_RANGE: '版本范围' }[props.result.changeSource]));
</script>

<template>
  <section class="result-overview" aria-labelledby="result-overview-title">
    <header>
      <div><span>审查结果</span><h2 id="result-overview-title">先确认范围，再处理待办</h2></div>
      <p :class="{ warning: incomplete }"><component :is="incomplete ? AlertTriangle : CheckCircle2" :size="15" />{{ incomplete ? '存在未覆盖范围，结论需要结合未知项判断' : 'Git 改动已完整读取，没有发现解析限制' }}</p>
    </header>
    <div class="overview-grid">
      <article>
        <FileDiff :size="18" /><div><small>本次审了什么</small><strong>{{ files.length }} 个文件 · {{ uniqueSymbolCount }} 个代码对象</strong></div>
        <p>{{ sourceLabel }}，新增 {{ additions }} 行、删除 {{ deletions }} 行。</p>
      </article>
      <article :class="{ attention: obligationCount || result.staleKnowledge.length }">
        <ListChecks :size="18" /><div><small>接下来要处理</small><strong>{{ obligationCount }} 项必须动作 · {{ result.unknowns.length }} 项人工核对</strong></div>
        <p>{{ result.requiredTests.length }} 项测试、{{ result.requiredApprovals.length }} 项审批、{{ result.staleKnowledge.length }} 条知识待验证。</p>
      </article>
      <article>
        <ShieldCheck :size="18" /><div><small>这些结论从哪里来</small><strong>{{ result.applicableKnowledge.length }} 条正式知识 · {{ result.referenceCandidates.length }} 条参考</strong></div>
        <p>测试和审批只来自已发布、已审核且命中本次改动的规则。</p>
      </article>
    </div>
    <aside><strong>如何理解结果：</strong><span>文件和行数来自 Git；测试与审批来自正式规则；模型内容只是带证据引用的总结。“没有要求”不代表代码没有风险。</span></aside>
  </section>
</template>

<style scoped>
.result-overview { display: grid; gap: 13px; padding: 17px; border: 1px solid #d6e1e6; border-top: 3px solid var(--app-color-action); border-radius: 9px; background: #fff; }
.result-overview > header { display: flex; align-items: end; justify-content: space-between; gap: 18px; }
.result-overview > header div { display: grid; gap: 2px; }
.result-overview > header span { color: var(--app-color-action); font: 700 11px "SFMono-Regular", Consolas, monospace; letter-spacing: .08em; }
.result-overview h2 { margin: 0; color: #22323c; font-size: 18px; }
.result-overview > header p { display: flex; align-items: center; gap: 6px; margin: 0; color: #39715f; font-size: 12px; }
.result-overview > header p.warning { color: #98601e; }
.overview-grid { display: grid; grid-template-columns: repeat(3, 1fr); border: 1px solid #dce4e8; border-radius: 8px; overflow: hidden; }
.overview-grid article { display: grid; grid-template-columns: 26px minmax(0, 1fr); gap: 4px 8px; min-width: 0; padding: 13px; color: #47738f; border-left: 1px solid #e1e7ea; background: #fbfcfd; }
.overview-grid article:first-child { border-left: 0; }
.overview-grid article.attention { color: #996021; background: #fffaf2; }
.overview-grid article div { display: grid; min-width: 0; gap: 3px; }
.overview-grid small { color: #7a8992; font-size: 11px; }
.overview-grid strong { color: #2a3a44; font-size: 14px; line-height: 1.35; }
.overview-grid p { grid-column: 2; margin: 0; color: #71808a; font-size: 12px; line-height: 1.45; }
.result-overview aside { display: flex; gap: 5px; padding: 9px 11px; color: #667681; border-left: 3px solid #91a5b2; background: #f5f7f8; font-size: 12px; line-height: 1.5; }
.result-overview aside strong { color: #465761; white-space: nowrap; }
@media (max-width: 860px) { .overview-grid { grid-template-columns: 1fr; } .overview-grid article { border-top: 1px solid #e1e7ea; border-left: 0; } .overview-grid article:first-child { border-top: 0; } }
@media (max-width: 600px) { .result-overview > header { align-items: start; flex-direction: column; } .result-overview aside { display: grid; } }
</style>
