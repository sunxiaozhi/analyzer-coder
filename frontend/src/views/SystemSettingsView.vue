<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, shallowRef } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  Activity, ArrowRightLeft, Box, Check, CirclePlus, Cpu, Database, KeyRound,
  Pencil, Power, Save, Server, ShieldCheck, Unplug,
} from 'lucide-vue-next';
import { intelligenceApi } from '@/api/intelligence';
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
const settings = reactive<Record<string, string>>({});
const loading = shallowRef(false);
const saving = shallowRef(false);
const checkingId = shallowRef<string | null>(null);
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

const activeProvider = computed(() => providers.value.find(item => item.active) ?? null);

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
    const [loadedSettings, loadedProviders, loadedVectors] = await Promise.all([
      intelligenceApi.settings(),
      llmSettingsApi.providers(),
      llmSettingsApi.vectorModels(),
    ]);
    Object.assign(settings, loadedSettings);
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

async function activateProvider(item: LlmProvider) {
  if (!item.id || !item.latestCheckId || !item.fingerprint) {
    return ElMessage.warning('请先完成连接检测');
  }
  try {
    await llmSettingsApi.activate(item.id, {
      latestCheckId: item.latestCheckId,
      fingerprint: item.fingerprint,
      expectedActivationVersion: item.activationVersion,
    });
    await load();
    ElMessage.success(`已切换到 ${item.name}`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '切换失败');
  }
}

async function deactivateProvider() {
  const item = activeProvider.value;
  if (!item) return;
  await ElMessageBox.confirm('停用后问答将立即回退到本地模式。', '停用问答模型', {
    type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消',
  });
  await llmSettingsApi.deactivate(item.activationVersion);
  await load();
  ElMessage.success('问答模型已停用');
}

