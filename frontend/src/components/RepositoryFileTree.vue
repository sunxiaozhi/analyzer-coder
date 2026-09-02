<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { Document, Folder, FolderOpened, Search } from '@element-plus/icons-vue';
import { ElTree } from 'element-plus';
import type { RepositoryFileEntry } from '@/types/api';

interface TreeNode {
  id: string;
  label: string;
  path: string;
  type: 'directory' | 'file';
  file?: RepositoryFileEntry;
  children?: TreeNode[];
}

const props = defineProps<{
  files: RepositoryFileEntry[];
  selectedPath: string | null;
  loading: boolean;
}>();
const emit = defineEmits<{ select: [path: string] }>();
const filter = ref('');
const tree = ref<InstanceType<typeof ElTree>>();

const treeData = computed(() => buildTree(props.files));

function buildTree(files: RepositoryFileEntry[]): TreeNode[] {
  const roots: TreeNode[] = [];
  const directories = new Map<string, TreeNode>();
  for (const file of files) {
    const parts = file.path.split('/');
    let children = roots;
    let currentPath = '';
    for (let index = 0; index < parts.length - 1; index++) {
      currentPath = currentPath ? `${currentPath}/${parts[index]}` : parts[index];
      let directory = directories.get(currentPath);
      if (!directory) {
        directory = {
          id: `directory:${currentPath}`,
          label: parts[index],
          path: currentPath,
          type: 'directory',
          children: [],
        };
        directories.set(currentPath, directory);
        children.push(directory);
      }
      children = directory.children!;
    }
    children.push({
      id: file.path,
      label: file.name,
      path: file.path,
      type: 'file',
      file,
    });
  }
  sortNodes(roots);
  return roots;
}

function sortNodes(nodes: TreeNode[]) {
  nodes.sort((left, right) =>
    left.type === right.type
      ? left.label.localeCompare(right.label, 'zh-CN', { numeric: true, sensitivity: 'base' })
      : left.type === 'directory' ? -1 : 1,
  );
  nodes.forEach(node => node.children && sortNodes(node.children));
}

function filterNode(value: string, data: Record<string, unknown>) {
  return !value || String(data.path ?? '').toLocaleLowerCase().includes(value.toLocaleLowerCase());
}

function selectNode(data: TreeNode) {
  if (data.type === 'file') emit('select', data.path);
}

async function revealSelected(path: string | null) {
  if (!path) return;
  await nextTick();
  const instance = tree.value;
  if (!instance) return;
  const parts = path.split('/');
  let currentPath = '';
  for (let index = 0; index < parts.length - 1; index++) {
    currentPath = currentPath ? `${currentPath}/${parts[index]}` : parts[index];
    const node = instance.getNode(`directory:${currentPath}`);
    if (node) node.expanded = true;
  }
  instance.setCurrentKey(path);
}

watch(filter, value => tree.value?.filter(value));
watch(() => props.selectedPath, revealSelected, { immediate: true });
watch(treeData, () => revealSelected(props.selectedPath));
</script>

<template>
  <aside class="repository-tree-pane">
    <header class="tree-pane-head">
      <div>
        <b>文件目录</b>
        <span>{{ files.length }} 个文件</span>
      </div>
      <el-input v-model="filter" :prefix-icon="Search" clearable placeholder="筛选文件路径" />
    </header>
    <div v-loading="loading" class="repository-tree-scroll">
      <el-empty v-if="!loading && !files.length" :image-size="54" description="当前代码版本没有文件" />
      <el-tree
        v-else
        ref="tree"
        :data="treeData"
        node-key="id"
        :filter-node-method="filterNode"
        :expand-on-click-node="false"
        :highlight-current="true"
        :indent="14"
        @node-click="selectNode"
      >
        <template #default="{ node, data }">
          <span class="tree-node" :title="data.path">
            <el-icon class="tree-node-icon">
              <FolderOpened v-if="data.type === 'directory' && node.expanded" />
              <Folder v-else-if="data.type === 'directory'" />
              <Document v-else />
            </el-icon>
            <span>{{ data.label }}</span>
          </span>
        </template>
      </el-tree>
    </div>
  </aside>
</template>

<style scoped>
.repository-tree-pane {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: #fff;
  border: 1px solid #dedee3;
  border-radius: 7px 0 0 7px;
}

.tree-pane-head {
  display: grid;
  gap: 10px;
  padding: 12px;
  border-bottom: 1px solid #ececef;
}

.tree-pane-head > div {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.tree-pane-head b {
  color: #303036;
  font-size: 15px;
}

.tree-pane-head span {
  color: var(--app-text-muted);
  font-size: 13px;
}

.repository-tree-scroll {
  min-height: 0;
  padding: 8px 0 14px;
  overflow: auto;
  overscroll-behavior: contain;
}

.repository-tree-scroll :deep(.el-tree) {
  min-width: max-content;
  color: #3d3d43;
  font-size: 14px;
}

.repository-tree-scroll :deep(.el-tree-node__content) {
  height: 30px;
  padding-right: 12px;
}

.repository-tree-scroll :deep(.el-tree-node__content:hover) {
  background: #f5f7fa;
}

.repository-tree-scroll :deep(.el-tree-node.is-current > .el-tree-node__content) {
  color: #005eb8;
  background: var(--app-color-action-soft);
}

.tree-node {
  display: inline-flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.tree-node-icon {
  flex: none;
  color: var(--app-text-muted);
}
</style>
