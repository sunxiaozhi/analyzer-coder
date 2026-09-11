<script setup lang="ts">
import {
  ArrowRight,
  BookOpenCheck,
  CircleAlert,
  CircleCheck,
  Code2,
  Database,
  FolderOpen,
  GitBranch,
  ListChecks,
  MessageSquareText,
  RefreshCw,
  ScrollText,
  Settings2,
  Users,
} from 'lucide-vue-next';
import { computed, shallowRef, watch } from 'vue';
import { useRouter } from 'vue-router';
import { getRepositoryProfile, type RepositoryPreparation } from '@/api/repositories';
import { useAuthStore } from '@/stores/authStore';
import { useRepositoryStore } from '@/stores/repositoryStore';

type StatusTone = 'success' | 'warning' | 'danger' | 'neutral';
type CardAccent = 'action' | 'evidence' | 'model' | 'warning' | 'success';

interface GuideCard {
  key: string;
  number: string;
  title: string;
  description: string;
  source: string;
  status: string;
  statusTone: StatusTone;
  action: string;
  target: string;
  icon: object;
  accent: CardAccent;
}

const router = useRouter();
const auth = useAuthStore();
const repositoryStore = useRepositoryStore();
const preparation = shallowRef<RepositoryPreparation | null>(null);
const preparationLoading = shallowRef(false);
const preparationError = shallowRef<string | null>(null);
let requestVersion = 0;

const repository = computed(() => repositoryStore.selectedRepository);
const snapshotId = computed(() => preparation.value?.snapshotId ?? repository.value?.snapshotId ?? null);
const hasEvidence = computed(() => Boolean(snapshotId.value));
const canMaintainKnowledge = computed(() => (
  auth.isAdmin || Boolean(repository.value?.capabilities.canUpdate)
));

const preparationStatus = computed<{ label: string; tone: StatusTone }>(() => {
  if (!repository.value) return { label: '需要选择项目', tone: 'warning' };
  if (preparationLoading.value) return { label: '正在读取准备状态', tone: 'neutral' };
  if (preparationError.value) return { label: '状态读取失败', tone: 'danger' };
  switch (preparation.value?.state) {
    case 'READY':
      return { label: '证据已就绪', tone: 'success' };
    case 'PROCESSING':
      return { label: '证据准备中 ' + preparation.value.progress + '%', tone: 'warning' };
    case 'DEGRADED':
      return { label: '部分能力降级', tone: 'warning' };
    case 'ACTION_REQUIRED':
      return { label: '需要处理', tone: 'danger' };
    default:
      return { label: snapshotId.value ? '已有可用快照' : '尚未准备证据', tone: snapshotId.value ? 'success' : 'warning' };
  }
});

const repositoryMeta = computed(() => {
  if (!repository.value) return '选择后才能读取仓库快照、代码证据和知识数据';
  const branch = preparation.value?.branch ?? repository.value.branch ?? '分支未知';
  const commit = preparation.value?.commitSha ?? repository.value.commit;
  return commit ? branch + ' · ' + commit.slice(0, 8) : branch;
});

function evidenceAccess(readyLabel: string) {
  if (!repository.value) {
    return { status: '需要选择项目', statusTone: 'warning' as const, action: '选择项目', target: '/repositories' };
  }
  if (!hasEvidence.value) {
    return { status: '等待证据快照', statusTone: 'warning' as const, action: '先准备证据', target: '/overview' };
  }
  return { status: readyLabel, statusTone: 'success' as const, action: '进入功能', target: '' };
}

