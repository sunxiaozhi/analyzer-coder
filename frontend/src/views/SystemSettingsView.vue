<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, shallowRef } from 'vue';
import { ElMessage } from 'element-plus';
import {
  Activity, ArrowRightLeft, Box, CirclePlus, Cpu, Database, KeyRound,
  Pencil, Save, Server,
} from 'lucide-vue-next';
import {
  llmSettingsApi,
  type LlmConnectivityCheck,
  type LlmProvider,
  type LlmProviderInput,
  type VectorModel,
  type VectorModelInput,
} from '@/api/llmSettings';

const section = shallowRef<'generation' | 'vector'>('generation');
const providers = shallowRef<LlmProvider[]>([]);
const vectorModels = shallowRef<VectorModel[]>([]);
const loading = shallowRef(false);
const saving = shallowRef(false);
const checkingId = shallowRef<string | null>(null);
const checkingVectorId = shallowRef<string | null>(null);
const providerDialog = shallowRef(false);
const vectorDialog = shallowRef(false);
const editingProviderId = shallowRef<string | null>(null);
const editingVectorId = shallowRef<string | null>(null);
const apiKey = ref('');
const vectorApiKey = ref('');
let checkTimer: number | undefined;

const providerForm = reactive<LlmProviderInput>({
  name: '',
  providerType: 'OPENAI_COMPATIBLE',
  baseUrl: '',
  model: '',
  connectTimeoutMs: 5000,
  requestTimeoutMs: 60000,
  maxOutputTokens: 2048,
  temperature: 0.2,
  streamingEnabled: true,
  secretAction: 'CLEAR',
});

const vectorForm = reactive<VectorModelInput>({
  name: '',
  providerType: 'LOCAL_HASH',
  baseUrl: '',
  model: '',
  dimension: 64,
  requestTimeoutMs: 30000,
  secretAction: 'CLEAR',
});

const availabilityCopy = {
  UNCONFIGURED: ['未配置', 'neutral'],
  UNTESTED: ['待检测', 'pending'],
  AVAILABLE: ['可用', 'available'],
  DEGRADED: ['部分可用', 'warning'],
  UNAVAILABLE: ['不可用', 'danger'],
} as const;

async function load() {
  loading.value = true;
  try {
    const [loadedProviders, loadedVectors] = await Promise.all([
      llmSettingsApi.providers(),
      llmSettingsApi.vectorModels(),
    ]);
    providers.value = loadedProviders;
    vectorModels.value = loadedVectors;
  } finally {
    loading.value = false;
  }
}

function openCreateProvider() {
  editingProviderId.value = null;
  Object.assign(providerForm, {
    name: '', providerType: 'OPENAI_COMPATIBLE', baseUrl: '', model: '',
    connectTimeoutMs: 5000, requestTimeoutMs: 60000, maxOutputTokens: 2048,
    temperature: 0.2, streamingEnabled: true, secretAction: 'CLEAR',
  });
  apiKey.value = '';
  providerDialog.value = true;
}

function openEditProvider(item: LlmProvider) {
  editingProviderId.value = item.id;
  Object.assign(providerForm, {
    name: item.name,
    providerType: item.providerType,
    baseUrl: item.baseUrl,
    model: item.model,
    connectTimeoutMs: item.connectTimeoutMs,
    requestTimeoutMs: item.requestTimeoutMs,
    maxOutputTokens: item.maxOutputTokens,
    temperature: item.temperature,
    streamingEnabled: item.streamingEnabled,
    secretAction: item.secretConfigured ? 'KEEP' : 'CLEAR',
  });
  apiKey.value = '';
  providerDialog.value = true;
}

async function saveProvider() {
  if (!providerForm.name.trim() || !providerForm.baseUrl.trim() || !providerForm.model.trim()) {
    return ElMessage.warning('请填写名称、服务地址和模型标识');
  }
  saving.value = true;
  try {
    const input: LlmProviderInput = {
      ...providerForm,
      secretAction: apiKey.value ? 'REPLACE' : editingProviderId.value
        ? providerForm.secretAction
        : 'CLEAR',
      ...(apiKey.value ? { apiKey: apiKey.value } : {}),
    };
    if (editingProviderId.value) {
      await llmSettingsApi.updateProvider(editingProviderId.value, input);
      ElMessage.success('模型备案已更新，请重新检测');
    } else {
      await llmSettingsApi.createProvider(input);
      ElMessage.success('模型已备案');
    }
    providerDialog.value = false;
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败');
  } finally {
    apiKey.value = '';
    saving.value = false;
  }
}

