<script setup lang="ts">
import { shallowRef } from 'vue';
import { Archive, ChevronDown } from 'lucide-vue-next';
import { ElMessage } from 'element-plus';
import { useRouter } from 'vue-router';
import { useRepositoryStore } from '@/stores/repositoryStore';
import { intelligenceApi } from '@/api/intelligence';
import type { Repository } from '@/types/api';
const props = defineProps<{ repository: Repository }>();
const emit = defineEmits<{ changed: [] }>();
const store = useRepositoryStore();
const router = useRouter();
const busy = shallowRef(false), detailsOpen = shallowRef(false);
async function execute(kind: 'sync' | 'content' | 'graph') {
  if (busy.value) return;
  if (kind === 'sync' && !props.repository.capabilities.canUpdate) return;
  if (kind === 'content' && !props.repository.capabilities.canIndex) return;
  if (kind === 'graph' && !props.repository.capabilities.canBuildCodeGraph) return;
  busy.value = true;
  try {
    if (kind === 'sync') await store.rescanRepository(props.repository.id);
    else if (kind === 'content') await store.createIndexJob(props.repository.id, 'FULL');
    else await intelligenceApi.buildGraph(props.repository.id);
    emit('changed'); ElMessage.success('单版本代码源操作已提交');
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '操作失败'); }
  finally { busy.value = false; }
}
function command(action: string) {
  if (action === 'details') detailsOpen.value = true;
  else if (['sync', 'content', 'graph'].includes(action)) void execute(action as 'sync' | 'content' | 'graph');
}
function date(value: string | null) { return value ? new Date(value).toLocaleString() : '尚未更新'; }
</script>
<template>
  <section class="single-version" aria-label="单版本代码管理">
    <header><h3>单版本代码</h3><p>ZIP 项目保留一个代码版本，可更新代码并按需构建索引。</p></header>
    <div class="single-version-row"><div class="version-name"><Archive :size="18" /><div><b>当前代码版本</b><small>{{ repository.snapshotId ? '已发布快照' : '尚未发布快照' }}</small></div></div><code>{{ repository.snapshotId?.slice(0, 12) || '—' }}</code><span class="version-date">{{ date(repository.lastScannedAt) }}</span><div class="version-actions"><el-button link type="primary" :disabled="!repository.snapshotId" @click="router.push('/search')">打开代码</el-button><el-dropdown trigger="click" :disabled="busy" @command="command"><button class="version-more" type="button" :disabled="busy">更多<ChevronDown :size="14" /></button><template #dropdown><el-dropdown-menu><el-dropdown-item command="details">版本详情</el-dropdown-item><el-dropdown-item v-if="repository.capabilities.canUpdate" command="sync" divided>更新代码版本</el-dropdown-item><el-dropdown-item v-if="repository.capabilities.canIndex" command="content">构建内容索引</el-dropdown-item><el-dropdown-item v-if="repository.capabilities.canBuildCodeGraph" command="graph">构建代码图谱</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div></div>
    <el-dialog v-model="detailsOpen" title="单版本代码详情" width="min(680px, 94vw)"><dl class="version-details"><dt>项目</dt><dd>{{ repository.name }}</dd><dt>代码快照</dt><dd><code>{{ repository.snapshotId || '尚未发布' }}</code></dd><dt>更新日期</dt><dd>{{ date(repository.lastScannedAt) }}</dd><dt>代码目录</dt><dd>{{ repository.path }}</dd></dl><template #footer><el-button @click="detailsOpen = false">关闭</el-button></template></el-dialog>
  </section>
</template>
<style scoped>
.single-version { padding: 26px 30px 30px; color: var(--app-text-regular); }
.single-version h3 { margin: 0 0 8px; font-size: 17px; font-weight: 600; }.single-version header p { margin: 0 0 24px; color: var(--app-text-muted); font-size: 12px; line-height: 1.8; }
.single-version-row { display: flex; align-items: center; flex-wrap: wrap; gap: 20px 28px; padding: 22px; border: 1px solid var(--app-border); border-radius: 9px; }
.version-name { display: flex; align-items: center; gap: 12px; }.version-name svg { color: var(--app-text-muted); }.version-name > div { display: grid; gap: 5px; }.version-name b { font-size: 13px; font-weight: 600; }.version-name small,.version-date { font-size: 12px; color: var(--app-text-muted); }.single-version-row code { font-size: 12px; color: var(--app-text-muted); }
.version-actions { display: flex; align-items: center; gap: 18px; margin-left: auto; }.version-more { display: flex; align-items: center; gap: 5px; border: 0; background: transparent; color: var(--app-text-muted); font-size: 12px; padding: 5px 0; }.version-more:disabled { opacity: .5; cursor: wait; }
.version-details { display: grid; grid-template-columns: 90px minmax(0, 1fr); gap: 18px; font-size: 13px; }.version-details dt { color: var(--app-text-muted); }.version-details dd { margin: 0; overflow-wrap: anywhere; }
@media (max-width: 760px) { .single-version { padding: 22px 18px; }.single-version-row { padding: 20px 16px; }.version-actions { width: 100%; margin-left: 0; } }
</style>