const workflowCards = computed<GuideCard[]>(() => {
  const code = evidenceAccess('可以使用');
  const ask = evidenceAccess('可以提问');
  const knowledge = !repository.value
    ? { status: '需要选择项目', statusTone: 'warning' as const, action: '选择项目', target: '/repositories' }
    : {
        status: canMaintainKnowledge.value ? '可以维护' : '只读查看',
        statusTone: canMaintainKnowledge.value ? 'success' as const : 'neutral' as const,
        action: '进入功能',
        target: '/knowledge',
      };

  return [
    {
      key: 'project',
      number: '01',
      title: '选择项目',
      description: '选择当前账号已获授权的仓库，后续页面都以这个仓库为上下文。',
      source: '仓库列表、当前账号授权与仓库选择偏好',
      status: repository.value ? '已选择 ' + repository.value.name : '需要选择项目',
      statusTone: repository.value ? 'success' : 'warning',
      action: repository.value ? '管理项目' : '选择项目',
      target: '/repositories',
      icon: FolderOpen,
      accent: 'action',
    },
    {
      key: 'prepare',
      number: '02',
      title: '准备证据',
      description: '生成当前快照，并准备代码片段、向量、图谱和知识失效检查。',
      source: '仓库 /profile、索引任务与当前快照',
      status: preparationStatus.value.label,
      statusTone: preparationStatus.value.tone,
      action: repository.value ? '查看准备状态' : '选择项目',
      target: repository.value ? '/overview' : '/repositories',
      icon: Database,
      accent: 'evidence',
    },
    {
      key: 'knowledge',
      number: '03',
      title: '知识库',
      description: '查看项目 Markdown 与知识卡片；有维护权限时可补充项目约束。',
      source: 'knowledge_cards、Markdown 来源与漂移记录',
      status: knowledge.status,
      statusTone: knowledge.statusTone,
      action: knowledge.action,
      target: knowledge.target,
      icon: BookOpenCheck,
      accent: 'success',
    },
    {
      key: 'code',
      number: '04',
      title: '代码与知识',
      description: '用一个查询同时检索源码片段与项目知识，并回到原始文件和行号核对。',
      source: '当前快照、code_chunks 与已发布知识',
      status: code.status,
      statusTone: code.statusTone,
      action: code.action,
      target: code.target || '/search',
      icon: Code2,
      accent: 'evidence',
    },
    {
      key: 'ask',
      number: '05',
      title: '问项目',
      description: '需要自然语言总结时再使用问答，并从引用返回代码或知识证据。',
      source: '联合检索结果与可选问答模型',
      status: ask.status,
      statusTone: ask.statusTone,
      action: ask.action,
      target: ask.target || '/ask',
      icon: MessageSquareText,
      accent: 'model',
    },
  ];
});

const administrationCards: GuideCard[] = [
  {
    key: 'jobs',
    number: 'A1',
    title: '索引任务',
    description: '查看后台准备任务、当前步骤、失败原因和重试结果。',
    source: 'index_jobs 与可见仓库信息',
    status: '管理员功能',
    statusTone: 'neutral',
    action: '查看任务',
    target: '/indexing',
    icon: ListChecks,
    accent: 'evidence',
  },
  {
    key: 'models',
    number: 'A2',
    title: '模型配置',
    description: '配置并检测问答模型和向量模型，查看实际运行状态。',
    source: '模型配置、连接检测与运行状态',
    status: '管理员功能',
    statusTone: 'neutral',
    action: '配置模型',
    target: '/settings',
    icon: Settings2,
    accent: 'model',
  },
  {
    key: 'accounts',
    number: 'A3',
    title: '账号权限',
    description: '维护平台账号与访问令牌；仓库授权在项目管理中完成。',
    source: 'accounts、访问令牌与仓库授权',
    status: '管理员功能',
    statusTone: 'neutral',
    action: '管理账号',
    target: '/accounts',
    icon: Users,
    accent: 'action',
  },
  {
    key: 'audit',
    number: 'A4',
    title: '审计日志',
    description: '查询平台已记录的登录、账号、权限、令牌和治理事件。',
    source: 'audit_events 最近记录',
    status: '管理员功能',
    statusTone: 'neutral',
    action: '查看日志',
    target: '/audit',
    icon: ScrollText,
    accent: 'warning',
  },
];