async function testProvider(item: LlmProvider) {
  if (!item.id) return;
  checkingId.value = item.id;
  try {
    let result = await llmSettingsApi.startCheck(item.id);
    const poll = async () => {
      result = await llmSettingsApi.check(result.id);
      if (['QUEUED', 'RUNNING'].includes(result.status)) {
        checkTimer = window.setTimeout(() => void poll(), 700);
        return;
      }
      checkingId.value = null;
      await load();
      if (result.status === 'SUCCEEDED' && result.availability === 'AVAILABLE') {
        ElMessage.success(`${item.name} 连接检测通过`);
      } else {
        ElMessage.warning(result.errorSummary ?? '连接检测未通过');
      }
    };
    await poll();
  } catch (error) {
    checkingId.value = null;
    ElMessage.error(error instanceof Error ? error.message : '无法开始连接检测');
  }
}

function openCreateVector() {
  editingVectorId.value = null;
  Object.assign(vectorForm, {
    name: '', providerType: 'LOCAL_HASH', baseUrl: '', model: '', dimension: 64,
    requestTimeoutMs: 30000, secretAction: 'CLEAR',
  });
  vectorApiKey.value = '';
  vectorDialog.value = true;
}

function openEditVector(item: VectorModel) {
  editingVectorId.value = item.id;
  Object.assign(vectorForm, {
    name: item.name, providerType: item.providerType, model: item.model, dimension: item.dimension,
    baseUrl: item.baseUrl ?? '', requestTimeoutMs: item.requestTimeoutMs,
    secretAction: item.secretConfigured ? 'KEEP' : 'CLEAR',
  });
  vectorApiKey.value = '';
  vectorDialog.value = true;
}

function changeVectorProvider(providerType: VectorModelInput['providerType']) {
  if (providerType === 'LOCAL_HASH') vectorForm.dimension = 64;
}

async function saveVector() {
  if (!vectorForm.name.trim() || !vectorForm.model.trim()) {
    return ElMessage.warning('请填写名称和模型标识');
  }
  saving.value = true;
  try {
    const input: VectorModelInput = {
      ...vectorForm,
      secretAction: vectorApiKey.value ? 'REPLACE' : editingVectorId.value
        ? vectorForm.secretAction : vectorForm.providerType === 'OPENAI_COMPATIBLE' ? 'REPLACE' : 'CLEAR',
      ...(vectorApiKey.value ? { apiKey: vectorApiKey.value } : {}),
    };
    if (editingVectorId.value) {
      await llmSettingsApi.updateVectorModel(editingVectorId.value, input);
      ElMessage.success('向量模型备案已更新');
    } else {
      await llmSettingsApi.createVectorModel(input);
      ElMessage.success('向量模型已备案');
    }
    vectorDialog.value = false;
    await load();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败');
  } finally {
    vectorApiKey.value = '';
    saving.value = false;
  }
}

async function activateVector(item: VectorModel) {
  try {
    await llmSettingsApi.activateVectorModel(item.id, item.activationVersion);
    await load();
    ElMessage.success(`已切换到 ${item.name}，后续检索会重建不匹配的向量`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '切换失败');
  }
}

async function testVector(item: VectorModel) {
  checkingVectorId.value = item.id;
  try {
    const result = await llmSettingsApi.checkVectorModel(item.id);
    if (result.available) {
      ElMessage.success(`${item.name} 检测通过：${result.dimension} 维，${result.durationMs} ms`);
    } else {
      ElMessage.warning(result.errorSummary ?? '向量模型检测未通过');
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '向量模型检测失败');
  } finally {
    checkingVectorId.value = null;
  }
}

function status(item: LlmProvider) {
  return availabilityCopy[item.availability] ?? availabilityCopy.UNCONFIGURED;
}

