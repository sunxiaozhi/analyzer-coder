<script setup lang="ts">
import { computed } from 'vue';
import {
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  CircleDashed,
  FileCode2,
  GitBranch,
  Network,
  SearchCheck,
  ShieldCheck,
} from 'lucide-vue-next';
import type {
  ProjectArchitectureMap,
  ProjectProfile,
  RepositoryPreparation,
} from '@/api/repositories';
import type { Repository, RepositorySnapshotFiles } from '@/types/api';

interface Props {
  repository: Repository;
  preparation: RepositoryPreparation;
  snapshot: RepositorySnapshotFiles | null;
  architecture: ProjectArchitectureMap | null;
  profile: ProjectProfile;
}

type Target = 'architecture' | 'structure' | 'assets' | 'indexing' | 'ask';
type Tone = 'ready' | 'warning' | 'danger' | 'muted';

const props = defineProps<Props>();
const emit = defineEmits<{
  navigate: [target: Target];
  openFile: [path: string];
  openModule: [module: string];
  ask: [task: string];
}>();

const coverage = computed(() => props.architecture?.coverage ?? null);
const architectureCoverage = computed(() => {
  if (!coverage.value?.totalCodeFiles) return 0;
  return Math.round((coverage.value.analyzedFiles / coverage.value.totalCodeFiles) * 100);
});
const vectorCoverage = computed(() => {
  if (!props.profile.chunkCount) return 0;
  return Math.round((props.profile.vectorizedChunks / props.profile.chunkCount) * 100);
});
const highRiskCount = computed(() =>
  props.architecture?.risks.filter(risk => risk.severity === 'HIGH').length ?? 0,
);
const resourceCount = computed(() =>
  props.architecture?.nodes.filter(node => node.kind === 'RESOURCE').length ?? 0,
);
const analysisLimited = computed(() => Boolean(
  props.repository.dirty
  || coverage.value?.partial
  || props.profile.missingChunks
  || props.preparation.state !== 'READY',
));
const verdict = computed(() => {
  if (!props.repository.snapshotId || !props.profile.chunkCount) {
    return {
      tone: 'danger' as Tone,
      label: '暂不能用于改动判断',
      detail: '当前仓库没有可用的代码快照或内容索引。先完成索引，再判断入口和影响范围。',
    };
  }
  if (analysisLimited.value) {
    return {
      tone: 'warning' as Tone,
      label: '可用于定位，影响判断受限',
      detail: '已有真实代码证据，但工作区状态或分析覆盖不完整；涉及跨模块修改时需要人工补充核验。',
    };
  }
  return {
    tone: 'ready' as Tone,
    label: '可用于改动前分析',
    detail: '当前快照、代码索引与架构扫描均可用，可以从入口、依赖和引用证据开始评估改动。',
  };
});

const signals = computed(() => [
  {
    label: '代码版本',
    value: props.snapshot?.commit?.slice(0, 10) ?? '未发布',
    detail: props.repository.dirty ? '工作区有未进入快照的变更' : `分支 ${props.snapshot?.branch ?? props.repository.branch ?? '未知'}`,
    tone: (props.repository.dirty ? 'warning' : props.repository.snapshotId ? 'ready' : 'danger') as Tone,
  },
  {
    label: '内容证据',
    value: `${props.profile.chunkCount} 片段`,
    detail: props.profile.missingChunks ? `${props.profile.missingChunks} 条尚未向量化` : '检索内容已完整入库',
    tone: (props.profile.chunkCount ? props.profile.missingChunks ? 'warning' : 'ready' : 'danger') as Tone,
  },
  {
    label: '架构扫描',
    value: props.architecture ? `${architectureCoverage.value}%` : '无结果',
    detail: coverage.value
      ? `${coverage.value.analyzedFiles}/${coverage.value.totalCodeFiles} 个代码文件，跳过 ${coverage.value.skippedLargeFiles + coverage.value.skippedByLimit + coverage.value.unreadableFiles} 个`
      : '尚未生成模块依赖证据',
    tone: (props.architecture ? coverage.value?.partial ? 'warning' : 'ready' : 'muted') as Tone,
  },
  {
    label: props.profile.retrievalCapabilityLabel,
    value: `${vectorCoverage.value}%`,
    detail: vectorCoverage.value === 100
      ? `全部片段可参与${props.profile.retrievalCapabilityLabel}召回`
      : '问答可能退化为关键词检索',
    tone: (vectorCoverage.value === 100 ? 'ready' : vectorCoverage.value > 0 ? 'warning' : 'muted') as Tone,
  },
]);