async function loadPreparation(repositoryId: string | null) {
  const version = ++requestVersion;
  preparation.value = null;
  preparationError.value = null;
  if (!repositoryId) return;

  preparationLoading.value = true;
  try {
    const result = await getRepositoryProfile(repositoryId);
    if (version === requestVersion) preparation.value = result;
  } catch (error) {
    if (version === requestVersion) {
      preparationError.value = error instanceof Error ? error.message : '读取项目准备状态失败';
    }
  } finally {
    if (version === requestVersion) preparationLoading.value = false;
  }
}

function navigate(target: string) {
  void router.push(target);
}

watch(
  () => repositoryStore.selectedRepositoryId,
  repositoryId => void loadPreparation(repositoryId),
  { immediate: true },
);
</script>

<template>
  <div class="feature-guide-page">
    <header class="guide-intro">
      <div>
        <span class="guide-kicker">使用帮助</span>
        <h1>功能导航</h1>
        <p>按实际工作顺序进入功能，并在操作前确认当前项目、数据来源和证据状态。</p>
      </div>
      <div class="guide-principle">
        <CircleCheck :size="18" aria-hidden="true" />
        <span>页面数据始终按当前账号的仓库授权隔离</span>
      </div>
    </header>

    <section class="repository-context" aria-labelledby="current-project-title">
      <div class="repository-icon"><GitBranch :size="22" aria-hidden="true" /></div>
      <div class="repository-copy">
        <span id="current-project-title">当前项目</span>
        <strong>{{ repository?.name ?? '尚未选择项目' }}</strong>
        <small>{{ repositoryMeta }}</small>
      </div>
      <span class="status-pill" :class="'status-pill--' + preparationStatus.tone" aria-live="polite">
        {{ preparationStatus.label }}
      </span>
      <button class="context-action" type="button" @click="navigate('/repositories')">
        {{ repository ? '切换项目' : '选择项目' }}
        <ArrowRight :size="15" aria-hidden="true" />
      </button>
    </section>

    <div v-if="preparationError" class="profile-warning" role="alert">
      <CircleAlert :size="17" aria-hidden="true" />
      <span>准备状态读取失败：{{ preparationError }}</span>
      <button type="button" @click="loadPreparation(repositoryStore.selectedRepositoryId)">
        <RefreshCw :size="14" aria-hidden="true" />
        重试
      </button>
    </div>

    <section class="guide-section" aria-labelledby="workflow-title">
      <div class="section-heading">
        <div>
          <span>推荐顺序</span>
          <h2 id="workflow-title">从项目接入到代码与知识联合检索</h2>
        </div>
        <p>卡片状态来自当前项目；“数据来源”说明页面结论由哪里产生。</p>
      </div>

      <div class="workflow-grid">
        <article
          v-for="card in workflowCards"
          :key="card.key"
          class="workflow-card"
          :class="'workflow-card--' + card.accent"
        >
          <header>
            <span class="step-number">{{ card.number }}</span>
            <span class="card-icon"><component :is="card.icon" :size="21" aria-hidden="true" /></span>
            <span class="status-pill" :class="'status-pill--' + card.statusTone">{{ card.status }}</span>
          </header>
          <h3>{{ card.title }}</h3>
          <p>{{ card.description }}</p>
          <div class="source-line">
            <span>数据来源</span>
            <strong>{{ card.source }}</strong>
          </div>
          <button class="workflow-action" type="button" @click="navigate(card.target)">
            {{ card.action }}
            <ArrowRight :size="15" aria-hidden="true" />
          </button>
        </article>
      </div>
    </section>

    <section v-if="auth.isAdmin" class="guide-section administration" aria-labelledby="administration-title">
      <div class="section-heading">
        <div>
          <span>系统管理</span>
          <h2 id="administration-title">平台运行与权限</h2>
        </div>
        <p>仅超级管理员可见，操作范围不随当前项目切换。</p>
      </div>

      <div class="administration-grid">
        <article v-for="card in administrationCards" :key="card.key" class="administration-card">
          <span class="card-icon"><component :is="card.icon" :size="20" aria-hidden="true" /></span>
          <div>
            <h3>{{ card.title }}</h3>
            <p>{{ card.description }}</p>
            <small><b>数据来源</b>{{ card.source }}</small>
          </div>
          <button type="button" @click="navigate(card.target)">
            {{ card.action }}
            <ArrowRight :size="15" aria-hidden="true" />
          </button>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.feature-guide-page {
  min-width: 0;
  min-height: 0;
  height: 100%;
  padding: 24px 28px 36px;
  overflow-y: auto;
  color: var(--app-text-primary);
}

