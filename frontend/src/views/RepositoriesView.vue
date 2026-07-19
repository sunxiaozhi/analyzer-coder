<script setup lang="ts">
import { Plus, Search } from '@element-plus/icons-vue';
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';

const query = ref('');
const status = ref('全部状态');
const source = ref('全部来源');
const dialog = ref(false);
const form = reactive({ name: '', path: '', branch: 'main' });
const repos = ref([
  { name: 'ai-platform-service', kind: 'Git 仓库', path: '/data/repos/ai-platform-service', ref: 'main · a1b2c3d', graph: '可用', index: '可用', updated: '14:45' },
  { name: 'order-service', kind: 'Git 仓库', path: '/data/repos/order-service', ref: 'develop · d4e5f6a', graph: '可用', index: '待索引', updated: '11:15' },
  { name: 'gateway', kind: '工作区有修改', path: '/data/repos/gateway', ref: 'main · f7g8h9i', graph: '可用', index: '索引中 68%', updated: '10:42' },
  { name: 'frontend-web', kind: 'Git 仓库', path: '/data/repos/frontend-web', ref: 'main · j1k2l3m', graph: '缺失', index: '不可用', updated: '昨天' },
  { name: 'common-lib', kind: 'Git 仓库', path: '/data/repos/common-lib', ref: 'release/1.2 · n4o5p6q', graph: '已过期', index: '失败', updated: '2 天前' },
]);
const rows = computed(() => repos.value.filter((item) => !query.value || item.name.includes(query.value) || item.path.includes(query.value)));
function addRepository() {
  if (!form.name || !form.path) return ElMessage.warning('请填写仓库名称和本地路径');
  repos.value.unshift({ name: form.name, kind: 'Git 仓库', path: form.path, ref: form.branch + ' · pending', graph: '检测中', index: '待索引', updated: '刚刚' });
  dialog.value = false;
  ElMessage.success('仓库已添加');
}
function handlePageAction(event: Event) {
  const detail = (event as CustomEvent).detail;
  if (detail.route === 'repositories' && detail.label === '添加仓库') dialog.value = true;
}
onMounted(() => window.addEventListener('page-primary-action', handlePageAction));
onBeforeUnmount(() => window.removeEventListener('page-primary-action', handlePageAction));
</script>

<template>
  <section class="page repository-design">
    <div class="summary-strip">
      <div><span>仓库总数</span><b>{{ repos.length }}</b></div>
      <div><span>索引可用</span><b>3</b></div>
      <div><span>需要更新</span><b>1</b></div>
      <div><span>异常</span><b>1</b></div>
    </div>
    <div class="surface">
      <div class="toolbar">
        <el-input v-model="query" :prefix-icon="Search" placeholder="搜索仓库名称或路径" clearable />
        <el-select v-model="status"><el-option label="全部状态" value="全部状态" /></el-select>
        <el-select v-model="source"><el-option label="全部来源" value="全部来源" /></el-select>
      </div>
      <el-table :data="rows">
        <el-table-column label="仓库" min-width="190">
          <template #default="{ row }"><div class="primary-cell"><b>{{ row.name }}</b><span>{{ row.kind }}</span></div></template>
        </el-table-column>
        <el-table-column prop="path" label="本地路径" min-width="300"><template #default="{ row }"><span class="mono">{{ row.path }}</span></template></el-table-column>
        <el-table-column label="分支 / Commit" min-width="180"><template #default="{ row }"><el-tag effect="plain" type="info">{{ row.ref }}</el-tag></template></el-table-column>
        <el-table-column label="CodeGraph" width="118"><template #default="{ row }"><el-tag effect="plain" :type="row.graph==='可用'?'primary':'info'">{{ row.graph }}</el-tag></template></el-table-column>
        <el-table-column label="知识索引" width="132"><template #default="{ row }"><el-tag effect="plain" :type="row.index==='可用'?'primary':'info'">{{ row.index }}</el-tag></template></el-table-column>
        <el-table-column prop="updated" label="最后更新" width="92" />
        <el-table-column label="操作" width="64"><template #default><button class="more-button">•••</button></template></el-table-column>
      </el-table>
    </div>
    <button class="floating-add" type="button" aria-label="添加仓库" @click="dialog=true"><Plus :size="18" /></button>
    <el-dialog v-model="dialog" title="添加仓库" width="480">
      <el-form label-position="top">
        <el-form-item label="仓库名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="本地路径" required><el-input v-model="form.path" /></el-form-item>
        <el-form-item label="默认分支"><el-input v-model="form.branch" /></el-form-item>
        <el-checkbox :model-value="true">自动检测 CodeGraph</el-checkbox>
      </el-form>
      <template #footer><el-button @click="dialog=false">取消</el-button><el-button type="primary" @click="addRepository">保存</el-button></template>
    </el-dialog>
  </section>
</template>
