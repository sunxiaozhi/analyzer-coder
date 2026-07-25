<script setup lang="ts">
import { Link, Plus, Search, View } from '@element-plus/icons-vue';
import { computed, shallowRef, watch } from 'vue';
import { ElMessage } from 'element-plus';
import type { CodeReference } from '@/api/intelligence';
import { listChunks } from '@/api/repositories';
import type { CodeChunk } from '@/types/api';

const props = defineProps<{ repositoryId: string }>();
const emit = defineEmits<{
  openCode: [reference: CodeReference];
}>();
const references = defineModel<CodeReference[]>({ required: true });

const query = shallowRef('');
const results = shallowRef<CodeChunk[]>([]);
const total = shallowRef(0);
const loading = shallowRef(false);
const searched = shallowRef(false);
const selectedChunkIds = computed(() => new Set(references.value.map(item => item.chunkId).filter(Boolean)));

watch(() => props.repositoryId, () => {
  query.value = '';
  results.value = [];
  total.value = 0;
  searched.value = false;
});

async function search() {
  const keyword = query.value.trim();
  if (!keyword) {
    results.value = [];
    total.value = 0;
    searched.value = false;
    ElMessage.info('请输入文件路径、类名、方法名或代码关键词');
    return;
  }
  loading.value = true;
  searched.value = true;
  try {
    const response = await listChunks(props.repositoryId, { q: keyword, limit: 20 });
    results.value = response.chunks;
    total.value = response.total;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '代码检索失败');
  } finally {
    loading.value = false;
  }
}

function clearSearch() {
  if (query.value) return;
  results.value = [];
  total.value = 0;
  searched.value = false;
}

function add(chunk: CodeChunk) {
  if (selectedChunkIds.value.has(chunk.id)) return;
  if (references.value.length >= 30) {
    ElMessage.warning('每张知识卡片最多关联 30 处代码');
    return;
  }
  references.value = [...references.value, {
    chunkId: chunk.id,
    snapshotId: chunk.snapshotId,
    filePath: chunk.filePath,
    symbolName: chunk.symbolName,
    startLine: chunk.startLine,
    endLine: chunk.endLine,
    contentHash: chunk.contentHash,
    stale: false,
  }];
}

function remove(chunkId: string | null) {
  references.value = references.value.filter(item => item.chunkId !== chunkId);
}

function excerpt(content: string) {
  return content.replace(/\s+/g, ' ').trim().slice(0, 150);
}
</script>

<template>
  <section class="reference-selector">
    <header class="reference-intro">
      <div class="reference-intro-icon"><Link /></div>
      <div>
        <b>把知识定位到具体实现</b>
        <p>搜索当前仓库的代码片段，选择后可从知识卡片直接打开源码或调用图谱。</p>
      </div>
      <span>{{ references.length }}/30 已关联</span>
    </header>

    <div class="search-guide">
      <div class="search-guide-label">怎么搜索</div>
      <p>
        输入一段明确的文本，系统按“包含”匹配：
        <strong>文件路径</strong>、<strong>类或方法名</strong>、<strong>语言</strong>、<strong>代码原文</strong>。
      </p>
      <div class="search-examples">
        <span>示例</span>
        <code>LoginController</code>
        <code>login</code>
        <code>src/main/java</code>
        <code>Java</code>
      </div>
    </div>

    <div class="reference-search">
      <el-input
        v-model="query"
        :prefix-icon="Search"
        clearable
        placeholder="例如：LoginController、login 或 src/main/java"
        @clear="clearSearch"
        @keyup.enter="search"
      />
      <el-button type="primary" :icon="Search" :loading="loading" @click="search">搜索代码</el-button>
    </div>

    <section v-if="references.length" class="reference-group">
      <div class="group-heading">
        <b>已关联代码</b>
        <span>保存卡片后，这些代码会随当前修订一起记录</span>
      </div>
      <div class="selected-references">
        <article v-for="reference in references" :key="reference.chunkId ?? reference.filePath">
          <div class="code-rail" aria-hidden="true"></div>
          <div class="reference-copy">
            <b>{{ reference.symbolName || reference.filePath.split('/').pop() }}</b>
            <span class="mono">{{ reference.filePath }}</span>
            <small>L{{ reference.startLine ?? '?' }}–{{ reference.endLine ?? '?' }}</small>
          </div>
          <el-tag v-if="reference.stale" type="warning" size="small">代码已变化</el-tag>
          <div class="reference-actions">
            <el-button link :icon="View" @click="emit('openCode', reference)">查看源码</el-button>
            <el-button link type="danger" @click="remove(reference.chunkId)">移除</el-button>
          </div>
        </article>
      </div>
    </section>

    <section v-if="searched" class="reference-group search-results-group">
      <div class="group-heading">
        <b>搜索结果</b>
        <span v-if="total">共 {{ total }} 处匹配，显示前 {{ results.length }} 处</span>
        <span v-else>没有找到匹配代码</span>
      </div>

      <div v-if="results.length" class="reference-results">
        <article v-for="chunk in results" :key="chunk.id">
          <div class="result-main">
            <div class="result-title">
              <b>{{ chunk.symbolName || chunk.filePath.split('/').pop() }}</b>
              <span v-if="chunk.symbolKind">{{ chunk.symbolKind }}</span>
              <span v-if="chunk.language">{{ chunk.language }}</span>
            </div>
            <p class="mono">{{ chunk.filePath }} · L{{ chunk.startLine ?? '?' }}–{{ chunk.endLine ?? '?' }}</p>
            <small>{{ excerpt(chunk.content) || '该代码片段没有可预览内容' }}</small>
          </div>
          <el-button
            :type="selectedChunkIds.has(chunk.id) ? 'success' : 'primary'"
            plain
            :icon="selectedChunkIds.has(chunk.id) ? undefined : Plus"
            :disabled="selectedChunkIds.has(chunk.id)"
            @click="add(chunk)"
          >
            {{ selectedChunkIds.has(chunk.id) ? '已关联' : '关联此代码' }}
          </el-button>
        </article>
      </div>
      <div v-else-if="!loading" class="reference-empty">
        <Search />
        <div>
          <b>没有匹配代码</b>
          <p>尝试缩短关键词，或改用文件名、类名、方法名。例如把完整描述改成 <code>login</code>。</p>
        </div>
      </div>
    </section>
  </section>
