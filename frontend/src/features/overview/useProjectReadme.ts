import { computed, shallowReadonly, shallowRef, toValue, watch, type MaybeRefOrGetter } from 'vue';
import { getRepositoryFile } from '@/api/repositories';
import type {
  RepositoryFileContent,
  RepositoryFileEntry,
  RepositorySnapshotFiles,
} from '@/types/api';

export type ProjectDocumentCategory =
  | 'overview'
  | 'getting-started'
  | 'architecture'
  | 'development'
  | 'operations'
  | 'rules'
  | 'other';

export interface ProjectDocument {
  path: string;
  name: string;
  title: string;
  category: ProjectDocumentCategory;
  categoryLabel: string;
  priority: number;
}

const categoryLabels: Record<ProjectDocumentCategory, string> = {
  overview: '项目介绍',
  'getting-started': '开始使用',
  architecture: '架构设计',
  development: '开发指南',
  operations: '部署运维',
  rules: '协作规范',
  other: '其他文档',
};

function normalized(path: string) {
  return path.replace(/\\/g, '/').toLowerCase();
}

function pathDepth(path: string) {
  return path.split('/').length - 1;
}

function documentCategory(path: string): ProjectDocumentCategory {
  const value = normalized(path);
  const name = value.split('/').pop() ?? value;
  if (/^readme(?:[._-][a-z-]+)?\.(?:md|mdx|markdown)$/.test(name)) return 'overview';
  if (/(quick[-_ ]?start|getting[-_ ]?started|install|usage|使用|安装|入门|开始)/.test(value)) return 'getting-started';
  if (/(architecture|design|adr|架构|设计)/.test(value)) return 'architecture';
  if (/(develop|contribut|api|开发|贡献|接口)/.test(value)) return 'development';
  if (/(deploy|docker|operation|release|部署|运维|发布)/.test(value)) return 'operations';
  if (/(agents\.md|claude\.md|codex\.md|rules?|guideline|规范|约束)/.test(value)) return 'rules';
  return 'other';
}

function documentPriority(file: RepositoryFileEntry, category: ProjectDocumentCategory) {
  const value = normalized(file.path);
  const name = value.split('/').pop() ?? value;
  let score = category === 'overview' ? 500 : 100;
  if (name === 'readme.md') score += 500;
  if (name === 'readme.zh-cn.md' || name === 'readme.zh.md') score += 460;
  if (name.startsWith('readme')) score += 300;
  score -= pathDepth(value) * 25;
  return score;
}

function displayTitle(path: string) {
  const name = path.split('/').pop() ?? path;
  return name
    .replace(/\.(?:md|mdx|markdown)$/i, '')
    .replace(/[-_]+/g, ' ')
    .replace(/^readme(?:\.[a-z-]+)?$/i, 'README');
}

export function discoverProjectDocuments(files: RepositoryFileEntry[]): ProjectDocument[] {
  return files
    .filter(file => /\.(?:md|mdx|markdown)$/i.test(file.path))
    .map(file => {
      const category = documentCategory(file.path);
      return {
        path: file.path,
        name: file.name,
        title: displayTitle(file.path),
        category,
        categoryLabel: categoryLabels[category],
        priority: documentPriority(file, category),
      };
    })
    .sort((left, right) => right.priority - left.priority || left.path.localeCompare(right.path));
}

