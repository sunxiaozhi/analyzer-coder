<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, shallowRef, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Activity, Check, KeyRound, Power, Save, Server, ShieldCheck, Unplug } from 'lucide-vue-next';
import { intelligenceApi, type Backup } from '@/api/intelligence';
import {
  llmSettingsApi,
  type LlmAvailability,
  type LlmConnectivityCheck,
  type LlmProvider,
  type LlmProviderInput,
} from '@/api/llmSettings';

const tab = shallowRef('基础设置');
const tabs = ['基础设置', '模型与检索', '排除规则', '备份恢复'];
const settings = reactive<Record<string, string>>({});
const backups = shallowRef<Backup[]>([]);
const busy = shallowRef(false);
const provider = shallowRef<LlmProvider | null>(null);
const check = shallowRef<LlmConnectivityCheck | null>(null);
const savingProvider = shallowRef(false);
const checking = computed(() => check.value?.status === 'QUEUED' || check.value?.status === 'RUNNING');
const formDirty = shallowRef(false);
const apiKey = ref('');
let checkTimer: number | undefined;
let hydratingProvider = false;

const providerForm = reactive<LlmProviderInput>({
  name: '团队模型服务',
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

const stageDefinitions = [
  ['VALIDATE_CONFIG', '配置'],
  ['RESOLVE_AND_AUTHORIZE_TARGET', '目标'],
  ['CONNECT_TLS', 'TLS'],
  ['AUTHENTICATE', '鉴权'],
  ['GENERATE_MINIMAL', '生成'],
  ['STREAM_FIRST_TOKEN', '流式'],
] as const;

const availabilityCopy: Record<LlmAvailability, { label: string; note: string }> = {
  UNCONFIGURED: { label: '未配置', note: '保存一个 Provider 后开始检测' },
  UNTESTED: { label: '待检测', note: '配置已保存，但尚未验证连接' },
  AVAILABLE: { label: '连接可用', note: '模型与流式能力均已验证' },
  DEGRADED: { label: '部分可用', note: '基础生成可用，流式能力异常' },
  UNAVAILABLE: { label: '连接不可用', note: '根据错误提示修正配置后重试' },
};

const availability = computed(() => provider.value?.availability ?? 'UNCONFIGURED');
const canActivate = computed(
  () =>
    provider.value?.id &&
    provider.value.availability === 'AVAILABLE' &&
    provider.value.latestCheckId &&
    provider.value.fingerprint &&
    !formDirty.value,
);

function fillProvider(value: LlmProvider) {
  provider.value = value;
  if (!value.id) return;
  hydratingProvider = true;
  Object.assign(providerForm, {
    name: value.name,
    providerType: value.providerType,
    baseUrl: value.baseUrl,
    model: value.model,
    connectTimeoutMs: value.connectTimeoutMs,
    requestTimeoutMs: value.requestTimeoutMs,
    maxOutputTokens: value.maxOutputTokens,
    temperature: value.temperature,
    streamingEnabled: value.streamingEnabled,
    secretAction: value.secretConfigured ? 'KEEP' : 'CLEAR',
  });
  apiKey.value = '';
  formDirty.value = false;
  queueMicrotask(() => {
    hydratingProvider = false;
    formDirty.value = false;
  });
}

async function load() {
  const [loadedSettings, loadedBackups, loadedProvider] = await Promise.all([
    intelligenceApi.settings(),
    intelligenceApi.backups(),
    llmSettingsApi.provider(),
  ]);
  Object.assign(settings, loadedSettings);
  backups.value = loadedBackups;
  fillProvider(loadedProvider);
  if (loadedProvider.latestCheckId) {
    check.value = await llmSettingsApi.check(loadedProvider.latestCheckId).catch(() => null);
  }
}

async function saveSettings(message = '配置已持久化') {
  busy.value = true;
  try {
    Object.assign(settings, await intelligenceApi.saveSettings({ ...settings }));
    ElMessage.success(message);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败');
  } finally {
    busy.value = false;
  }
}

async function saveProvider() {
  if (!providerForm.baseUrl.trim() || !providerForm.model.trim()) {
    ElMessage.warning('请填写服务地址和模型标识');
    return;
  }
  savingProvider.value = true;
  try {
    const input: LlmProviderInput = {
      ...providerForm,
      secretAction: apiKey.value
        ? 'REPLACE'
        : provider.value?.secretConfigured
          ? 'KEEP'
          : 'CLEAR',
      ...(apiKey.value ? { apiKey: apiKey.value } : {}),
    };
    const saved = await llmSettingsApi.save(input);
    fillProvider(saved);
    check.value = null;
    ElMessage.success('Provider 已保存，下一步请测试连接');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Provider 保存失败');
  } finally {
    apiKey.value = '';
    savingProvider.value = false;
  }
}

async function startCheck() {
  if (!provider.value?.id) return ElMessage.warning('请先保存 Provider');
  if (formDirty.value) return ElMessage.warning('配置有未保存修改，请先保存');
  try {
    check.value = await llmSettingsApi.startCheck(provider.value.id);
    scheduleCheckPoll();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法开始连接检测');
  }
}

function scheduleCheckPoll() {
  window.clearTimeout(checkTimer);
  if (!check.value || !['QUEUED', 'RUNNING'].includes(check.value.status)) return;
  checkTimer = window.setTimeout(async () => {
    try {
      if (!check.value) return;
      check.value = await llmSettingsApi.check(check.value.id);
      if (['QUEUED', 'RUNNING'].includes(check.value.status)) {
        scheduleCheckPoll();
      } else {
        const loaded = await llmSettingsApi.provider();
        fillProvider(loaded);
        check.value = loaded.latestCheckId
          ? await llmSettingsApi.check(loaded.latestCheckId)
          : check.value;
        if (check.value.status === 'SUCCEEDED' && check.value.availability === 'AVAILABLE') {
          ElMessage.success('连接检测通过，可以启用 Provider');
        } else if (check.value.status !== 'CANCELED') {
          ElMessage.warning(check.value.errorSummary ?? '连接检测未通过');
        }
      }
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : '连接检测状态读取失败');
    }
  }, 650);
}

async function cancelCheck() {
  if (!check.value) return;
  check.value = await llmSettingsApi.cancelCheck(check.value.id);
  window.clearTimeout(checkTimer);
  ElMessage.info('连接检测已取消');
}

async function activateProvider() {
  const value = provider.value;
  if (!value?.id || !value.latestCheckId || !value.fingerprint) return;
  try {
    fillProvider(
      await llmSettingsApi.activate(value.id, {
        latestCheckId: value.latestCheckId,
        fingerprint: value.fingerprint,
        expectedActivationVersion: value.activationVersion,
      }),
    );
    ElMessage.success('Provider 已启用');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'Provider 启用失败');
  }
}