function formatTime(value: string | null) {
  return value ? new Date(value).toLocaleString() : '尚无记录';
}

onMounted(() => void load().catch(error =>
  ElMessage.error(error instanceof Error ? error.message : '加载模型配置失败')));
onBeforeUnmount(() => window.clearTimeout(checkTimer));
</script>

<template>
  <section class="model-registry surface" v-loading="loading">
    <nav class="registry-tabs" aria-label="模型类型">
      <button :class="{ active: section === 'generation' }" @click="section = 'generation'">
        <Cpu :size="16" />问答模型 <span>{{ providers.length }}</span>
      </button>
      <button :class="{ active: section === 'vector' }" @click="section = 'vector'">
        <Database :size="16" />向量模型 <span>{{ vectorModels.length }}</span>
      </button>
    </nav>

    <main v-if="section === 'generation'" class="registry-body">
      <div class="section-toolbar">
        <el-button type="primary" @click="openCreateProvider"><CirclePlus :size="15" />新增模型</el-button>
      </div>
      <div v-if="providers.length" class="model-grid">
        <article v-for="item in providers" :key="item.id ?? item.version" class="model-card">
          <header class="card-header">
            <el-tag effect="plain" size="small">OpenAI-compatible</el-tag>
            <el-tag
              :type="item.availability === 'AVAILABLE' ? 'success' : item.availability === 'UNAVAILABLE' ? 'danger' : item.availability === 'DEGRADED' ? 'warning' : 'info'"
              size="small"
            >
              {{ status(item)[0] }}
            </el-tag>
          </header>
          <div class="model-title">
            <h4>{{ item.name }}</h4>
            <code>{{ item.model }}</code>
          </div>
          <dl>
            <div><dt>协议</dt><dd>OpenAI-compatible</dd></div>
            <div><dt>服务地址</dt><dd>{{ item.baseUrl }}</dd></div>
            <div><dt>最近成功</dt><dd>{{ formatTime(item.lastSuccessAt) }}</dd></div>
            <div><dt>输出上限</dt><dd>{{ item.maxOutputTokens }} tokens</dd></div>
          </dl>
          <footer class="card-actions">
            <el-button link :loading="checkingId === item.id" @click="testProvider(item)">
              <Activity :size="14" />检测
            </el-button>
            <el-button link @click="openEditProvider(item)">
              <Pencil :size="14" />编辑
            </el-button>
          </footer>
        </article>
      </div>
      <div v-else class="empty-registry">
        <Box :size="28" /><b>尚未备案问答模型</b><p>新增配置并通过连接检测后，即可在知识问答页面选择使用。</p>
      </div>
    </main>

    <main v-else class="registry-body">
      <div class="section-toolbar actions-only">
        <el-button type="primary" @click="openCreateVector"><CirclePlus :size="15" />新增向量模型</el-button>
      </div>
      <div v-if="vectorModels.length" class="model-grid vector-grid">
        <article v-for="item in vectorModels" :key="item.id" class="model-card" :class="{ active: item.active }">
          <header class="card-header">
            <el-tag effect="plain" size="small">{{ item.providerType === 'LOCAL_HASH' ? '本地哈希' : 'OpenAI-compatible' }}</el-tag>
            <el-tag :type="item.active ? 'success' : 'info'" size="small">{{ item.active ? '当前启用' : '已备案' }}</el-tag>
          </header>
          <div class="model-title"><h4>{{ item.name }}</h4><code>{{ item.model }}</code></div>
          <dl>
            <div><dt>运行方式</dt><dd>{{ item.providerType === 'LOCAL_HASH' ? '本地确定性' : '外部 API' }}</dd></div>
            <div><dt>向量维度</dt><dd>{{ item.dimension }}</dd></div>
            <div><dt>数据外发</dt><dd>{{ item.providerType === 'LOCAL_HASH' ? '无' : '索引文本' }}</dd></div>
            <div><dt>备案时间</dt><dd>{{ formatTime(item.createdAt) }}</dd></div>
          </dl>
          <footer class="card-actions">
            <el-button link :loading="checkingVectorId === item.id" @click="testVector(item)"><Activity :size="14" />检测</el-button>
            <el-button v-if="!item.active" link type="primary" @click="activateVector(item)"><ArrowRightLeft :size="14" />切换使用</el-button>
            <el-button link :disabled="item.active" @click="openEditVector(item)"><Pencil :size="14" />编辑</el-button>
          </footer>
        </article>
      </div>
      <div v-else class="empty-registry">
        <Database :size="28" />
        <b>尚未备案向量模型</b>
        <p>新增配置后先检测连接，再切换为系统模型。</p>
      </div>
    </main>

    <el-dialog v-model="providerDialog" :title="editingProviderId ? '编辑问答模型' : '新增问答模型'" width="680px" destroy-on-close>
      <el-form label-position="top" class="dialog-form">
        <div class="form-pair">
          <el-form-item label="备案名称"><el-input v-model="providerForm.name" placeholder="例如：生产问答模型" /></el-form-item>
          <el-form-item label="协议"><el-select v-model="providerForm.providerType"><el-option label="OpenAI-compatible" value="OPENAI_COMPATIBLE" /></el-select></el-form-item>
        </div>
        <el-form-item label="服务地址"><el-input v-model="providerForm.baseUrl" placeholder="https://llm.example.com/v1"><template #prefix><Server :size="14" /></template></el-input></el-form-item>
        <div class="form-pair">
          <el-form-item label="模型标识"><el-input v-model="providerForm.model" placeholder="model-name" /></el-form-item>
          <el-form-item label="API Key"><el-input v-model="apiKey" type="password" show-password :placeholder="editingProviderId ? '留空保留现有密钥' : '可选'" ><template #prefix><KeyRound :size="14" /></template></el-input></el-form-item>
        </div>
        <div class="form-quad">
          <el-form-item label="连接超时(ms)"><el-input-number v-model="providerForm.connectTimeoutMs" :min="1000" :max="10000" /></el-form-item>
          <el-form-item label="请求超时(ms)"><el-input-number v-model="providerForm.requestTimeoutMs" :min="3000" :max="120000" /></el-form-item>
          <el-form-item label="最大 Token"><el-input-number v-model="providerForm.maxOutputTokens" :min="1" :max="32768" /></el-form-item>
          <el-form-item label="Temperature"><el-input-number v-model="providerForm.temperature" :min="0" :max="2" :step="0.1" /></el-form-item>
        </div>
        <div class="switch-field"><span><b>检测流式能力</b><small>连接检测会验证 SSE 与首 Token。</small></span><el-switch v-model="providerForm.streamingEnabled" /></div>
      </el-form>
      <template #footer><el-button @click="providerDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveProvider"><Save :size="14" />保存备案</el-button></template>
    </el-dialog>

    <el-dialog v-model="vectorDialog" :title="editingVectorId ? '编辑向量模型' : '新增向量模型'" width="520px" destroy-on-close>
      <el-form label-position="top" class="dialog-form">
        <el-form-item label="备案名称"><el-input v-model="vectorForm.name" placeholder="例如：默认代码向量" /></el-form-item>
        <el-form-item label="模型标识"><el-input v-model="vectorForm.model" :placeholder="vectorForm.providerType === 'LOCAL_HASH' ? 'local-hash-64' : 'text-embedding-model'" /></el-form-item>
        <div class="form-pair">
          <el-form-item label="运行方式"><el-select v-model="vectorForm.providerType" @change="changeVectorProvider"><el-option label="本地哈希" value="LOCAL_HASH" /><el-option label="OpenAI-compatible" value="OPENAI_COMPATIBLE" /></el-select></el-form-item>
          <el-form-item label="向量维度"><el-input-number v-model="vectorForm.dimension" :min="vectorForm.providerType === 'LOCAL_HASH' ? 64 : 1" :max="vectorForm.providerType === 'LOCAL_HASH' ? 64 : 4096" :disabled="vectorForm.providerType === 'LOCAL_HASH'" /></el-form-item>
        </div>
        <template v-if="vectorForm.providerType === 'OPENAI_COMPATIBLE'">
          <el-form-item label="服务地址"><el-input v-model="vectorForm.baseUrl" placeholder="https://api.example.com/v1"><template #prefix><Server :size="14" /></template></el-input></el-form-item>
          <div class="form-pair">
            <el-form-item label="API Key"><el-input v-model="vectorApiKey" type="password" show-password :placeholder="editingVectorId ? '留空保留现有密钥' : '请输入 API Key'"><template #prefix><KeyRound :size="14" /></template></el-input></el-form-item>
            <el-form-item label="请求超时(ms)"><el-input-number v-model="vectorForm.requestTimeoutMs" :min="3000" :max="120000" /></el-form-item>
          </div>
        </template>
        <el-alert
          type="info"
          :closable="false"
          :title="vectorForm.providerType === 'LOCAL_HASH'
            ? '本地哈希固定为 64 维。'
            : '请填写模型实际支持的维度；检测会调用 OpenAI-compatible /embeddings 并校验返回长度。'"
        />
      </el-form>
      <template #footer><el-button @click="vectorDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveVector"><Save :size="14" />保存备案</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.model-registry {
  min-height: 100%;
  overflow: auto;
  background: #fff;
}

