import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import ProjectOverviewSheet from './ProjectOverviewSheet.vue';
import type { ProjectCodeFacts, ProjectProfile, RepositoryPreparation } from '@/api/repositories';
import type { Repository, RepositoryFileContent } from '@/types/api';

const repository = {
  id: 'repo-1',
  name: '示例项目',
  description: '',
  branch: 'main',
  dirty: false,
  snapshotCreatedAt: new Date().toISOString(),
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

const readmeFile = {
  snapshotId: 'snapshot-1',
  path: 'README.md',
  name: 'README.md',
  language: 'Markdown',
  sizeBytes: 120,
  lineCount: 8,
  content: [
    '# 项目介绍',
    '',
    '这是项目 README 原文。',
    '',
    '## 核心能力',
    '',
    '- 代码检索',
  ].join('\n'),
} satisfies RepositoryFileContent;

function mountSheet() {
  return mount(ProjectOverviewSheet, {
    props: {
      repository,
      preparation,
      profile,
      codeFacts,
      readmeFile,
      readmeLoading: false,
      readmeError: null,
      loading: false,
      preparing: false,
    },
  });
}

describe('ProjectOverviewSheet', () => {
  it('shows project data beside the README introduction without document tabs', () => {
    const wrapper = mountSheet();
    const text = wrapper.text();

    expect(text).toContain('示例项目');
    expect(text).toContain('已准备');
    expect(text).toContain('CodeGraph');
    expect(text).toContain('120');
    expect(text).toContain('80 / 80');
    expect(text).toContain('Spring Boot');
    expect(text).toContain('项目介绍');
    expect(text).toContain('这是项目 README 原文。');
    expect(text).toContain('核心能力');
    expect(text).toContain('语言构成');
    expect(wrapper.find('.document-tabs').exists()).toBe(false);
    expect(text).not.toContain('代码类型');
    expect(text).not.toContain('项目结构');
  });

  it('opens a technology evidence file', async () => {
    const wrapper = mountSheet();
    await wrapper.get('.technology-list button').trigger('click');
    expect(wrapper.emitted('openFile')).toEqual([['backend/pom.xml']]);
  });

  it('opens the README source from the single-document reader', async () => {
    const wrapper = mountSheet();
    await wrapper.get('.source-action').trigger('click');
    expect(wrapper.emitted('openFile')).toEqual([['README.md']]);
  });
});