async function deactivateProvider() {
  if (!provider.value) return;
  await ElMessageBox.confirm(
    '停用后问答会立即回退到 deterministic-local。是否继续？',
    '停用外部 Provider',
    { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' },
  );
  fillProvider(await llmSettingsApi.deactivate(provider.value.activationVersion));
  ElMessage.success('外部 Provider 已停用');
}

async function backup() {
  busy.value = true;
  try {
    await intelligenceApi.backup();
    await load();
    ElMessage.success('一致性清单备份已生成');
  } finally {
    busy.value = false;
  }
}

async function restore(id: string) {
  await ElMessageBox.confirm(
    '恢复会使所有现有登录会话失效，是否继续？',
    '确认恢复',
    { type: 'warning' },
  );
  await intelligenceApi.restore(id);
  ElMessage.success('备份校验通过，恢复点已应用，请重新登录');
  location.href = '/login';
}

function stageState(stage: string) {
  const result = check.value?.stages.find((item) => item.stage === stage);
  if (result) return result.status.toLowerCase();
  if (check.value?.currentStage === stage && checking.value) return 'running';
  return 'pending';
}

function stageDuration(stage: string) {
  const result = check.value?.stages.find((item) => item.stage === stage);
  return result ? `${result.durationMs}ms` : '—';
}

function formatTime(value: string | null | undefined) {
  return value ? new Date(value).toLocaleString() : '尚无记录';
}

watch(
  providerForm,
  () => {
    if (provider.value?.id && !hydratingProvider) formDirty.value = true;
  },
  { deep: true },
);

watch(apiKey, (value) => {
  if (value) formDirty.value = true;
});

onMounted(() => {
  void load().catch((error) =>
    ElMessage.error(error instanceof Error ? error.message : '加载设置失败'),
  );
});

onBeforeUnmount(() => window.clearTimeout(checkTimer));
</script>

<template>
  <section class="settings-design surface">
    <aside class="settings-nav" aria-label="系统设置分类">
      <button
        v-for="item in tabs"
        :key="item"
        :class="{ active: tab === item }"
        @click="tab = item"
      >
        {{ item }}
      </button>
    </aside>

    <main class="settings-content">
      <template v-if="tab === '模型与检索'">
        <div class="settings-heading model-heading">
          <div>
            <span class="eyebrow">LLM CONTROL PLANE</span>
            <h2>模型与检索</h2>
            <p>保存、检测和启用相互独立。连接测试只发送固定探针，不携带代码。</p>
          </div>
          <div class="availability-pill" :data-state="availability">
            <Activity :size="15" />
            <span>{{ availabilityCopy[availability].label }}</span>
          </div>
        </div>

        <section class="connection-rail" aria-label="模型接入进度">
          <div :class="{ complete: provider?.id }">
            <span><Save :size="15" /></span>
            <div><b>配置</b><small>{{ provider?.id ? `版本 ${provider.version}` : '尚未保存' }}</small></div>
          </div>
          <i></i>
          <div :class="{ complete: availability === 'AVAILABLE', warning: availability === 'DEGRADED' }">
            <span><ShieldCheck :size="15" /></span>
            <div><b>检测</b><small>{{ availabilityCopy[availability].label }}</small></div>
          </div>
          <i></i>
          <div :class="{ complete: provider?.activeConfigId }">
            <span><Power :size="15" /></span>
            <div>
              <b>启用</b>
              <small>
                {{
                  provider?.active
                    ? '当前版本正在承接问答'
                    : provider?.activeConfigId
                      ? '上一有效版本仍在运行'
                      : '仍使用本地模式'
                }}
              </small>
            </div>
          </div>
        </section>

        <div class="model-grid">
          <section class="provider-card">
            <header>
              <div>
                <span class="card-index">CONFIGURATION</span>
                <h3>Provider 配置</h3>
              </div>
              <span v-if="formDirty" class="dirty-mark">有未保存修改</span>
            </header>

            <el-form label-position="top" class="provider-form">
              <div class="form-pair">
                <el-form-item label="配置名称">
                  <el-input v-model="providerForm.name" placeholder="团队模型服务" />
                </el-form-item>
                <el-form-item label="协议">
                  <el-select v-model="providerForm.providerType">
                    <el-option label="OpenAI-compatible" value="OPENAI_COMPATIBLE" />
                  </el-select>
                </el-form-item>
              </div>
              <el-form-item label="服务地址">
                <el-input v-model="providerForm.baseUrl" placeholder="https://llm.example.com/v1">
                  <template #prefix><Server :size="14" /></template>
                </el-input>
                <small>生产环境仅允许 HTTPS；地址、DNS 和实际 IP 会在检测时复核。</small>
              </el-form-item>
              <div class="form-pair">
                <el-form-item label="模型标识">
                  <el-input v-model="providerForm.model" placeholder="model-name" />
                </el-form-item>
                <el-form-item label="API Key">
                  <el-input
                    v-model="apiKey"
                    type="password"
                    show-password
                    autocomplete="new-password"
                    :placeholder="provider?.secretConfigured ? '已配置；留空表示保留' : '输入写入型密钥'"
                  >
                    <template #prefix><KeyRound :size="14" /></template>
                  </el-input>
                </el-form-item>
              </div>
              <div class="parameter-row">
                <el-form-item label="连接超时">
                  <el-input-number v-model="providerForm.connectTimeoutMs" :min="1000" :max="10000" :step="500" />
                  <small>毫秒</small>
                </el-form-item>
                <el-form-item label="请求超时">
                  <el-input-number v-model="providerForm.requestTimeoutMs" :min="3000" :max="120000" :step="1000" />
                  <small>毫秒</small>
                </el-form-item>
                <el-form-item label="最大 Token">
                  <el-input-number v-model="providerForm.maxOutputTokens" :min="1" :max="32768" :step="256" />
                </el-form-item>
                <el-form-item label="Temperature">
                  <el-input-number v-model="providerForm.temperature" :min="0" :max="2" :step="0.1" />
                </el-form-item>
              </div>
              <div class="switch-row">
                <div>
                  <b>检测流式输出</b>
                  <small>连接测试会验证首个 Token 和 SSE 帧格式。</small>
                </div>
                <el-switch v-model="providerForm.streamingEnabled" />
              </div>
              <div class="card-actions">
                <el-button type="primary" :loading="savingProvider" @click="saveProvider">
                  <Save :size="14" />保存 Provider
                </el-button>
                <span>保存不会自动检测或启用。</span>
              </div>
            </el-form>
          </section>

          <section class="diagnostic-card">
            <header>
              <div>
                <span class="card-index">CONNECTIVITY</span>
                <h3>连接诊断</h3>
              </div>
              <span class="request-id" v-if="check"># {{ check.requestId.slice(0, 8) }}</span>
            </header>

            <div class="diagnostic-summary" :data-state="check?.availability ?? availability">
              <div class="signal">
                <Check v-if="check?.availability === 'AVAILABLE'" :size="20" />
                <Unplug v-else :size="20" />
              </div>
              <div>
                <b>{{ availabilityCopy[check?.availability ?? availability].label }}</b>
                <p>{{ check?.errorSummary ?? availabilityCopy[check?.availability ?? availability].note }}</p>
              </div>
            </div>

            <div class="stage-list">
              <div v-for="[stage, label] in stageDefinitions" :key="stage" :data-state="stageState(stage)">
                <span class="stage-dot"></span>
                <b>{{ label }}</b>
                <small>{{ stageDuration(stage) }}</small>
              </div>
            </div>

            <dl class="diagnostic-meta">
              <div><dt>最近成功</dt><dd>{{ formatTime(provider?.lastSuccessAt) }}</dd></div>
              <div><dt>首 Token</dt><dd>{{ check?.firstTokenDurationMs ? `${check.firstTokenDurationMs}ms` : '—' }}</dd></div>
              <div><dt>错误码</dt><dd class="mono">{{ check?.errorCode ?? provider?.lastErrorCode ?? '—' }}</dd></div>
              <div><dt>熔断器</dt><dd>{{ provider?.breakerState === 'OPEN' ? '已打开' : '关闭' }}</dd></div>
            </dl>

            <div class="diagnostic-actions">
              <el-button v-if="!checking" :disabled="!provider?.id || formDirty" @click="startCheck">
                <Activity :size="14" />测试连接
              </el-button>
              <el-button v-else type="danger" plain @click="cancelCheck">取消检测</el-button>
              <el-button
                v-if="!provider?.active"
                type="primary"
                :disabled="!canActivate"
                @click="activateProvider"
              >
                <Power :size="14" />启用 Provider
              </el-button>
              <el-button v-if="provider?.activeConfigId" type="danger" plain @click="deactivateProvider">停用</el-button>
            </div>
          </section>
        </div>

        <section class="outbound-policy">
          <div>
            <ShieldCheck :size="18" />
            <div>
              <b>允许发送检索命中的代码片段</b>
              <p>Provider 启用后仍需此全局策略允许；测试连接不会改变该开关。</p>
            </div>
          </div>
          <el-switch
            v-model="settings.externalModelEnabled"
            active-value="true"
            inactive-value="false"
            @change="saveSettings('外发策略已更新')"
          />
        </section>
      </template>

      <template v-else>
        <div class="settings-heading">
          <h2>{{ tab }}</h2>
          <p>配置保存在 PostgreSQL 中，修改模型或过滤规则后应重建相应索引。</p>
        </div>

        <el-form v-if="tab === '基础设置'" label-position="top">
          <el-form-item label="最大检索结果数">
            <el-input v-model="settings.maxSearchResults" />
          </el-form-item>
          <el-form-item label="备份保留天数">
            <el-input v-model="settings.backupRetentionDays" />
          </el-form-item>
          <el-button type="primary" :loading="busy" @click="saveSettings()">保存设置</el-button>
        </el-form>

        <el-form v-else-if="tab === '排除规则'" label-position="top">
          <el-form-item label="敏感文件排除">
            <el-input v-model="settings.excludedPatterns" type="textarea" :rows="5" />
          </el-form-item>
          <el-button type="primary" :loading="busy" @click="saveSettings('排除规则已保存')">保存规则</el-button>
        </el-form>

        <div v-else>
          <div class="toolbar">
            <el-button type="primary" :loading="busy" @click="backup">立即创建备份</el-button>
          </div>
          <el-table :data="backups">
            <el-table-column prop="status" label="状态" width="100" />
            <el-table-column label="校验和">
              <template #default="{ row }"><span class="mono">{{ row.checksum.slice(0, 16) }}…</span></template>
            </el-table-column>
            <el-table-column label="创建时间" width="190">
              <template #default="{ row }">{{ new Date(row.createdAt).toLocaleString() }}</template>
            </el-table-column>
            <el-table-column label="恢复时间" width="190">
              <template #default="{ row }">{{ row.restoredAt ? new Date(row.restoredAt).toLocaleString() : '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="100">
              <template #default="{ row }"><el-button link type="danger" @click="restore(row.id)">校验并恢复</el-button></template>
            </el-table-column>
          </el-table>
        </div>
      </template>
    </main>
  </section>
</template>

<style scoped>
.model-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
}

