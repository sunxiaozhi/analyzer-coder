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
  snapshotId: 'snapshot-1',
  dirty: false,
  snapshotCreatedAt: new Date().toISOString(),
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
  snapshotId: 'snapshot-1',
  commitSha: '1234567890abcdef',
  branch: 'main',
  dirty: false,
  generatedAt: '2026-08-30T10:02:00Z',
  repositoryId: 'repo-1',
  state: 'READY',
  progress: 100,
  message: '项目已准备完成',
  stages: [
    { key: 'snapshot', label: '代码快照', state: 'READY', detail: '40 个文件已发布' },
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
  snapshotId: 'snapshot-1',
  commitSha: '1234567890abcdef',
  generatedAt: new Date().toISOString(),
  projectType: '前后端分离 Web 应用',
  confidence: 100,
  codeFileCount: 30,
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
  snapshotId: 'snapshot-1',
  commitSha: '1234567890abcdef',
  state: 'DEGRADED',
  readyForReview: true,
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
  recentReviews: [{
    reviewId: 'review-1',
    status: 'COMPLETED',
    repositoryId: 'repo-1',
    snapshotId: 'snapshot-1',
    createdBy: 'account-1',
    clientRequestId: 'request-1',
    task: '调整登录校验',
    changeSource: 'WORKTREE',
    changedFileCount: 3,
    changedSymbolCount: 5,
    applicableKnowledgeCount: 2,
    requiredTestCount: 1,
    requiredApprovalCount: 0,
    staleKnowledgeCount: 0,
    unknownCount: 0,
    error: null,
    createdAt: '2026-08-30T10:00:00Z',
    finishedAt: '2026-08-30T10:01:00Z',
  }],
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
  it('shows snapshot facts, knowledge health, code categories and recent reviews without README or technologies', () => {
    const wrapper = mountSheet();
    const text = wrapper.text();

    expect(text).toContain('示例项目');
    expect(text).toContain('准备或治理存在缺口');
    expect(text).toContain('1234567890');
    expect(text).toContain('快照 snapshot');
    expect(text).toContain('代码图谱');
    expect(text).toContain('120');
    expect(text).toContain('100%');
    expect(text).toContain('知识治理状态');
    expect(text).toContain('当前');
    expect(text).toContain('必需但无负责人');
    expect(text).toContain('代码类型统计');
    expect(text).toContain('应用与服务');
    expect(text).toContain('最近变更审查');
    expect(text).toContain('调整登录校验');
    expect(text).toContain('当前阻塞与缺口');
    expect(text).not.toContain('技术栈');
    expect(text).not.toContain('Spring Boot');
    expect(text).not.toContain('README 原文');
  });

  it('emits the primary review action and routes knowledge issue handling through explicit events', async () => {
    const wrapper = mountSheet();
    await wrapper.get('.review-action').trigger('click');
    await wrapper.get('.issue-row button').trigger('click');

    expect(wrapper.emitted('startReview')).toHaveLength(1);
    expect(wrapper.emitted('openKnowledge')).toHaveLength(1);
  });

  it('offers a retry action on the exact degraded preparation stage', async () => {
    const degraded = {
      ...preparation,
      state: 'DEGRADED',
      stages: preparation.stages.map(stage => (
        stage.key === 'vectors' ? { ...stage, state: 'DEGRADED' as const } : stage
      )),
    } satisfies RepositoryPreparation;
    const wrapper = mountSheet(degraded);

    await wrapper.get('.stage-title button').trigger('click');

    expect(wrapper.emitted('retryStage')).toEqual([['vectors']]);
  });
});

it('shows unavailable statistics instead of reporting zero code files on a failed request', async () => {
  const wrapper = mountSheet();
  await wrapper.setProps({ codeFacts: null });
  expect(wrapper.text()).toContain('代码统计未能加载，请刷新重试。');
  expect(wrapper.find('[data-accent="violet"] strong').text()).toBe('—');
});

it('never presents missing responses as healthy or as zero measurements', async () => {
  const wrapper = mountSheet();
  await wrapper.setProps({ profile: null, health: null, preparation: null, codeFacts: null });
  expect(wrapper.text()).toContain('状态尚未获取');
  expect(wrapper.text()).toContain('无法判断是否存在缺口');
  expect(wrapper.text()).not.toContain('未发现缺口');
  expect(wrapper.text()).not.toContain('还没有变更审查记录');
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

it('shows all categories with their share of source files', async () => {
  const wrapper = mountSheet();
  const fileCategories = Array.from({ length: 10 }, (_, index) => ({ key: String(index), label: `类别${index}`, count: 1, detail: '', samples: [] }));
  await wrapper.setProps({ codeFacts: { ...codeFacts, codeFileCount: 10, fileCategories } });
  expect(wrapper.findAll('.category-row')).toHaveLength(10);
  expect(wrapper.get('.category-row b').attributes('style')).toContain('width: 10%');
  expect(wrapper.get('[data-accent="violet"]').text()).toContain('10 类');
});

it('shows failed review errors and opens the exact historical record', async () => {
  const wrapper = mountSheet();
  await wrapper.setProps({ health: { ...health, recentReviews: [{ ...health.recentReviews[0], status: 'FAILED', snapshotId: 'old-snapshot',
    changedFileCount: null, changedSymbolCount: null, applicableKnowledgeCount: null,
    error: { code: 'GIT_REF_NOT_FOUND', message: '目标提交不存在' } }] } });
  const row = wrapper.get('.review-row');
  expect(row.text()).toContain('未生成有效统计');
  expect(row.text()).toContain('历史快照');
  expect(row.text()).toContain('目标提交不存在');
  expect(row.text()).not.toContain('0 文件');
  await row.get('button').trigger('click');
  expect(wrapper.emitted('openReview')).toEqual([['review-1']]);
});
