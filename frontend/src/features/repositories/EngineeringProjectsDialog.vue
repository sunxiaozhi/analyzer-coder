<script setup lang="ts">
import { computed, reactive, shallowRef, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Connection, Delete, Plus } from '@element-plus/icons-vue';
import {
  engineeringProjectsApi,
  type EngineeringProject,
  type EngineeringProjectInput,
} from '@/api/engineeringProjects';
import type { Repository } from '@/types/api';

const props = defineProps<{ modelValue: boolean; repositories: Repository[] }>();
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>();

type MemberDraft = { repositoryId: string; serviceName: string };
type ContractDraft = {
  id: string | null;
  contractKey: string;
  name: string;
  providerRepositoryId: string;
  consumerRepositoryId: string;
  providerEvidencePath: string;
  consumerEvidencePath: string;
};

const projects = shallowRef<EngineeringProject[]>([]);
const loading = shallowRef(false);
const saving = shallowRef(false);
const editingId = shallowRef<string | null>(null);
const form = reactive({
  name: '', description: '', expectedVersion: null as number | null,
  repositories: [] as MemberDraft[], contracts: [] as ContractDraft[],
});

const managedRepositories = computed(() => props.repositories.filter(item =>
  item.capabilities.canConfigure && item.snapshotId,
));
const editing = computed(() => projects.value.find(item => item.id === editingId.value) ?? null);
const canSave = computed(() => Boolean(
  (!editing.value || canManage(editing.value))
  && form.name.trim()
  && form.repositories.length >= 2
  && form.repositories.every(item => item.repositoryId && validKey(item.serviceName))
  && new Set(form.repositories.map(item => item.repositoryId)).size === form.repositories.length
  && new Set(form.repositories.map(item => item.serviceName.trim().toLowerCase())).size === form.repositories.length
  && form.contracts.every(validContract),
));

watch(() => props.modelValue, value => {
  if (!value) return;
  void load();
}, { immediate: true });

function validKey(value: string) { return /^[a-z0-9][a-z0-9._-]{0,119}$/.test(value.trim().toLowerCase()); }
function validContract(item: ContractDraft) {
  return validKey(item.contractKey) && Boolean(item.name.trim() && item.providerRepositoryId
    && item.consumerRepositoryId && item.providerRepositoryId !== item.consumerRepositoryId
    && item.providerEvidencePath.trim() && item.consumerEvidencePath.trim());
}
function repositoryName(id: string) { return props.repositories.find(item => item.id === id)?.name ?? id; }
function evidenceStatus(id: string | null) {
  if (!id) return null;
  return editing.value?.contracts.find(item => item.id === id)?.current ?? null;
}
function canManage(project: EngineeringProject) {
  return project.repositories.every(member => props.repositories.find(item =>
    item.id === member.repositoryId,
  )?.capabilities.canConfigure);
}
async function load() {
  loading.value = true;
  try { projects.value = await engineeringProjectsApi.list(); }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '工程项目加载失败'); }
  finally { loading.value = false; }
}
function beginCreate() {
  editingId.value = null;
  form.name = '';
  form.description = '';
  form.expectedVersion = null;
  form.repositories = managedRepositories.value.slice(0, 2).map((item, index) => ({
    repositoryId: item.id, serviceName: `${item.name.toLowerCase().replace(/[^a-z0-9._-]+/g, '-') || 'service'}-${index + 1}`,
  }));
  form.contracts = [];
}
function beginEdit(project: EngineeringProject) {
  editingId.value = project.id;
  form.name = project.name;
  form.description = project.description;
  form.expectedVersion = project.version;
  form.repositories = project.repositories.map(item => ({
    repositoryId: item.repositoryId, serviceName: item.serviceName,
  }));
  form.contracts = project.contracts.map(item => ({
    id: item.id, contractKey: item.contractKey, name: item.name,
    providerRepositoryId: item.providerRepositoryId,
    consumerRepositoryId: item.consumerRepositoryId,
    providerEvidencePath: item.providerEvidencePath,
    consumerEvidencePath: item.consumerEvidencePath,
  }));
}
function addMember() { form.repositories.push({ repositoryId: '', serviceName: '' }); }
function removeMember(index: number) { if (form.repositories.length > 2) form.repositories.splice(index, 1); }
function addContract() {
  form.contracts.push({
    id: null, contractKey: '', name: '',
    providerRepositoryId: form.repositories[0]?.repositoryId ?? '',
    consumerRepositoryId: form.repositories[1]?.repositoryId ?? '',
    providerEvidencePath: '', consumerEvidencePath: '',
  });
}
function payload(): EngineeringProjectInput {
  return {
    name: form.name.trim(), description: form.description.trim(), expectedVersion: form.expectedVersion,
    repositories: form.repositories.map(item => ({
      repositoryId: item.repositoryId, serviceName: item.serviceName.trim().toLowerCase(),
    })),
    contracts: form.contracts.map(item => ({
      id: item.id, contractKey: item.contractKey.trim().toLowerCase(), name: item.name.trim(),
      providerRepositoryId: item.providerRepositoryId,
      consumerRepositoryId: item.consumerRepositoryId,
      providerEvidencePath: item.providerEvidencePath.trim(),
      consumerEvidencePath: item.consumerEvidencePath.trim(),
    })),
  };
}
async function save() {
  if (!canSave.value) return ElMessage.warning('请补全唯一服务身份和有效契约证据路径');
  saving.value = true;
  try {
    const result = editingId.value
      ? await engineeringProjectsApi.update(editingId.value, payload())
      : await engineeringProjectsApi.create(payload());
    await load();
    beginEdit(result);
    ElMessage.success('工程项目与跨仓事实已保存');
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '工程项目保存失败'); }
  finally { saving.value = false; }
}
async function remove(project: EngineeringProject) {
  await ElMessageBox.confirm('删除前必须先清理引用该拓扑的跨仓知识 Scope。', `删除“${project.name}”`, { type: 'warning' });
  try {
    await engineeringProjectsApi.remove(project.id, project.version);
    if (editingId.value === project.id) beginCreate();
    await load();
    ElMessage.success('工程项目已删除');
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '删除失败'); }
}
</script>