.eyebrow,
.card-index {
  color: #75808d;
  font: 600 10px/1.2 "SFMono-Regular", Consolas, monospace;
  letter-spacing: 0.12em;
}

.model-heading h2 {
  margin-top: 6px;
}

.availability-pill {
  display: inline-flex;
  gap: 7px;
  align-items: center;
  padding: 7px 10px;
  color: #74520f;
  font-size: 12px;
  font-weight: 600;
  background: #fff8e7;
  border: 1px solid #ead79f;
  border-radius: 999px;
}

.availability-pill[data-state="AVAILABLE"] {
  color: #126442;
  background: #edf9f3;
  border-color: #a8d8c2;
}

.availability-pill[data-state="UNAVAILABLE"] {
  color: #a13737;
  background: #fff1f1;
  border-color: #e7b8b8;
}

.connection-rail {
  display: grid;
  grid-template-columns: minmax(130px, 1fr) 36px minmax(130px, 1fr) 36px minmax(130px, 1fr);
  align-items: center;
  max-width: 760px;
  margin: 0 0 22px;
}

.connection-rail > div {
  display: flex;
  gap: 10px;
  align-items: center;
  min-width: 0;
  color: #7e8792;
}

.connection-rail > div > span {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  background: #f3f5f7;
  border: 1px solid #dfe3e8;
  border-radius: 50%;
}

