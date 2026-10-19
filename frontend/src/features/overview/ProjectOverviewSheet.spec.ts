import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import ProjectOverviewSheet from './ProjectOverviewSheet.vue';
import type {
  ProjectCodeFacts,
  ProjectHealthOverview,
  ProjectProfile,
  RepositoryPreparation,
} from '@/api/repositories';
import type { Repository } from '@/types/api';

const repository = {
  id: 'repo-1',
  name: '示例项目',
  description: '',
  branch: 'main',
  commit: '1234567890abcdef',
  contentVersion: 'contentVersion-1',
  dirty: false,
  contentVersionCreatedAt: new Date().toISOString(),
  capabilities: { canIndex: true, canUpdate: true },
} as Repository;

const profile = {
  fileCount: 40,
  totalBytes: 1_000,
  chunkCount: 80,
  vectorizedChunks: 80,
  missingChunks: 0,
  knowledgeCards: 2,
  retrievalCapability: 'SEMANTIC_EMBEDDING',
  retrievalCapabilityLabel: '语义检索',
  graphNodes: 120,
  graphEdges: 260,
  languages: [{ name: 'java', count: 30 }, { name: 'vue', count: 10 }],
  modules: [{ name: 'backend', count: 30 }, { name: 'frontend', count: 10 }],
  entryPoints: ['backend/src/main/Application.java'],
  assets: [{ name: 'CODE', count: 30 }],
  keyAssets: [{ path: 'frontend/package.json', assetType: 'CONFIG' }],
} satisfies ProjectProfile;

const preparation = {
  contentVersion: 'contentVersion-1',
  commitSha: '1234567890abcdef',
  branch: 'main',
  dirty: false,
  generatedAt: '2026-08-30T10:02:00Z',
  repositoryId: 'repo-1',
  state: 'READY',
  progress: 100,
  message: '项目已准备完成',
  stages: [
    { key: 'contentVersion', label: '代码内容版本', state: 'READY', detail: '40 个文件已发布' },
    { key: 'content', label: '内容索引', state: 'READY', detail: '80 个片段' },
    { key: 'vectors', label: '语义索引', state: 'READY', detail: '80 个向量' },
    { key: 'graph', label: '调用图谱', state: 'READY', detail: '120 个节点' },
    { key: 'knowledge_drift', label: '知识失效检查', state: 'READY', detail: '已核对' },
  ],
  profile,
  activeJobId: null,
  activeJobType: null,
  activeJobStatus: null,
} satisfies RepositoryPreparation;

const codeFacts = {
  contentVersion: 'contentVersion-1',
  commitSha: '1234567890abcdef',
  generatedAt: new Date().toISOString(),
  projectType: '前后端分离 Web 应用',
  confidence: 100,
  codeFileCount: 30,
  codeTypes: [
    { name: 'Java', count: 20 },
    { name: 'Vue SFC', count: 10 },
  ],
  technologies: [
    {
      name: 'Spring Boot',
      category: 'FRAMEWORK',
      confidence: 'HIGH',
      detail: '构建清单声明 spring-boot',
      evidencePaths: ['backend/pom.xml'],
    },
    {
      name: 'Vue',
      category: 'FRAMEWORK',
      confidence: 'HIGH',
      detail: '依赖清单声明 vue',
      evidencePaths: ['frontend/package.json'],
    },
  ],
  fileCategories: [
    {
      key: 'SERVICE',
      label: '应用与服务',
      detail: '用例编排和业务服务',
      count: 18,
      samples: ['backend/src/UserService.java'],
    },
    {
      key: 'TEST',
      label: '测试代码',
      detail: '自动化验证',
      count: 6,
      samples: ['backend/src/UserServiceTest.java'],
    },
  ],
  graph: {
    codeGraphReady: true,
    codeGraphVersion: '1.0',
    symbolNodes: 120,
    symbolEdges: 260,
    modules: 1,
    dependencyEdges: 6,
    runtimeEdges: 1,
    hotspots: [],
    analyzedCodeFiles: 30,
    totalCodeFiles: 30,
    partial: false,
  },
  suggestions: [],
  evidenceNotes: [],
} satisfies ProjectCodeFacts;

