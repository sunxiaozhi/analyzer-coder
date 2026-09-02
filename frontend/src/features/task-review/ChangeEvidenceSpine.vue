<script setup lang="ts">
import {
  AlertCircle,
  BookCheck,
  ChevronRight,
  CircleHelp,
  FileDiff,
  RefreshCcw,
  ShieldCheck,
  Sparkles,
} from 'lucide-vue-next';
import type {
  ChangedSymbol,
  KnowledgeMatch,
  KnowledgeReferenceCandidate,
  ModelSummaryFinding,
  TaskReviewFinding,
  TaskReviewResult,
} from '@/api/taskReviews';
import type { ReviewEvidenceSelection } from './types';
import KnowledgeFindingList from './KnowledgeFindingList.vue';
import ObligationPanel from './ObligationPanel.vue';

const props = defineProps<{ result: TaskReviewResult }>();
const emit = defineEmits<{ select: [selection: ReviewEvidenceSelection] }>();

const resolutionLabels: Record<string, string> = {
  CODEGRAPH: 'CodeGraph 节点',
  SOURCE_DECLARATION: '源码声明',
  CHUNK_SYMBOL: '同版本代码片段',
  FILE_LEVEL: '文件级降级',
};
const changeLabels: Record<string, string> = {
  ADDED: '新增', MODIFIED: '修改', DELETED: '删除', RENAMED: '重命名', COPIED: '复制',
};

function selectChange(item: ChangedSymbol) {
  const provenance = item.provenance[0];
  emit('select', {
    kind: 'CHANGE',
    eyebrow: '真实 Git 改动',
    title: item.name,
    status: `${changeLabels[item.changeType] ?? item.changeType} · ${resolutionLabels[item.resolution] ?? item.resolution}`,
    description: provenance?.detail ?? '该对象由真实文件变化和行号映射得到。',
    filePath: item.filePath,
    startLine: item.declarationStartLine,
    endLine: item.declarationEndLine,
    facts: [
      { label: '对象类型', value: item.kind },
      { label: '符号标识', value: item.symbolId, mono: true },
      { label: '旧行号', value: item.oldStartLine == null ? '不适用' : `L${item.oldStartLine}`, mono: true },
      { label: '新行号', value: item.newStartLine == null ? '不适用' : `L${item.newStartLine}`, mono: true },
      { label: '解析来源', value: resolutionLabels[item.resolution] ?? item.resolution },
    ],
    evidence: [],
    sources: [],
  });
}

function selectKnowledge(item: KnowledgeMatch, stale = false) {
  const first = item.reasons[0]?.evidence;
  emit('select', {
    kind: stale ? 'STALE' : 'KNOWLEDGE',
    eyebrow: stale ? '待重新验证的知识' : '确定性适用知识',
    title: item.title,
    status: `${item.enforcement} · ${item.sourceVersionStatus}`,
    description: stale
      ? '这条知识的适用范围命中了真实改动，但来源版本已经需要重新核对。'
      : '这条知识已发布、已人工审核，并通过确定性 Scope 规则命中。',
    knowledgeId: item.knowledgeId,
    filePath: first?.filePath,
    facts: [
      { label: '知识类型', value: item.kind },
      { label: '执行级别', value: item.enforcement },
      { label: '来源状态', value: item.sourceVersionStatus },
      { label: '修订', value: `v${item.revision}`, mono: true },
      { label: '负责人', value: item.ownerAccountId ?? '未指定', mono: true },
    ],
    evidence: item.reasons,
    sources: item.sources,
  });
}

function selectApplicableKnowledge(item: KnowledgeMatch) {
  selectKnowledge(item);
}

function selectStaleKnowledge(item: KnowledgeMatch) {
  selectKnowledge(item, true);
}

function selectReference(item: KnowledgeReferenceCandidate) {
  emit('select', {
    kind: 'KNOWLEDGE',
    eyebrow: '检索参考候选',
    title: item.title,
    status: '仅供参考，不产生义务',
    description: item.detail,
    knowledgeId: item.knowledgeId,
    facts: [
      { label: '知识类型', value: item.kind },
      { label: '检索通道', value: item.retrievalSource },
      { label: '来源状态', value: item.sourceVersionStatus },
    ],
    evidence: [],
    sources: [item.provenance],
  });
}