const findings = computed(() => {
  const result: Array<{
    id: string;
    tone: Tone;
    title: string;
    detail: string;
    action: string;
    target: Target;
    task?: string;
  }> = [];
  if (props.preparation.state === 'ACTION_REQUIRED') {
    result.push({
      id: 'preparation', tone: 'danger', title: '索引任务需要处理',
      detail: props.preparation.message, action: '查看失败环节', target: 'indexing',
    });
  }
  if (props.repository.dirty) {
    result.push({
      id: 'dirty', tone: 'warning', title: '分析结果未覆盖工作区变更',
      detail: '当前总览绑定已发布快照；本地未提交内容不会出现在引用和影响范围中。',
      action: '更新项目索引', target: 'indexing',
    });
  }
  for (const risk of props.architecture?.risks ?? []) {
    result.push({
      id: risk.id,
      tone: risk.severity === 'HIGH' ? 'danger' : 'warning',
      title: risk.title,
      detail: risk.detail,
      action: '核对关系证据',
      target: 'architecture',
      task: `分析架构风险“${risk.title}”的真实影响范围、证据和处理顺序`,
    });
  }
  if (coverage.value?.partial) {
    result.push({
      id: 'coverage', tone: 'warning', title: '架构影响范围不完整',
      detail: coverage.value.notes[0] || '部分代码文件未被静态分析，依赖矩阵不能作为完整影响清单。',
      action: '查看扫描边界', target: 'architecture',
    });
  }
  if (!props.profile.entryPoints.length) {
    result.push({
      id: 'entry', tone: 'muted', title: '没有识别出明确启动入口',
      detail: '接手者需要从 README、构建配置或部署文件人工确认系统如何启动。',
      action: '查看关键资产', target: 'assets',
    });
  }
  if (!result.length) {
    result.push({
      id: 'clear', tone: 'ready', title: '没有发现阻断改动分析的问题',
      detail: '可以从入口文件进入调用链，再用模块依赖核对跨边界影响。',
      action: '从入口开始', target: 'structure',
    });
  }
  return result.slice(0, 5);
});

const mostConnectedModule = computed(() => {
  if (!props.architecture) return null;
  const weights = new Map<string, number>();
  for (const edge of props.architecture.edges) {
    if (edge.relation !== 'DEPENDS_ON') continue;
    weights.set(edge.source, (weights.get(edge.source) ?? 0) + edge.weight);
    weights.set(edge.target, (weights.get(edge.target) ?? 0) + edge.weight);
  }
  const id = [...weights.entries()].sort((left, right) => right[1] - left[1])[0]?.[0];
  return id ? props.architecture.nodes.find(node => node.id === id && node.kind === 'MODULE') ?? null : null;
});

const startingPoints = computed(() => {
  const result: Array<{
    id: string;
    icon: typeof FileCode2;
    label: string;
    value: string;
    detail: string;
    action: () => void;
  }> = [];
  const entry = props.profile.entryPoints[0];
  if (entry) result.push({
    id: 'entry', icon: FileCode2, label: '启动入口', value: entry,
    detail: '先确认启动、装配与第一层调用', action: () => emit('openFile', entry),
  });
  if (mostConnectedModule.value) result.push({
    id: 'module', icon: Network, label: '高连接模块', value: mostConnectedModule.value.label,
    detail: '修改前优先核对上下游引用', action: () => emit('openModule', mostConnectedModule.value!.id),
  });
  const keyAsset = props.profile.keyAssets[0];
  if (keyAsset) result.push({
    id: 'asset', icon: ShieldCheck, label: '项目约束', value: keyAsset.path,
    detail: '编码前先确认规则和设计边界', action: () => emit('openFile', keyAsset.path),
  });
  if (resourceCount.value) result.push({
    id: 'resource', icon: GitBranch, label: '运行边界', value: `${resourceCount.value} 个外部资源`,
    detail: '核对数据库、消息与远程调用', action: () => emit('navigate', 'architecture'),
  });
  return result.slice(0, 4);
});

function followFinding(finding: (typeof findings.value)[number]) {
  if (finding.target === 'ask' && finding.task) {
    emit('ask', finding.task);
    return;
  }
  emit('navigate', finding.target);
}