.guide-intro {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 24px;
  max-width: 1320px;
  margin: 0 auto 20px;
}

.guide-kicker,
.section-heading span {
  color: var(--app-color-action);
  font-size: 12px;
  font-weight: 750;
  letter-spacing: .12em;
}

.guide-intro h1 {
  margin: 4px 0 5px;
  color: var(--app-color-identity);
  font-family: var(--app-font-display);
  font-size: clamp(27px, 3vw, 38px);
  line-height: 1.2;
}

.guide-intro p,
.section-heading p {
  margin: 0;
  color: var(--app-text-muted);
  font-size: 14px;
}

.guide-principle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex: none;
  padding: 9px 12px;
  border: 1px solid #cfe6da;
  border-radius: 7px;
  color: #246c4f;
  background: var(--app-color-success-soft);
  font-size: 13px;
}

.repository-context {
  display: grid;
  grid-template-columns: 46px minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 14px;
  max-width: 1320px;
  margin: 0 auto 24px;
  padding: 15px 16px;
  border: 1px solid var(--app-border);
  border-left: 3px solid var(--app-color-action);
  border-radius: 8px;
  background: var(--app-surface);
}

.repository-icon,
.card-icon {
  display: grid;
  place-items: center;
  color: var(--app-color-action);
  background: var(--app-color-action-soft);
  border-radius: 7px;
}

.repository-icon {
  width: 44px;
  height: 44px;
}

.repository-copy {
  display: grid;
  min-width: 0;
}

.repository-copy span {
  color: var(--app-text-muted);
  font-size: 12px;
}