function selectObligation(item: TaskReviewFinding) {
  const first = item.evidence[0]?.evidence;
  emit('select', {
    kind: 'OBLIGATION',
    eyebrow: item.kind === 'REQUIRED_TEST' ? '必须执行的测试' : '必须取得的审批',
    title: item.key,
    status: item.status === 'REQUIRED_NOT_REPORTED' ? '尚未回报执行结果' : '等待审批',
    description: `由 ${item.knowledgeIds.length} 条 CURRENT 且 REQUIRED 的工程知识确定性产生。`,
    filePath: first?.filePath,
    facts: [
      { label: '要求类型', value: item.kind },
      { label: '当前状态', value: item.status },
      { label: '来源知识数', value: String(item.knowledgeIds.length) },
    ],
    evidence: item.evidence,
    sources: item.sources,
  });
}

function selectUnknown(item: TaskReviewFinding) {
  const reason = item.unknownReason;
  emit('select', {
    kind: 'UNKNOWN',
    eyebrow: '无法确定',
    title: reason?.code ?? item.key,
    status: '需要人工处理或补充事实',
    description: reason?.detail ?? '当前证据不足，系统没有扩大为违规结论。',
    filePath: reason?.filePath,
    knowledgeId: reason?.knowledgeId,
    facts: [
      { label: '原因代码', value: reason?.code ?? item.key, mono: true },
      { label: '相关规则', value: reason?.rule ?? '无' },
      { label: '处理建议', value: unknownAction(reason?.code) },
    ],
    evidence: [],
    sources: item.sources,
  });
}

function selectModelFinding(item: ModelSummaryFinding) {
  const first = item.evidence[0];
  emit('select', {
    kind: 'MODEL',
    eyebrow: 'MODEL_SUGGESTION · 非确定性结论',
    title: item.text,
    status: `已校验 ${item.evidenceIds.length} 个证据 ID`,
    description: '这是模型对既有审查证据的总结，不会创建文件、符号、规则、测试或审批。',
    filePath: first?.filePath,
    startLine: first?.startLine,
    endLine: first?.endLine,
    knowledgeId: first?.knowledgeId,
    facts: [
      { label: '来源类型', value: 'MODEL_SUGGESTION', mono: true },
      { label: '证据数量', value: String(item.evidenceIds.length) },
      { label: '证据 ID', value: item.evidenceIds.join(', '), mono: true },
    ],
    evidence: [],
    sources: item.sources,
  });
}

function unknownAction(code?: string) {
  if (!code) return '检查审查输入与仓库状态后重试';
  if (code.includes('SNAPSHOT')) return '重新扫描并发布仓库快照后重试';
  if (code.includes('CODEGRAPH') || code.includes('MODULE_GRAPH')) return '重建 CodeGraph 后重新审查';
  if (code.includes('SOURCE') || code.includes('FILE')) return '打开对应文件，人工核对无法解析的内容';
  if (code.includes('KNOWLEDGE')) return '打开知识卡片并重新验证来源版本';
  return '根据原因代码补充事实后重新审查';
}
</script>