const health = {
  repositoryId: 'repo-1',
  contentVersion: 'contentVersion-1',
  commitSha: '1234567890abcdef',
  state: 'DEGRADED',
  readyForSearch: true,
  knowledge: {
    total: 7,
    current: 4,
    suspect: 1,
    stale: 1,
    unverified: 1,
    trusted: 3,
    requiredWithoutOwner: 1,
    unreviewed: 2,
  },
  issues: [{
    code: 'REQUIRED_KNOWLEDGE_WITHOUT_OWNER',
    severity: 'WARNING',
    title: '必需知识缺少负责人',
    detail: '1 条 REQUIRED 知识无法明确审批责任。',
    actionTarget: 'KNOWLEDGE',
  }],
  generatedAt: '2026-08-30T10:02:00Z',
} satisfies ProjectHealthOverview;

function mountSheet(currentPreparation: RepositoryPreparation = preparation) {
  return mount(ProjectOverviewSheet, {
    props: {
      repository,
      preparation: currentPreparation,
      profile,
      codeFacts,
      health,
      loading: false,
      preparing: false,
    },
  });
}

describe('ProjectOverviewSheet', () => {
  it('shows contentVersion facts, knowledge health and code categories without unrelated review data', () => {
    const wrapper = mountSheet();
    const text = wrapper.text();

    expect(text).toContain('示例项目');
    expect(text).toContain('检索可用，部分能力降级');
    expect(text).toContain('1234567890');
    expect(text).toContain('内容版本 contentV');
    expect(text).toContain('代码图谱');
    expect(text).toContain('120');
    expect(text).toContain('100%');
    expect(text).toContain('知识库状态');
    expect(text).toContain('当前');
    expect(text).toContain('缺少维护人');
    expect(text).toContain('代码类型与系统结构');
    expect(text).toContain('Java');
    expect(text).toContain('前后端分离 Web 应用');
    expect(text).toContain('当前问题');
    expect(text).not.toContain('技术栈');
    expect(text).not.toContain('Spring Boot');
    expect(text).not.toContain('README 原文');
  });

  it('emits the primary search action and routes knowledge issue handling through explicit events', async () => {
    const wrapper = mountSheet();
    await wrapper.get('.review-action').trigger('click');
    await wrapper.get('.issue-row button').trigger('click');

    expect(wrapper.emitted('openSearch')).toHaveLength(1);
    expect(wrapper.emitted('openKnowledge')).toEqual([['REQUIRED_KNOWLEDGE_WITHOUT_OWNER']]);
  });

  it('routes degraded preparation stages to project management', async () => {
    const degraded = {
      ...preparation,
      state: 'DEGRADED',
      stages: preparation.stages.map(stage => (
        stage.key === 'vectors' ? { ...stage, state: 'DEGRADED' as const } : stage
      )),
    } satisfies RepositoryPreparation;
    const wrapper = mountSheet(degraded);

    await wrapper.get('.stage-title button').trigger('click');

    expect(wrapper.emitted('openManagement')).toHaveLength(1);
  });
});

it('shows unavailable statistics instead of reporting zero code files on a failed request', async () => {
  const wrapper = mountSheet();
  await wrapper.setProps({ codeFacts: null });
  expect(wrapper.text()).toContain('项目结构未能加载，请刷新重试。');
  expect(wrapper.find('[data-accent="violet"] strong').text()).toBe('—');
});

it('never presents missing responses as healthy or as zero measurements', async () => {
  const wrapper = mountSheet();
  await wrapper.setProps({ profile: null, health: null, preparation: null, codeFacts: null });
  expect(wrapper.text()).toContain('状态尚未获取');
  expect(wrapper.text()).toContain('无法判断是否存在缺口');
  expect(wrapper.text()).not.toContain('未发现缺口');
  expect(wrapper.text()).not.toContain('变更审查');
  expect(wrapper.findAll('.capability-strip strong').map(item => item.text())).toEqual(['—', '—', '—', '—']);
});

it('keeps incomplete vector coverage below 100% and names character retrieval', async () => {
  const wrapper = mountSheet();
  await wrapper.setProps({ profile: { ...profile, chunkCount: 10000, vectorizedChunks: 9999, missingChunks: 1,
    retrievalCapability: 'CHARACTER_HASH', retrievalCapabilityLabel: '字符相似度' } });
  expect(wrapper.get('[data-accent="cyan"] strong').text()).toBe('99.9%');
  expect(wrapper.text()).toContain('不具备语义理解能力');
  await wrapper.setProps({ profile: { ...profile, chunkCount: 0, vectorizedChunks: 0 } });
  expect(wrapper.get('[data-accent="cyan"] strong').text()).toBe('—');
});