function askFinding(finding: (typeof findings.value)[number]) {
  emit('ask', finding.task ?? `解释“${finding.title}”的代码证据、影响范围和下一步核验方法`);
}
</script>

<template>
  <section class="decision-brief">
    <div class="decision-main">
      <header class="verdict" :data-tone="verdict.tone">
        <span class="verdict-mark">
          <CheckCircle2 v-if="verdict.tone === 'ready'" :size="20" />
          <AlertTriangle v-else-if="verdict.tone === 'warning' || verdict.tone === 'danger'" :size="20" />
          <CircleDashed v-else :size="20" />
        </span>
        <div>
          <span class="eyebrow">改动决策简报</span>
          <h2>{{ verdict.label }}</h2>
          <p>{{ verdict.detail }}</p>
        </div>
      </header>

      <div class="finding-list">
        <article v-for="finding in findings" :key="finding.id" :data-tone="finding.tone">
          <i></i>
          <div>
            <strong>{{ finding.title }}</strong>
            <p>{{ finding.detail }}</p>
          </div>
          <div class="finding-actions">
            <button v-if="finding.task" type="button" class="quiet" @click="askFinding(finding)">分析影响</button>
            <button type="button" @click="followFinding(finding)">{{ finding.action }} <ArrowRight :size="12" /></button>
          </div>
        </article>
      </div>
    </div>

    <aside class="evidence-ledger">
      <header>
        <div><span class="eyebrow">证据状态</span><h2>这份分析依据什么</h2></div>
        <SearchCheck :size="19" />
      </header>
      <div class="signal-list">
        <article v-for="signal in signals" :key="signal.label" :data-tone="signal.tone">
          <i></i>
          <div><span>{{ signal.label }}</span><small>{{ signal.detail }}</small></div>
          <strong>{{ signal.value }}</strong>
        </article>
      </div>
      <footer>
        <span>所有结论绑定当前 Snapshot</span>
        <button type="button" @click="emit('navigate', 'assets')">核对事实来源</button>
      </footer>
    </aside>

    <div v-if="startingPoints.length" class="starting-points">
      <header><span class="eyebrow">建议起点</span><strong>从可验证事实进入项目</strong></header>
      <button v-for="point in startingPoints" :key="point.id" type="button" @click="point.action">
        <span class="point-icon"><component :is="point.icon" :size="15" /></span>
        <span><small>{{ point.label }}</small><strong>{{ point.value }}</strong><em>{{ point.detail }}</em></span>
        <ArrowRight :size="13" />
      </button>
    </div>
  </section>
</template>