.registry-tabs {
  display: flex;
  min-height: 54px;
  gap: 4px;
  padding: 0 16px;
  background: #fff;
  border-bottom: 1px solid #ececef;
}

.registry-tabs button {
  display: flex;
  align-items: center;
  gap: 8px;
  align-self: stretch;
  padding: 0 12px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: none;
  color: #65656c;
  font-size: 13px;
}

.registry-tabs button:hover {
  color: #1d1d1f;
}

.registry-tabs button.active {
  border-color: #0066cc;
  color: #005eb8;
  font-weight: 600;
}

.registry-tabs span {
  padding: 2px 6px;
  border-radius: 4px;
  background: #f1f3f5;
  color: #5e6670;
  font: 11px Consolas, monospace;
}

.registry-body {
  padding: 16px;
}

.section-toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  margin-bottom: 12px;
}

.model-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(330px, 1fr));
  gap: 10px;
}

.model-card {
  display: flex;
  min-height: 220px;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
  background: #fff;
  border: 1px solid #d6dbe2;
  border-radius: 6px;
  box-shadow: 0 2px 8px rgb(24 39 58 / 6%);
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}

.model-card:hover {
  border-color: #b9c5d3;
  box-shadow: 0 6px 16px rgb(24 39 58 / 10%);
}