export function markdownTitle(content: string) {
  const match = content.match(/^#\s+(.+)$/m);
  return match?.[1]
    ?.replace(/\[([^\]]+)]\([^)]*\)/g, '$1')
    .replace(/[*_`]/g, '')
    .trim() ?? '';
}

export function markdownSummary(content: string) {
  const cleaned = content
    .replace(/^---[\s\S]*?---\s*/m, '')
    .replace(/```[\s\S]*?```/g, '')
    .replace(/^#{1,6}\s+.*$/gm, '')
    .replace(/^\s*(?:!\[[^\]]*]\([^)]*\)|<[^>]+>)\s*$/gm, '')
    .replace(/\[([^\]]+)]\([^)]*\)/g, '$1')
    .replace(/[*_`>#-]/g, '')
    .split(/\n\s*\n/)
    .map(paragraph => paragraph.replace(/\s+/g, ' ').trim())
    .find(paragraph => paragraph.length >= 24);
  return cleaned?.slice(0, 220) ?? '';
}

export function markdownCommands(content: string) {
  const commands: string[] = [];
  const blocks = content.matchAll(/```(?:bash|sh|shell|console|powershell|cmd)?\s*\n([\s\S]*?)```/gi);
  for (const block of blocks) {
    for (const rawLine of block[1].split('\n')) {
      const line = rawLine.trim().replace(/^[$>]\s*/, '');
      if (!line || line.startsWith('#') || line.length > 140) continue;
      if (/^(?:npm|pnpm|yarn|bun|mvnw?|gradlew?|docker|docker-compose|compose|make|go|cargo|python|pip|uv|java|dotnet|\.\/)/i.test(line)) {
        commands.push(line);
      }
      if (commands.length >= 6) return [...new Set(commands)];
    }
  }
  return [...new Set(commands)];
}

export function useProjectReadme(
  repositoryId: MaybeRefOrGetter<string | null>,
  snapshot: MaybeRefOrGetter<RepositorySnapshotFiles | null>,
) {
  const selectedPath = shallowRef<string | null>(null);
  const selectedFile = shallowRef<RepositoryFileContent | null>(null);
  const primaryFile = shallowRef<RepositoryFileContent | null>(null);
  const loading = shallowRef(false);
  const error = shallowRef<string | null>(null);
  const cache = new Map<string, RepositoryFileContent>();
  let requestVersion = 0;

  const repositoryIdValue = computed(() => toValue(repositoryId));
  const snapshotValue = computed(() => toValue(snapshot));
  const documents = computed(() => discoverProjectDocuments(snapshotValue.value?.files ?? []));
  const primaryDocument = computed(() => documents.value[0] ?? null);
  const projectTitle = computed(() => markdownTitle(primaryFile.value?.content ?? ''));
  const projectSummary = computed(() => markdownSummary(primaryFile.value?.content ?? ''));
  const commands = computed(() => markdownCommands(primaryFile.value?.content ?? ''));

  async function read(path: string, version: number) {
    const id = repositoryIdValue.value;
    const snapshotId = snapshotValue.value?.snapshotId;
    if (!id || !snapshotId) return null;
    const key = `${snapshotId}:${path}`;
    const cached = cache.get(key);
    if (cached) return cached;
    const file = await getRepositoryFile(id, path);
    if (version !== requestVersion) return null;
    cache.set(key, file);
    return file;
  }

  async function openDocument(path: string) {
    if (!documents.value.some(document => document.path === path)) return false;
    const version = ++requestVersion;
    selectedPath.value = path;
    selectedFile.value = null;
    error.value = null;
    loading.value = true;
    try {
      const file = await read(path, version);
      if (version === requestVersion) selectedFile.value = file;
      return Boolean(file);
    } catch (exception) {
      if (version === requestVersion) {
        error.value = exception instanceof Error ? exception.message : '项目文档加载失败';
      }
      return false;
    } finally {
      if (version === requestVersion) loading.value = false;
    }
  }

  watch(
    () => [repositoryIdValue.value, snapshotValue.value?.snapshotId, primaryDocument.value?.path] as const,
    async ([id, snapshotId, path]) => {
      requestVersion++;
      selectedPath.value = null;
      selectedFile.value = null;
      primaryFile.value = null;
      error.value = null;
      cache.clear();
      if (!id || !snapshotId || !path) return;
      const version = requestVersion;
      loading.value = true;
      try {
        const file = await read(path, version);
        if (version !== requestVersion) return;
        primaryFile.value = file;
        selectedFile.value = file;
        selectedPath.value = path;
      } catch (exception) {
        if (version === requestVersion) {
          error.value = exception instanceof Error ? exception.message : 'README 加载失败';
        }
      } finally {
        if (version === requestVersion) loading.value = false;
      }
    },
    { immediate: true },
  );

  return {
    documents,
    primaryDocument,
    selectedPath: shallowReadonly(selectedPath),
    selectedFile: shallowReadonly(selectedFile),
    primaryFile: shallowReadonly(primaryFile),
    projectTitle,
    projectSummary,
    commands,
    loading: shallowReadonly(loading),
    error: shallowReadonly(error),
    openDocument,
  };
}