.repository-copy strong {
  overflow: hidden;
  color: var(--app-color-identity);
  font-size: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.repository-copy small {
  color: var(--app-text-subtle);
  font-family: var(--app-font-mono);
  font-size: 12px;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  width: fit-content;
  min-height: 25px;
  padding: 3px 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 650;
  white-space: nowrap;
}

.status-pill--success { color: #1d6f4d; background: var(--app-color-success-soft); }
.status-pill--warning { color: #95601a; background: var(--app-color-warning-soft); }
.status-pill--danger { color: #a13f38; background: var(--app-color-danger-soft); }
.status-pill--neutral { color: var(--app-text-muted); background: #edf2f5; }

.context-action,
.workflow-action,
.administration-card button,
.profile-warning button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 34px;
  padding: 0 11px;
  border: 1px solid var(--app-border-strong);
  border-radius: 6px;
  color: var(--app-color-identity);
  background: var(--app-surface);
  font-size: 13px;
  font-weight: 650;
}

.context-action:hover,
.workflow-action:hover,
.administration-card button:hover,
.profile-warning button:hover {
  border-color: var(--app-color-action);
  color: var(--app-color-action);
  background: var(--app-color-action-soft);
}

.profile-warning {
  display: flex;
  align-items: center;
  gap: 9px;
  max-width: 1320px;
  margin: -10px auto 20px;
  padding: 10px 12px;
  border: 1px solid #ebc8c4;
  border-radius: 7px;
  color: #8d3d37;
  background: var(--app-color-danger-soft);
  font-size: 13px;
}

.profile-warning span { flex: 1; }
.profile-warning button { min-height: 30px; border-color: #e3b7b3; color: #8d3d37; }

.guide-section {
  max-width: 1320px;
  margin: 0 auto;
}

.section-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 20px;
  margin: 0 0 12px;
}

.section-heading h2 {
  margin: 3px 0 0;
  color: var(--app-color-identity);
  font-family: var(--app-font-display);
  font-size: 19px;
}

.workflow-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.workflow-card {
  position: relative;
  display: grid;
  grid-template-rows: auto auto minmax(68px, 1fr) auto auto;
  gap: 11px;
  min-width: 0;
  padding: 17px;
  border: 1px solid var(--app-border);
  border-top: 3px solid var(--card-accent);
  border-radius: 8px;
  background: var(--app-surface);
  box-shadow: 0 4px 14px rgb(23 50 77 / 4%);
}

.workflow-card--action { --card-accent: var(--app-color-action); }
.workflow-card--evidence { --card-accent: var(--app-color-evidence); }
.workflow-card--model { --card-accent: var(--app-color-model); }
.workflow-card--warning { --card-accent: var(--app-color-warning); }
.workflow-card--success { --card-accent: var(--app-color-success); }

.workflow-card header {
  display: grid;
  grid-template-columns: auto 36px minmax(0, 1fr);
  align-items: center;
  gap: 9px;
}

.step-number {
  color: var(--card-accent);
  font-family: var(--app-font-mono);
  font-size: 12px;
  font-weight: 750;
}

.workflow-card .card-icon {
  width: 36px;
  height: 36px;
  color: var(--card-accent);
  background: color-mix(in srgb, var(--card-accent) 10%, white);
}

.workflow-card header .status-pill {
  justify-self: end;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
}

.workflow-card h3,
.administration-card h3 {
  margin: 0;
  color: var(--app-color-identity);
  font-size: 17px;
}

.workflow-card > p,
.administration-card p {
  margin: 0;
  color: var(--app-text-regular);
  font-size: 13px;
  line-height: 1.65;
}

.source-line {
  display: grid;
  gap: 3px;
  padding: 10px 0;
  border-top: 1px solid #e7edf1;
  border-bottom: 1px solid #e7edf1;
}

.source-line span {
  color: var(--app-text-subtle);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .08em;
}

.source-line strong {
  color: var(--app-text-muted);
  font-size: 12px;
  font-weight: 550;
  line-height: 1.5;
}

.workflow-action {
  width: 100%;
}

.administration {
  margin-top: 28px;
  padding-top: 22px;
  border-top: 1px solid var(--app-border);
}

.administration-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.administration-card {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: var(--app-surface);
}

.administration-card .card-icon {
  width: 38px;
  height: 38px;
}

.administration-card h3 { font-size: 15px; }
.administration-card p { margin-top: 3px; }
.administration-card small {
  display: block;
  margin-top: 7px;
  color: var(--app-text-subtle);
  font-size: 11px;
}

.administration-card small b {
  margin-right: 6px;
  color: var(--app-text-muted);
}

@media (max-width: 1120px) {
  .workflow-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .guide-principle { display: none; }
}

@media (max-width: 860px) {
  .feature-guide-page { padding: 20px 16px 30px; }
  .repository-context { grid-template-columns: 42px minmax(0, 1fr) auto; }
  .repository-context > .status-pill { grid-column: 2; }
  .context-action { grid-column: 3; grid-row: 1 / span 2; }
  .administration-grid { grid-template-columns: 1fr; }
  .section-heading { align-items: start; flex-direction: column; gap: 4px; }
}

@media (max-width: 620px) {
  .feature-guide-page { padding: 16px 4px 28px; }
  .workflow-grid { grid-template-columns: 1fr; }
  .repository-context { grid-template-columns: 38px minmax(0, 1fr); }
  .repository-icon { width: 38px; height: 38px; }
  .repository-context > .status-pill { grid-column: 2; }
  .context-action { grid-column: 1 / -1; grid-row: auto; width: 100%; }
  .administration-card { grid-template-columns: 36px minmax(0, 1fr); }
  .administration-card button { grid-column: 1 / -1; }
}

@media (prefers-reduced-motion: reduce) {
  .context-action,
  .workflow-action,
  .administration-card button {
    transition: none;
  }
}
</style>