.connection-rail > div.complete {
  color: #176b4a;
}

.connection-rail > div.complete > span {
  background: #ebf8f1;
  border-color: #9fd4ba;
}

.connection-rail > div.warning {
  color: #8a6417;
}

.connection-rail > div div {
  display: grid;
}

.connection-rail b {
  color: currentcolor;
  font-size: 12px;
}

.connection-rail small {
  overflow: hidden;
  color: #8b929b;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.connection-rail > i {
  height: 1px;
  margin: 0 8px;
  background: #dfe3e8;
}

.model-grid {
  display: grid;
  grid-template-columns: minmax(440px, 1.45fr) minmax(300px, 0.8fr);
  gap: 14px;
  align-items: start;
}

.provider-card,
.diagnostic-card,
.outbound-policy {
  background: #fff;
  border: 1px solid #dfe3e8;
  border-radius: 8px;
}

.provider-card > header,
.diagnostic-card > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 66px;
  padding: 14px 18px;
  border-bottom: 1px solid #edf0f2;
}

.provider-card h3,
.diagnostic-card h3 {
  margin: 4px 0 0;
  color: #24272b;
  font-size: 15px;
}

.dirty-mark {
  color: #936b1a;
  font-size: 11px;
}

.provider-form {
  max-width: none !important;
  padding: 18px;
}

