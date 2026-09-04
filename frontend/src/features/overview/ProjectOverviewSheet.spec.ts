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
    expect(text).toContain('可用但有缺口');
    expect(text).toContain('1234567890');
    expect(text).toContain('快照 snapshot');
    expect(text).toContain('代码图谱');
    expect(text).toContain('120');
    expect(text).toContain('100%');
    expect(text).toContain('知识真实性');
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