<style scoped>
.decision-brief { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(310px, .75fr); overflow: hidden; border: 1px solid var(--el-border-color, #dedee3); border-radius: 7px; background: #fff; }
.decision-main { min-width: 0; border-right: 1px solid #e8e8ec; }
.eyebrow { color: var(--app-text-muted); font-size: 13px; font-weight: 650; letter-spacing: .04em; }
.verdict { display: grid; grid-template-columns: 42px minmax(0, 1fr); gap: 12px; padding: 17px 18px 14px; border-bottom: 1px solid #ececef; }
.verdict-mark { display: grid; width: 40px; height: 40px; place-items: center; color: #65656c; border-radius: 7px; background: #f0f1f3; }
.verdict[data-tone='ready'] .verdict-mark { color: var(--app-color-success); background: var(--app-color-success-soft); }
.verdict[data-tone='warning'] .verdict-mark { color: #a96010; background: #fff2df; }
.verdict[data-tone='danger'] .verdict-mark { color: var(--app-color-danger); background: #fff0f0; }
.verdict h2 { margin: 5px 0 4px; color: var(--el-text-color-primary, #1d1d1f); font-size: 18px; font-weight: 650; }
.verdict p { max-width: 760px; margin: 0; color: #5f6973; font-size: 14px; line-height: 1.6; }
.finding-list { display: grid; }
.finding-list article { display: grid; grid-template-columns: 8px minmax(0, 1fr) auto; align-items: center; gap: 10px; min-height: 62px; padding: 10px 14px 10px 18px; border-bottom: 1px solid #f0f0f2; }
.finding-list article:last-child { border-bottom: 0; }
.finding-list article:hover { background: #fafbfc; }
.finding-list i, .signal-list i { width: 7px; height: 7px; border-radius: 50%; background: #a7a7ad; }
.finding-list article[data-tone='ready'] i, .signal-list article[data-tone='ready'] i { background: var(--app-color-success); }
.finding-list article[data-tone='warning'] i, .signal-list article[data-tone='warning'] i { background: #c27719; }
.finding-list article[data-tone='danger'] i, .signal-list article[data-tone='danger'] i { background: var(--app-color-danger); }
.finding-list article > div:nth-child(2) { min-width: 0; }
.finding-list strong { color: #35353a; font-size: 14px; }
.finding-list p { margin: 3px 0 0; overflow: hidden; color: var(--app-text-muted); font-size: 14px; line-height: 1.5; text-overflow: ellipsis; white-space: nowrap; }
.finding-actions { display: flex; flex: none; gap: 5px; }
.finding-actions button, .evidence-ledger footer button { display: inline-flex; align-items: center; gap: 4px; padding: 5px 7px; color: var(--app-color-action); border: 1px solid #a9cae8; border-radius: 4px; background: #fff; font-size: 13px; }
.finding-actions button:hover, .finding-actions button:focus-visible, .evidence-ledger footer button:hover, .evidence-ledger footer button:focus-visible { outline: none; background: #f0f7fd; }
.finding-actions .quiet { color: #65656c; border-color: #dedee3; }
.evidence-ledger { display: grid; grid-template-rows: auto 1fr auto; min-width: 0; background: #fafafa; }
.evidence-ledger > header { display: flex; min-height: 70px; align-items: center; justify-content: space-between; gap: 12px; padding: 13px 15px; color: var(--app-color-action); border-bottom: 1px solid #e8e8ec; }
.evidence-ledger h2 { margin: 5px 0 0; color: #2e2e33; font-size: 14px; font-weight: 650; }
.signal-list { display: grid; align-content: start; }
.signal-list article { display: grid; grid-template-columns: 8px minmax(0, 1fr) auto; align-items: center; gap: 8px; min-height: 55px; padding: 8px 14px; border-bottom: 1px solid #e8e8ec; }
.signal-list div { display: grid; min-width: 0; gap: 3px; }
.signal-list span { color: #505057; font-size: 14px; font-weight: 600; }
.signal-list small { overflow: hidden; color: var(--app-text-muted); font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.signal-list strong { color: #2d3740; font: 600 13px "SFMono-Regular", Consolas, monospace; }
.evidence-ledger footer { display: flex; min-height: 44px; align-items: center; justify-content: space-between; gap: 8px; padding: 8px 14px; color: var(--app-text-muted); font-size: 13px; }
.starting-points { grid-column: 1 / -1; display: grid; grid-template-columns: 150px repeat(4, minmax(0, 1fr)); border-top: 1px solid #e8e8ec; }
.starting-points > header { display: grid; align-content: center; gap: 5px; padding: 10px 14px; background: #fafafa; }
.starting-points > header strong { color: #3f3f45; font-size: 13px; }
.starting-points > button { display: grid; grid-template-columns: 30px minmax(0, 1fr) 14px; align-items: center; gap: 8px; min-height: 74px; padding: 9px 11px; text-align: left; border: 0; border-left: 1px solid #e8e8ec; background: #fff; }
.starting-points > button:hover, .starting-points > button:focus-visible { color: var(--app-color-action); outline: none; background: #f4f9fd; }
.point-icon { display: grid; width: 29px; height: 29px; place-items: center; color: var(--app-color-action); border-radius: 5px; background: var(--app-color-action-soft); }
.starting-points button > span:nth-child(2) { display: grid; min-width: 0; gap: 2px; }
.starting-points small { color: var(--app-text-muted); font-size: 13px; }
.starting-points strong { overflow: hidden; color: #3e4851; font: 600 13px "SFMono-Regular", Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.starting-points em { overflow: hidden; color: var(--app-text-muted); font-size: 13px; font-style: normal; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 1080px) {
  .decision-brief { grid-template-columns: 1fr; }
  .decision-main { border-right: 0; border-bottom: 1px solid #e8e8ec; }
  .starting-points { grid-template-columns: repeat(2, 1fr); }
  .starting-points > header { grid-column: 1 / -1; }
}
@media (max-width: 680px) {
  .finding-list article { grid-template-columns: 8px minmax(0, 1fr); }
  .finding-actions { grid-column: 2; }
  .starting-points { grid-template-columns: 1fr; }
  .starting-points > button { border-top: 1px solid #e8e8ec; border-left: 0; }
}
</style>