<template>
  <el-dialog :model-value="modelValue" width="1120" top="4vh" class="engineering-project-dialog"
    title="跨仓工程项目" @update:model-value="emit('update:modelValue', $event)">
    <div class="project-thesis">
      <Connection :size="20" />
      <div><b>只登记可复核关系</b><span>服务名是显式身份；契约必须在提供方与消费方当前内容索引中各有一条证据路径。</span></div>
      <el-button type="primary" plain :disabled="managedRepositories.length < 2" @click="beginCreate">
        新建工程项目
      </el-button>
    </div>
    <div class="project-workbench" v-loading="loading">
      <aside class="project-index">
        <button v-for="project in projects" :key="project.id" type="button"
          :class="{ active: editingId === project.id }" @click="beginEdit(project)">
          <b>{{ project.name }}</b>
          <span>{{ project.repositories.length }} 仓库 · {{ project.contracts.length }} 契约</span>
          <em v-if="project.contracts.some(item => !item.current)">证据待刷新</em>
        </button>
        <el-empty v-if="!projects.length" :image-size="48" description="尚未建立跨仓工程项目" />
      </aside>

      <main class="project-editor">
        <template v-if="form.repositories.length">
          <el-alert v-if="editing && !canManage(editing)" type="info" :closable="false"
            title="当前账号可以查看完整工程项目，但不能管理所有成员仓库，因此保持只读。" />
          <div class="project-title-row">
            <el-input v-model="form.name" maxlength="120" placeholder="工程项目名称" />
            <el-input v-model="form.description" maxlength="500" placeholder="说明这些仓库为何属于同一工程边界" />
          </div>

          <section>
            <header><div><span>01</span><b>仓库与服务身份</b><small>至少两个当前快照仓库；服务名在项目内唯一。</small></div><el-button link :icon="Plus" @click="addMember">添加仓库</el-button></header>
            <div class="member-grid">
              <div v-for="(member, index) in form.repositories" :key="index" class="member-row">
                <el-select v-model="member.repositoryId" filterable placeholder="选择仓库">
                  <el-option v-for="repository in managedRepositories" :key="repository.id"
                    :value="repository.id" :label="repository.name" />
                </el-select>
                <el-input v-model="member.serviceName" placeholder="order-service" />
                <el-button link :icon="Delete" :disabled="form.repositories.length <= 2" @click="removeMember(index)" />
              </div>
            </div>
          </section>

          <section>
            <header><div><span>02</span><b>跨仓契约证据</b><small>路径必须已被双方当前内容索引收录；保存时生成内容指纹。</small></div><el-button link :icon="Plus" @click="addContract">添加契约</el-button></header>
            <article v-for="(contract, index) in form.contracts" :key="contract.id ?? index" class="contract-card">
              <div class="contract-heading">
                <el-input v-model="contract.contractKey" placeholder="order-payment-v1" />
                <el-input v-model="contract.name" placeholder="订单支付接口" />
                <code v-if="contract.id">{{ contract.id }}</code>
                <el-tag v-if="evidenceStatus(contract.id) !== null" size="small"
                  :type="evidenceStatus(contract.id) ? 'success' : 'warning'">
                  {{ evidenceStatus(contract.id) ? '两端证据当前' : '证据待刷新' }}
                </el-tag>
                <el-button link :icon="Delete" @click="form.contracts.splice(index, 1)" />
              </div>
              <div class="contract-side">
                <b>提供方</b>
                <el-select v-model="contract.providerRepositoryId" placeholder="仓库">
                  <el-option v-for="member in form.repositories" :key="member.repositoryId"
                    :value="member.repositoryId" :label="repositoryName(member.repositoryId)" />
                </el-select>
                <el-input v-model="contract.providerEvidencePath" placeholder="openapi/order.yaml 或接口源码路径" />
              </div>
              <div class="contract-side">
                <b>消费方</b>
                <el-select v-model="contract.consumerRepositoryId" placeholder="仓库">
                  <el-option v-for="member in form.repositories" :key="member.repositoryId"
                    :value="member.repositoryId" :label="repositoryName(member.repositoryId)" />
                </el-select>
                <el-input v-model="contract.consumerEvidencePath" placeholder="调用方契约或客户端源码路径" />
              </div>
            </article>
            <el-empty v-if="!form.contracts.length" :image-size="42" description="可先只建立仓库与服务身份，契约关系不会自动猜测" />
          </section>
        </template>
        <el-empty v-else :image-size="64" description="至少需要两个可管理且已有当前快照的仓库" />
      </main>
    </div>
    <template #footer>
      <el-button v-if="editing && canManage(editing)" type="danger" plain @click="remove(editing)">删除</el-button>
      <span class="footer-spacer" />
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <el-button type="primary" :loading="saving" :disabled="!canSave" @click="save">保存并验证证据</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.project-thesis { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 12px; margin: -4px 0 16px; padding: 12px 14px; color: #24546b; border-left: 4px solid #2f7892; background: #edf7f8; }
.project-thesis div { display: grid; gap: 2px; }
.project-thesis span { color: #637783; font-size: 12px; }
.project-workbench { display: grid; grid-template-columns: 250px minmax(0, 1fr); min-height: 560px; max-height: 68vh; border: 1px solid #d7e2e7; }
.project-index { overflow: auto; padding: 8px; border-right: 1px solid #d7e2e7; background: #f5f8f9; }
.project-index button { display: grid; gap: 4px; width: 100%; margin-bottom: 6px; padding: 11px; text-align: left; border: 1px solid transparent; border-radius: 6px; background: transparent; cursor: pointer; }
.project-index button:hover, .project-index button.active { border-color: #b6d2dc; background: white; }
.project-index span, .project-index em { color: #71838d; font-size: 11px; font-style: normal; }
.project-index em { color: #a45a35; }
.project-editor { overflow: auto; padding: 18px 20px; }
.project-title-row { display: grid; grid-template-columns: minmax(220px, .7fr) minmax(0, 1.3fr); gap: 12px; }
.project-editor section { margin-top: 22px; padding-top: 14px; border-top: 1px solid #dbe5e9; }
.project-editor header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.project-editor header > div { display: grid; grid-template-columns: auto auto; align-items: baseline; gap: 8px; }
.project-editor header span { color: #2f7892; font: 700 11px Consolas, monospace; }
.project-editor header small { grid-column: 2; color: #71838d; }
.member-grid { display: grid; gap: 8px; }
.member-row { display: grid; grid-template-columns: minmax(180px, .8fr) minmax(220px, 1.2fr) 34px; gap: 8px; }
.contract-card { display: grid; gap: 8px; margin-bottom: 10px; padding: 12px; border: 1px solid #d7e2e7; border-radius: 7px; background: #fbfcfc; }
.contract-heading { display: grid; grid-template-columns: minmax(150px, .7fr) minmax(180px, 1fr) auto auto 34px; gap: 8px; align-items: center; }
.contract-heading code { color: #60727c; font-size: 10px; }
.contract-side { display: grid; grid-template-columns: 54px 180px minmax(0, 1fr); align-items: center; gap: 8px; }
.contract-side b { color: #5d707a; font-size: 12px; }
.footer-spacer { display: inline-block; width: calc(100% - 310px); }
@media (max-width: 760px) {
  .project-workbench { grid-template-columns: 1fr; max-height: none; }
  .project-index { max-height: 180px; border-right: 0; border-bottom: 1px solid #d7e2e7; }
  .project-title-row, .member-row, .contract-heading, .contract-side { grid-template-columns: 1fr; }
}
</style>