async function toggleOutbound() {
  try {
    Object.assign(settings, await intelligenceApi.saveSettings({ ...settings }));
    ElMessage.success('代码外发策略已更新');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '策略更新失败');
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
      <div class="section-toolbar actions-only">
        <el-button type="primary" @click="openCreateProvider"><CirclePlus :size="15" />新增模型</el-button>
      </div>

      <div v-if="providers.length" class="model-grid">
        <article v-for="item in providers" :key="item.id ?? item.version" class="model-card" :class="{ active: item.active }">
          <i class="signal-rail" :data-state="item.active ? 'active' : status(item)[1]"></i>
          <header>
            <div class="model-icon"><Server :size="18" /></div>
            <div>
              <h4>{{ item.name }}</h4>
              <code>{{ item.model }}</code>
            </div>
            <span class="state-pill" :data-state="status(item)[1]">
              <Check v-if="item.availability === 'AVAILABLE'" :size="12" />
              <Unplug v-else :size="12" />{{ status(item)[0] }}
            </span>
          </header>
          <dl>
            <div><dt>协议</dt><dd>OpenAI-compatible</dd></div>
            <div><dt>服务地址</dt><dd>{{ item.baseUrl }}</dd></div>
            <div><dt>最近成功</dt><dd>{{ formatTime(item.lastSuccessAt) }}</dd></div>
            <div><dt>输出上限</dt><dd>{{ item.maxOutputTokens }} tokens</dd></div>
          </dl>
          <footer>
            <span v-if="item.active" class="active-label"><Power :size="13" />当前启用</span>
            <el-button v-else text :disabled="item.availability !== 'AVAILABLE'" @click="activateProvider(item)">
              <ArrowRightLeft :size="14" />切换使用
            </el-button>
            <div class="card-actions">
              <el-button text :loading="checkingId === item.id" @click="testProvider(item)">
                <Activity :size="14" />检测
              </el-button>
              <el-button text :disabled="item.active" @click="openEditProvider(item)">
                <Pencil :size="14" />编辑
              </el-button>
            </div>
          </footer>
        </article>
      </div>
      <div v-else class="empty-registry">
        <Box :size="28" /><b>尚未备案问答模型</b><p>新增配置后先检测连接，再切换为系统模型。</p>
      </div>

      <section class="policy-row">
        <div><ShieldCheck :size="18" /><span><b>允许发送检索命中的代码片段</b><small>模型启用后仍需此策略允许。</small></span></div>
        <el-switch v-model="settings.externalModelEnabled" active-value="true" inactive-value="false" @change="toggleOutbound" />
      </section>
      <el-button v-if="activeProvider" class="deactivate-button" type="danger" plain @click="deactivateProvider">
        停用当前问答模型
      </el-button>
    </main>

    <main v-else class="registry-body">
      <div class="section-toolbar actions-only">
        <el-button type="primary" @click="openCreateVector"><CirclePlus :size="15" />新增向量模型</el-button>
      </div>
      <div v-if="vectorModels.length" class="model-grid vector-grid">
        <article v-for="item in vectorModels" :key="item.id" class="model-card" :class="{ active: item.active }">
          <i class="signal-rail" :data-state="item.active ? 'active' : item.providerType === 'OPENAI_COMPATIBLE' ? 'external' : 'available'"></i>
          <header>
            <div class="model-icon vector"><Database :size="18" /></div>
            <div><h4>{{ item.name }}</h4><code>{{ item.model }}</code></div>
            <span v-if="item.active" class="state-pill" data-state="available"><Check :size="12" />使用中</span>
          </header>
          <dl>
            <div><dt>运行方式</dt><dd>{{ item.providerType === 'LOCAL_HASH' ? '本地确定性' : '外部 API' }}</dd></div>
            <div><dt>向量维度</dt><dd>{{ item.dimension }}</dd></div>
            <div><dt>数据外发</dt><dd>{{ item.providerType === 'LOCAL_HASH' ? '无' : '索引文本' }}</dd></div>
            <div><dt>备案时间</dt><dd>{{ formatTime(item.createdAt) }}</dd></div>
          </dl>
          <footer>
            <span v-if="item.active" class="active-label"><Power :size="13" />当前启用</span>
            <el-button v-else text @click="activateVector(item)"><ArrowRightLeft :size="14" />切换使用</el-button>
            <el-button text :disabled="item.active" @click="openEditVector(item)"><Pencil :size="14" />编辑</el-button>
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
          <el-form-item label="运行方式"><el-select v-model="vectorForm.providerType"><el-option label="本地哈希" value="LOCAL_HASH" /><el-option label="OpenAI-compatible" value="OPENAI_COMPATIBLE" /></el-select></el-form-item>
          <el-form-item label="向量维度"><el-input-number v-model="vectorForm.dimension" :min="64" :max="64" /></el-form-item>
        </div>
        <template v-if="vectorForm.providerType === 'OPENAI_COMPATIBLE'">
          <el-form-item label="服务地址"><el-input v-model="vectorForm.baseUrl" placeholder="https://api.example.com/v1"><template #prefix><Server :size="14" /></template></el-input></el-form-item>
          <div class="form-pair">
            <el-form-item label="API Key"><el-input v-model="vectorApiKey" type="password" show-password :placeholder="editingVectorId ? '留空保留现有密钥' : '请输入 API Key'"><template #prefix><KeyRound :size="14" /></template></el-input></el-form-item>
            <el-form-item label="请求超时(ms)"><el-input-number v-model="vectorForm.requestTimeoutMs" :min="3000" :max="120000" /></el-form-item>
          </div>
        </template>
        <el-alert type="info" :closable="false" title="外部模型必须支持 dimensions=64，并返回 64 维 OpenAI-compatible embedding。" />
      </el-form>
      <template #footer><el-button @click="vectorDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveVector"><Save :size="14" />保存备案</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.model-registry{min-height:100%;overflow:auto;background:#f8fafc}
.section-toolbar p{margin:0;color:#7a838d;font-size:12px}
.registry-tabs{display:flex;gap:4px;padding:12px 28px 0;background:#fff;border-bottom:1px solid #e5e9ee}
.registry-tabs button{display:flex;align-items:center;gap:8px;padding:11px 14px;border:0;border-bottom:2px solid transparent;background:none;color:#697580}
.registry-tabs button.active{border-color:#1769aa;color:#155f99;font-weight:650}.registry-tabs span{padding:1px 6px;border-radius:10px;background:#eef2f5;font:10px Consolas}
.registry-body{padding:24px 28px 32px}.section-toolbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:16px}.section-toolbar h3{margin:0 0 5px;font-size:16px}
.section-toolbar.actions-only{justify-content:flex-end}
.model-grid{display:grid;grid-template-columns:repeat(2,minmax(330px,1fr));gap:13px}.model-card{position:relative;overflow:hidden;padding:17px 18px 0;border:1px solid #dfe5ea;border-radius:11px;background:#fff;box-shadow:0 1px 2px #18222d08}
.model-card.active{border-color:#9fc3df;box-shadow:0 0 0 2px #2475b317}.signal-rail{position:absolute;inset:0 auto 0 0;width:4px;background:#cbd3da}.signal-rail[data-state=active]{background:#1769aa}.signal-rail[data-state=available]{background:#2e936b}.signal-rail[data-state=external]{background:#7356b5}.signal-rail[data-state=pending]{background:#d3972c}.signal-rail[data-state=warning]{background:#d2792c}.signal-rail[data-state=danger]{background:#c65353}
.model-card header{display:grid;grid-template-columns:36px minmax(0,1fr) auto;gap:11px;align-items:center}.model-icon{display:grid;place-items:center;width:36px;height:36px;border-radius:9px;background:#edf4fa;color:#2f6f9f}.model-icon.vector{background:#eef6f1;color:#33785c}
.model-card h4{margin:0 0 4px;font-size:14px}.model-card code{display:block;overflow:hidden;color:#6d7780;font-size:10px;text-overflow:ellipsis;white-space:nowrap}
.state-pill{display:flex;align-items:center;gap:4px;padding:4px 7px;border-radius:999px;background:#f0f2f4;color:#68727b;font-size:10px}.state-pill[data-state=available]{background:#eaf6f0;color:#247453}.state-pill[data-state=pending]{background:#fff5df;color:#996511}.state-pill[data-state=danger]{background:#fceeee;color:#a53f3f}
.model-card dl{display:grid;grid-template-columns:1fr 1fr;gap:13px 18px;margin:18px 0}.model-card dt{color:#8a949d;font-size:9px;text-transform:uppercase}.model-card dd{margin:4px 0 0;overflow:hidden;color:#3f4952;font-size:11px;text-overflow:ellipsis;white-space:nowrap}
.model-card footer{display:flex;align-items:center;justify-content:space-between;min-height:48px;margin:0 -18px;padding:0 14px 0 18px;border-top:1px solid #edf0f2}.card-actions{display:flex}.active-label{display:flex;align-items:center;gap:5px;color:#1769aa;font-size:11px;font-weight:650}
.policy-row{display:flex;align-items:center;justify-content:space-between;margin-top:16px;padding:14px 17px;border:1px solid #dfe5ea;border-radius:10px;background:#fff}.policy-row>div{display:flex;align-items:center;gap:10px}.policy-row span{display:grid;gap:3px}.policy-row small{color:#7e8790}
.deactivate-button{margin-top:12px}.empty-registry{display:grid;place-items:center;gap:7px;padding:58px;border:1px dashed #cfd8df;border-radius:11px;color:#7b8791}.empty-registry p{margin:0;font-size:11px}
.dialog-form{padding:2px 4px}.form-pair{display:grid;grid-template-columns:1fr 1fr;gap:14px}.form-quad{display:grid;grid-template-columns:repeat(2,1fr);gap:0 14px}.switch-field{display:flex;align-items:center;justify-content:space-between;padding:12px 0}.switch-field span{display:grid;gap:4px}.switch-field small{color:#818991}
@media(max-width:1000px){.model-grid{grid-template-columns:1fr}}
@media(max-width:700px){.section-toolbar{align-items:flex-start;flex-direction:column}.registry-body{padding:18px 14px}.registry-tabs{padding-left:14px}.form-pair,.form-quad{grid-template-columns:1fr}}
</style>