it('shows source language types with their share of code files', async () => {
  const wrapper = mountSheet();
  expect(wrapper.findAll('[data-testid="code-type-row"]')).toHaveLength(2);
  expect(wrapper.get('[role="progressbar"]').attributes('aria-valuenow')).toContain('66.666');
  expect(wrapper.text()).toContain('66.7%');
  expect(wrapper.get('[data-accent="violet"]').text()).toContain('2 种源码类型');
});

it('summarizes system layers and omits low-value sample paths', async () => {
  const wrapper = mountSheet();
  expect(wrapper.text()).toContain('系统结构');
  expect(wrapper.text()).toContain('应用逻辑');
  expect(wrapper.text()).toContain('主要模块');
  expect(wrapper.text()).toContain('backend');
  expect(wrapper.text()).not.toContain('backend/src/UserService.java');
  expect(wrapper.text()).not.toContain('应用与服务');
  expect(wrapper.text()).not.toContain('来源版本标记为 CURRENT');
  expect(wrapper.text()).not.toContain('覆盖率只衡量向量齐备程度');
});


it('keeps completed history out of the overview and places live work inside branch status', async () => {
  const wrapper = mountSheet();
  const job = { id: 'job', branchId: 'branch', kind: 'PREPARE' as const, stage: 'COMPLETED', status: 'SUCCEEDED' as const, error: null, contentVersion: preparation.contentVersion };
  await wrapper.setProps({ jobs: [job] });
  expect(wrapper.find('[aria-label="当前分支任务进度"]').exists()).toBe(false);
  expect(wrapper.get('.readiness-section').text()).toContain('当前已发布版本的可用状态');
  await wrapper.setProps({ jobs: [{ ...job, status: 'RUNNING', stage: 'INDEXING' }], preparing: true });
  expect(wrapper.get('.readiness-section [aria-label="当前分支任务进度"]').text()).toContain('执行中 · 构建内容索引');
  expect(wrapper.get('.health-callout').text()).toContain('正在准备');
  expect(wrapper.get('.availability-summary').text()).not.toContain('可开始联合检索');
  expect(wrapper.find('.preparation-track').exists()).toBe(false);
  expect(wrapper.find('.project-hero + .preparation-progress').exists()).toBe(false);
  wrapper.unmount();
});

it('holds back old readiness until completion refresh succeeds and preserves refresh retry', async () => {
  const wrapper = mountSheet();
  await wrapper.setProps({ refreshPending: true, taskError: '读取暂不可用' });
  expect(wrapper.get('.health-callout').text()).toContain('状态待刷新');
  expect(wrapper.find('.preparation-track').exists()).toBe(false);
  expect(wrapper.get('.review-action').attributes('disabled')).toBeDefined();
  const status = wrapper.get('.readiness-section');
  expect(status.text()).toContain('重试刷新页面状态');
  status.findComponent({ name: 'OverviewPreparationProgress' }).vm.$emit('refresh');
  expect(wrapper.emitted('refreshTasks')).toHaveLength(1);
  await wrapper.setProps({ refreshPending: false, taskError: '' });
  expect(wrapper.find('.preparation-track').exists()).toBe(true);
  expect(wrapper.find('[aria-label="当前分支任务进度"]').exists()).toBe(false);
  wrapper.unmount();
});

it('hides old failures but keeps failures observed during this preparation and retries the same kind', async () => {
  const wrapper = mountSheet();
  const job = { id: 'job', branchId: 'branch', kind: 'GRAPH' as const, stage: 'GRAPH', status: 'FAILED' as const, error: '图谱失败', contentVersion: preparation.contentVersion };
  await wrapper.setProps({ jobs: [job] });
  expect(wrapper.find('[aria-label="当前分支任务进度"]').exists()).toBe(false);
  await wrapper.setProps({ jobs: [{ ...job, status: 'RUNNING' }], preparing: true });
  await wrapper.setProps({ jobs: [job], preparing: false });
  const progress = wrapper.getComponent({ name: 'OverviewPreparationProgress' });
  expect(progress.text()).toContain('本次任务失败');
  progress.vm.$emit('retry', 'GRAPH');
  expect(wrapper.emitted('retryTask')).toEqual([['GRAPH']]);
  await wrapper.setProps({ preparation: { ...preparation, contentVersion: 'new-version' } });
  expect(wrapper.find('[aria-label="当前分支任务进度"]').exists()).toBe(false);
  wrapper.unmount();
});