.model-card.active {
  border-color: #9fc3df;
  box-shadow: 0 0 0 1px rgb(0 102 204 / 10%), 0 2px 8px rgb(24 39 58 / 6%);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.model-title {
  min-height: 42px;
}

.model-title h4 {
  margin: 0;
  overflow: hidden;
  font-size: 15px;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-title code {
  display: block;
  margin-top: 6px;
  overflow: hidden;
  color: #71717a;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-card dl {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 18px;
  margin: 0;
  padding: 12px 0;
  border-top: 1px solid #eeeeef;
  border-bottom: 1px solid #eeeeef;
}

.model-card dl div {
  min-width: 0;
}

.model-card dt {
  margin-bottom: 4px;
  color: var(--app-text-muted);
  font-size: 11px;
}

.model-card dd {
  margin: 0;
  overflow: hidden;
  color: #4a4a4f;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
  margin-top: auto;
}

.card-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.empty-registry {
  display: grid;
  place-items: center;
  gap: 7px;
  padding: 58px;
  border: 1px dashed #d6dbe2;
  border-radius: 6px;
  color: var(--app-text-muted);
}

.empty-registry p {
  margin: 0;
  font-size: 11px;
}

.dialog-form {
  padding: 2px 4px;
}

.form-pair {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.form-quad {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 0 14px;
}

.switch-field {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 0;
}

.switch-field span {
  display: grid;
  gap: 4px;
}

.switch-field small {
  color: var(--app-text-muted);
}

@media (max-width: 1000px) {
  .model-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 700px) {
  .registry-body {
    padding: 14px;
  }

  .registry-tabs {
    padding: 0 8px;
  }

  .form-pair,
  .form-quad {
    grid-template-columns: 1fr;
  }
}
</style>