</template>

<style scoped>
.reference-selector {
  display: grid;
  gap: 14px;
  width: 100%;
  padding: 1px;
}

.reference-intro {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) auto;
  align-items: center;
  gap: 11px;
  padding: 12px;
  border: 1px solid #cfe0f1;
  border-radius: 7px;
  background: linear-gradient(100deg, #f3f8fd 0%, #fbfdff 70%);
}

.reference-intro-icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  color: #0066cc;
  border: 1px solid #bed7ef;
  border-radius: 6px;
  background: #fff;
}

.reference-intro-icon :deep(svg) { width: 17px; }
.reference-intro b { font-size: 13px; }
.reference-intro p { margin: 3px 0 0; color: #66717c; font-size: 11px; line-height: 1.5; }
.reference-intro > span { color: #005eb8; font-size: 11px; font-weight: 650; white-space: nowrap; }

.search-guide {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  gap: 5px 10px;
  padding: 10px 12px;
  border-left: 3px solid #f0b429;
  background: #fffaf0;
}

.search-guide-label {
  grid-row: 1 / 3;
  color: #7a5300;
  font-size: 11px;
  font-weight: 700;
}

.search-guide p { margin: 0; color: #5f5a50; font-size: 11px; line-height: 1.55; }
.search-examples { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.search-examples span { color: #8b8171; font-size: 10px; }
.search-examples code,
.reference-empty code {
  padding: 2px 5px;
  color: #34495e;
  border: 1px solid #e6dac1;
  border-radius: 3px;
  background: #fff;
  font-size: 10px;
}

.reference-search {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
}

.reference-group {
  overflow: hidden;
  border: 1px solid #dde2e7;
  border-radius: 7px;
  background: #fff;
}

.group-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 38px;
  padding: 0 11px;
  border-bottom: 1px solid #e8ebee;
  background: #f8f9fa;
}

.group-heading b { font-size: 11px; }
.group-heading span { color: #777f87; font-size: 10px; }

.selected-references,
.reference-results {
  display: grid;
}

.selected-references article {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 10px;
  min-height: 62px;
  padding: 9px 11px 9px 16px;
  border-bottom: 1px solid #edf0f2;
}

.selected-references article:last-child,
.reference-results article:last-child { border-bottom: 0; }

.code-rail {
  position: absolute;
  top: 10px;
  bottom: 10px;
  left: 7px;
  width: 3px;
  border-radius: 2px;
  background: #0066cc;
}

.reference-copy { display: grid; min-width: 0; gap: 2px; }
.reference-copy b,
.reference-copy span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.reference-copy b { font-size: 12px; }
.reference-copy span { color: #59636e; font-size: 10px; }
.reference-copy small { color: #8a9198; font-size: 9px; }
.reference-actions { display: flex; align-items: center; }

.reference-results {
  max-height: 310px;
  overflow: auto;
}

.reference-results article {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  padding: 11px;
  border-bottom: 1px solid #edf0f2;
}

.reference-results article:hover { background: #f7fbff; }
.result-main { display: grid; min-width: 0; gap: 4px; }
.result-title { display: flex; align-items: center; gap: 6px; min-width: 0; }
.result-title b { overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.result-title span {
  padding: 2px 5px;
  color: #65717d;
  border-radius: 3px;
  background: #eef1f4;
  font-size: 9px;
  white-space: nowrap;
}
.result-main p {
  overflow: hidden;
  margin: 0;
  color: #576574;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.result-main small {
  overflow: hidden;
  color: #7a8087;
  font-size: 10px;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reference-empty {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px;
  color: #77818b;
}
.reference-empty > svg { width: 24px; color: #a8b0b8; }
.reference-empty b { color: #454c54; font-size: 12px; }
.reference-empty p { margin: 4px 0 0; font-size: 10px; }

@media (max-width: 760px) {
  .reference-intro { grid-template-columns: 36px minmax(0, 1fr); }
  .reference-intro > span { grid-column: 2; }
  .search-guide { grid-template-columns: 1fr; }
  .search-guide-label { grid-row: auto; }
  .reference-search { grid-template-columns: 1fr; }
  .selected-references article { grid-template-columns: minmax(0, 1fr); padding-left: 16px; }
  .reference-actions { justify-content: flex-start; }
  .reference-results article { grid-template-columns: 1fr; }
}
</style>