.form-pair {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.parameter-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.parameter-row :deep(.el-input-number) {
  width: 100%;
}

.provider-form small {
  margin-top: 5px;
}

.switch-row,
.card-actions,
.outbound-policy {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.switch-row {
  min-height: 56px;
  padding: 0 2px 16px;
}

.switch-row div {
  display: grid;
  gap: 3px;
}

.switch-row b,
.outbound-policy b {
  color: #34383d;
  font-size: 12px;
}

.card-actions {
  padding-top: 15px;
  border-top: 1px solid #edf0f2;
}

.card-actions span {
  color: #888f98;
  font-size: 10px;
}

.request-id {
  color: #808893;
  font: 10px "SFMono-Regular", Consolas, monospace;
}

.diagnostic-summary {
  display: grid;
  grid-template-columns: 38px 1fr;
  gap: 11px;
  align-items: center;
  margin: 16px;
  padding: 13px;
  background: #f7f8fa;
  border: 1px solid #e5e8ec;
  border-radius: 7px;
}

.signal {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  color: #8b6417;
  background: #fff8e7;
  border-radius: 7px;
}

.diagnostic-summary[data-state="AVAILABLE"] .signal {
  color: #176b4a;
  background: #e9f7f0;
}

.diagnostic-summary[data-state="UNAVAILABLE"] .signal {
  color: #a33d3d;
  background: #fff0f0;
}

.diagnostic-summary b {
  font-size: 13px;
}

.diagnostic-summary p {
  margin: 4px 0 0;
  color: #727a84;
  font-size: 10px;
  line-height: 1.45;
}

.stage-list {
  display: grid;
  padding: 0 18px;
}

.stage-list > div {
  display: grid;
  grid-template-columns: 14px 1fr auto;
  gap: 8px;
  align-items: center;
  min-height: 34px;
  color: #747c86;
  border-bottom: 1px solid #f0f2f4;
}

.stage-list b {
  font-size: 11px;
  font-weight: 500;
}

.stage-list small {
  margin: 0;
  font: 10px "SFMono-Regular", Consolas, monospace;
}

.stage-dot {
  width: 7px;
  height: 7px;
  background: #d9dde2;
  border-radius: 50%;
}

.stage-list [data-state="running"] .stage-dot {
  background: #337fc7;
  box-shadow: 0 0 0 4px rgb(51 127 199 / 13%);
}

.stage-list [data-state="succeeded"] .stage-dot {
  background: #2e966b;
}

.stage-list [data-state="failed"] .stage-dot {
  background: #c55454;
}

.diagnostic-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin: 16px 18px;
}

.diagnostic-meta div {
  min-width: 0;
}

.diagnostic-meta dt {
  color: #9298a0;
  font-size: 9px;
}

.diagnostic-meta dd {
  margin: 3px 0 0;
  overflow: hidden;
  color: #4f555d;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.diagnostic-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 14px 18px 18px;
  border-top: 1px solid #edf0f2;
}

.outbound-policy {
  margin-top: 14px;
  padding: 15px 18px;
}

.outbound-policy > div {
  display: flex;
  gap: 11px;
  align-items: center;
}

.outbound-policy svg {
  color: #3675ad;
}

.outbound-policy p {
  margin: 4px 0 0;
  color: #7d858e;
  font-size: 10px;
}

@media (max-width: 1080px) {
  .model-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 760px) {
  .settings-content {
    padding: 20px 16px;
  }

  .connection-rail {
    grid-template-columns: 1fr;
    gap: 8px;
  }

  .connection-rail > i {
    width: 1px;
    height: 12px;
    margin-left: 15px;
  }

  .form-pair,
  .parameter-row {
    grid-template-columns: 1fr;
  }

  .model-heading {
    display: grid;
  }

  .outbound-policy {
    align-items: flex-start;
  }
}

@media (prefers-reduced-motion: reduce) {
  .stage-dot {
    transition: none;
  }
}
</style>