<template>
  <div class="evidence-spine">
    <section class="spine-stage stage-change">
      <span class="stage-marker"><FileDiff :size="15" /></span>
      <header>
        <div><small>Git facts</small><h2>真实改动</h2></div>
        <b>{{ result.change?.changes.length ?? 0 }} 文件 · {{ result.changedSymbols.length }} 对象</b>
      </header>
      <div class="stage-body change-list">
        <button v-for="item in result.changedSymbols" :key="`${item.symbolId}:${item.hunkIndex}:${item.filePath}`" type="button" @click="selectChange(item)">
          <span :data-change="item.changeType">{{ changeLabels[item.changeType] ?? item.changeType }}</span>
          <div><strong>{{ item.name }}</strong><code>{{ item.filePath }}:{{ item.newStartLine ?? item.oldStartLine ?? 1 }}</code></div>
          <small>{{ item.kind }} · {{ resolutionLabels[item.resolution] ?? item.resolution }}</small>
          <ChevronRight :size="14" />
        </button>
        <p v-if="!result.changedSymbols.length">Git 没有返回可审查的改动对象。</p>
      </div>
    </section>

    <section class="spine-stage stage-knowledge">
      <span class="stage-marker"><BookCheck :size="15" /></span>
      <header>
        <div><small>Verified knowledge</small><h2>适用知识</h2></div>
        <b>{{ result.applicableKnowledge.length }} 正式 · {{ result.referenceCandidates.length }} 参考</b>
      </header>
      <div class="stage-body">
        <KnowledgeFindingList
          :matches="result.applicableKnowledge"
          :references="result.referenceCandidates"
          @select-match="selectApplicableKnowledge"
          @select-reference="selectReference"
        />
      </div>
    </section>

    <section class="spine-stage stage-obligation">
      <span class="stage-marker"><ShieldCheck :size="15" /></span>
      <header>
        <div><small>Required actions</small><h2>测试与审批</h2></div>
        <b>{{ result.requiredTests.length + result.requiredApprovals.length }} 项待处理</b>
      </header>
      <div class="stage-body">
        <ObligationPanel :tests="result.requiredTests" :approvals="result.requiredApprovals" @select="selectObligation" />
      </div>
    </section>

    <section class="spine-stage stage-stale">
      <span class="stage-marker"><RefreshCcw :size="15" /></span>
      <header>
        <div><small>Knowledge drift</small><h2>知识失效</h2></div>
        <b>{{ result.staleKnowledge.length }} 条待重新验证</b>
      </header>
      <div class="stage-body">
        <KnowledgeFindingList :matches="result.staleKnowledge" stale @select-match="selectStaleKnowledge" />
      </div>
    </section>

    <section class="spine-stage stage-unknown">
      <span class="stage-marker"><CircleHelp :size="15" /></span>
      <header>
        <div><small>Unknowns</small><h2>未知项</h2></div>
        <b>{{ result.unknowns.length }} 项没有被猜测</b>
      </header>
      <div class="stage-body unknown-list">
        <button v-for="item in result.unknowns" :key="`${item.key}:${item.unknownReason?.knowledgeId ?? ''}`" type="button" @click="selectUnknown(item)">
          <AlertCircle :size="14" />
          <span><strong>{{ item.unknownReason?.code ?? item.key }}</strong><small>{{ item.unknownReason?.detail }}</small></span>
          <ChevronRight :size="14" />
        </button>
        <p v-if="!result.unknowns.length">没有未解释的降级或证据缺口。</p>
      </div>
    </section>

    <section v-if="result.modelSummaryState.status !== 'NOT_REQUESTED'" class="spine-stage stage-model">
      <span class="stage-marker"><Sparkles :size="15" /></span>
      <header>
        <div><small>MODEL_SUGGESTION</small><h2>模型引用总结</h2></div>
        <b>{{ result.modelSummaryState.status === 'COMPLETED' ? `${result.modelSummary?.findings.length ?? 0} 条建议` : '未采用模型输出' }}</b>
      </header>
      <div class="stage-body model-summary" :data-status="result.modelSummaryState.status">
        <template v-if="result.modelSummary">
          <div class="model-boundary">
            <strong>MODEL_SUGGESTION</strong>
            <span>{{ result.modelSummary.provider }}</span>
          </div>
          <p>{{ result.modelSummary.summary }}</p>
          <div class="model-findings">
            <button v-for="item in result.modelSummary.findings" :key="`${item.text}:${item.evidenceIds.join(':')}`" type="button" @click="selectModelFinding(item)">
              <Sparkles :size="13" />
              <span><strong>{{ item.text }}</strong><small>引用 {{ item.evidence.length }} 条既有证据</small></span>
              <ChevronRight :size="14" />
            </button>
          </div>
          <ul v-if="result.modelSummary.unknowns.length">
            <li v-for="item in result.modelSummary.unknowns" :key="item">{{ item }}</li>
          </ul>
        </template>
        <div v-else class="model-discarded">
          <AlertCircle :size="15" />
          <span>
            <strong>{{ result.modelSummaryState.status === 'REJECTED' ? '模型输出已丢弃' : '模型总结不可用' }}</strong>
            <small>{{ result.modelSummaryState.code }} · {{ result.modelSummaryState.detail }}</small>
          </span>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.evidence-spine { --line: #cbd6dc; display: grid; }
.spine-stage { position: relative; display: grid; grid-template-columns: 42px minmax(0, 1fr); padding-bottom: 17px; }
.spine-stage::before { position: absolute; top: 28px; bottom: -1px; left: 20px; width: 1px; background: var(--line); content: ''; }
.spine-stage:last-child::before { display: none; }
.stage-marker { z-index: 1; display: grid; grid-column: 1; grid-row: 1; place-items: center; width: 29px; height: 29px; margin: 1px 0 0 6px; color: #fff; border: 4px solid #f5f7f8; border-radius: 50%; background: var(--app-color-action); box-sizing: content-box; }
.stage-knowledge .stage-marker { background: var(--app-color-success); }
.stage-obligation .stage-marker, .stage-stale .stage-marker { background: var(--app-color-warning); }
.stage-unknown .stage-marker { background: #76838f; }
.stage-model .stage-marker { background: var(--app-color-model); }
.spine-stage > header { display: flex; grid-column: 2; grid-row: 1; align-items: center; justify-content: space-between; gap: 14px; min-height: 38px; padding: 0 2px 8px; }
.spine-stage > header div { display: grid; gap: 1px; }
.spine-stage > header small { color: #81909a; font: 700 12px "SFMono-Regular", Consolas, monospace; letter-spacing: .08em; text-transform: uppercase; }
.spine-stage > header h2 { margin: 0; color: #1f2a33; font-size: 15px; }
.spine-stage > header b { color: #64737d; font-size: 13px; font-weight: 600; }
.stage-body { grid-column: 2; min-width: 0; padding: 11px; border: 1px solid #dbe2e7; border-radius: 9px; background: #fff; }
.change-list { display: grid; gap: 6px; }
.change-list button { display: grid; grid-template-columns: 48px minmax(0, 1fr) auto 16px; align-items: center; gap: 9px; width: 100%; padding: 9px 10px; color: #5b6871; text-align: left; border: 1px solid #dce5ec; border-radius: 7px; background: #f9fbfd; }
.change-list button:hover { border-color: #8fb5d5; background: #f3f8fc; }
.change-list button:focus-visible, .unknown-list button:focus-visible { outline: 3px solid var(--app-focus-ring); outline-offset: 2px; }
.change-list button > span { padding: 3px 5px; color: var(--app-color-action); border-radius: 4px; background: #e7f0f8; font-size: 12px; font-weight: 700; text-align: center; }
.change-list button > span[data-change="DELETED"] { color: #9a4d37; background: #f7eae6; }
.change-list button > span[data-change="RENAMED"], .change-list button > span[data-change="COPIED"] { color: var(--app-color-model); background: var(--app-color-model-soft); }
.change-list button div { display: grid; min-width: 0; gap: 2px; }
.change-list strong { color: #24323c; font-size: 14px; }
.change-list code { overflow: hidden; color: #647681; font: 13px "SFMono-Regular", Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.change-list button > small { color: #72808a; font-size: 13px; white-space: nowrap; }
.stage-body > p, .change-list > p, .unknown-list > p { margin: 0; padding: 9px; color: #75838c; font-size: 13px; }
.unknown-list { display: grid; gap: 6px; }
.unknown-list button { display: grid; grid-template-columns: 20px minmax(0, 1fr) 16px; align-items: center; gap: 7px; width: 100%; padding: 9px 10px; color: #667681; text-align: left; border: 1px solid #dfe4e7; border-radius: 7px; background: #fafbfc; }
.unknown-list button:hover { border-color: #aab4bb; }
.unknown-list button span { display: grid; min-width: 0; gap: 2px; }
.unknown-list strong { color: #42515b; font: 13px "SFMono-Regular", Consolas, monospace; }
.unknown-list small { overflow: hidden; color: #77848d; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.model-summary { display: grid; gap: 10px; border-left: 3px solid var(--app-color-model); background: #fbf9fd; }
.model-boundary { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.model-boundary strong { padding: 3px 6px; color: #684e92; border: 1px solid #cfc2df; border-radius: 4px; background: var(--app-color-model-soft); font: 700 12px "SFMono-Regular", Consolas, monospace; }
.model-boundary span { overflow: hidden; color: #786d83; font: 12px "SFMono-Regular", Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.model-summary > p { margin: 0; color: #4f4559; font-size: 13px; line-height: 1.6; }
.model-findings { display: grid; gap: 6px; }
.model-findings button { display: grid; grid-template-columns: 18px minmax(0, 1fr) 14px; align-items: center; gap: 7px; width: 100%; padding: 8px 9px; color: var(--app-color-model); text-align: left; border: 1px solid #e0d8e8; border-radius: 6px; background: #fff; }
.model-findings button:hover { border-color: #aa94c5; }
.model-findings button:focus-visible { outline: 3px solid rgb(109 93 181 / 18%); outline-offset: 2px; }
.model-findings button span { display: grid; min-width: 0; gap: 2px; }
.model-findings strong { color: #43394d; font-size: 13px; line-height: 1.45; }
.model-findings small { color: #81758b; font-size: 12px; }
.model-summary ul { display: grid; gap: 4px; margin: 0; padding: 8px 8px 8px 25px; color: #6f6479; background: #fff; font-size: 13px; line-height: 1.45; }
.model-discarded { display: grid; grid-template-columns: 20px minmax(0, 1fr); gap: 7px; color: #7c5b36; }
.model-discarded span { display: grid; gap: 2px; }
.model-discarded strong { font-size: 13px; }
.model-discarded small { color: #7d7066; font-size: 12px; line-height: 1.45; }
@media (max-width: 760px) {
  .spine-stage { grid-template-columns: 34px minmax(0, 1fr); }
  .spine-stage::before { left: 15px; }
  .stage-marker { width: 24px; height: 24px; margin-left: 1px; }
  .spine-stage > header { align-items: start; }
  .spine-stage > header b { max-width: 120px; text-align: right; }
  .change-list button { grid-template-columns: 43px minmax(0, 1fr) 14px; }
  .change-list button > small { display: none; }
}
</style>
