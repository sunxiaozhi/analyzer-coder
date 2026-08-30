<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue';
import { GitMerge, MessageSquareText, Play } from 'lucide-vue-next';
import type { AskModel } from '@/api/intelligence';
import type { PullRequestProviderKind } from '@/api/taskReviews';

export interface PullRequestReviewDraft {
  provider: PullRequestProviderKind;
  number: number;
  task: string | null;
  modelConfigId: string | null;
  apiBaseUrl: string | null;
}

const props = defineProps<{
  loading: boolean;
  disabled?: boolean;
  defaultProvider?: PullRequestProviderKind;
  models?: AskModel[];
  modelsLoading?: boolean;
}>();
const emit = defineEmits<{ submit: [draft: PullRequestReviewDraft] }>();

const provider = shallowRef<PullRequestProviderKind>(props.defaultProvider ?? 'GITHUB');
const number = shallowRef<number | null>(null);
const task = shallowRef('');
const modelConfigId = shallowRef('');
const customApi = shallowRef(false);
const apiBaseUrl = shallowRef('');
const canSubmit = computed(() => (
  !props.loading
  && !props.disabled
  && number.value !== null
  && Number.isInteger(number.value)
  && number.value > 0
  && (!customApi.value || /^https:\/\//i.test(apiBaseUrl.value.trim()))
));

watch(() => props.defaultProvider, value => {
  if (value) provider.value = value;
});

function submit() {
  if (!canSubmit.value || number.value === null) return;
  emit('submit', {
    provider: provider.value,
    number: number.value,
    task: task.value.trim() || null,
    modelConfigId: modelConfigId.value || null,
    apiBaseUrl: customApi.value ? apiBaseUrl.value.trim() || null : null,
  });
}
</script>

<template>
  <section class="provider-form" aria-labelledby="provider-review-title">
    <header>
      <div class="provider-mark"><GitMerge :size="18" /></div>
      <div>
        <span>Provider review</span>
        <h2 id="provider-review-title">同步 PR / MR 提示性审查</h2>
      </div>
      <p>从已绑定仓库和令牌读取真实 Patch；Head 必须等于当前快照，评论只提示、不阻断合并。</p>
    </header>

    <div class="provider-row">
      <label>
        <span>代码托管平台</span>
        <select v-model="provider">
          <option value="GITHUB">GitHub Pull Request</option>
          <option value="GITLAB">GitLab Merge Request</option>
        </select>
      </label>
      <label>
        <span>{{ provider === 'GITHUB' ? 'PR 编号' : 'MR 编号' }}</span>
        <input v-model.number="number" type="number" min="1" step="1" placeholder="例如 128" />
      </label>
    </div>

    <label class="task-field">
      <span>任务说明 <small>可选</small></span>
      <textarea v-model="task" rows="2" maxlength="2000" placeholder="例如：核对退款流程改动的测试和审批要求" @keydown.ctrl.enter="submit" />
    </label>

    <div class="provider-row">
      <label>
        <span>引用总结 <small>可选</small></span>
        <select v-model="modelConfigId" :disabled="modelsLoading">
          <option value="">不使用模型总结</option>
          <option v-for="item in models ?? []" :key="item.id" :value="item.id" :disabled="!item.available">
            {{ item.name }} / {{ item.model }}{{ item.available ? '' : '（不可用）' }}
          </option>
        </select>
      </label>
      <label class="api-toggle">
        <span>企业版 API</span>
        <button type="button" :aria-pressed="customApi" @click="customApi = !customApi">
          {{ customApi ? '使用自定义 HTTPS API' : '按仓库地址自动识别' }}
        </button>
      </label>
    </div>

    <label v-if="customApi" class="api-field">
      <span>API Base URL <small>必须与仓库同主机</small></span>
      <input v-model="apiBaseUrl" type="url" maxlength="500" placeholder="https://git.example.com/api/v4" />
    </label>

    <aside>
      <MessageSquareText :size="16" />
      <span><strong>幂等评论</strong>系统通过隐藏 Marker 查找已有评论；同一 PR/MR 重跑时更新原评论。</span>
    </aside>

    <footer>
      <span>需要仓库维护权限与已绑定访问令牌</span>
      <el-button type="primary" :loading="loading" :disabled="!canSubmit" @click="submit">
        <Play :size="14" />读取并同步审查
      </el-button>
    </footer>
  </section>
</template>

<style scoped>
.provider-form { display: grid; gap: 15px; padding: 20px; border: 1px solid #d8e2df; border-radius: 10px; background: #fff; }
.provider-form > header { display: grid; grid-template-columns: 36px minmax(190px, auto) minmax(260px, 1fr); align-items: center; gap: 10px; padding-bottom: 14px; border-bottom: 1px solid #e5ece9; }
.provider-mark { display: grid; width: 34px; height: 34px; place-items: center; color: #176b55; border: 1px solid #bcd8cf; border-radius: 8px; background: #edf8f4; }
.provider-form header span, label > span { color: #687782; font-size: 10px; font-weight: 700; letter-spacing: .045em; text-transform: uppercase; }
.provider-form h2 { margin: 3px 0 0; color: #21322d; font-size: 17px; }
.provider-form header p { justify-self: end; max-width: 540px; margin: 0; color: #687b74; font-size: 11px; line-height: 1.55; text-align: right; }
.provider-row { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
label { display: grid; gap: 7px; }
label small { color: #98a3ab; font-weight: 500; text-transform: none; }
input, select, textarea, .api-toggle button { width: 100%; color: #22312d; border: 1px solid #ccd9d5; border-radius: 7px; outline: none; background: #fbfdfc; }
input, select, .api-toggle button { height: 38px; padding: 0 10px; }
textarea { min-height: 68px; padding: 10px 11px; resize: vertical; line-height: 1.5; }
input:focus, select:focus, textarea:focus, .api-toggle button:focus-visible { border-color: #27836a; box-shadow: 0 0 0 3px #27836a1a; }
.api-toggle button { color: #4f645d; text-align: left; cursor: pointer; }
.api-toggle button[aria-pressed="true"] { color: #176b55; border-color: #85b9aa; background: #f1faf7; }
aside { display: grid; grid-template-columns: 20px 1fr; gap: 8px; padding: 10px 12px; color: #46645b; border-left: 3px solid #27836a; background: #f0f8f5; font-size: 11px; line-height: 1.5; }
aside span { display: grid; gap: 1px; }
footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
footer > span { color: #82918b; font-size: 10px; }
@media (max-width: 760px) {
  .provider-form { padding: 15px; }
  .provider-form > header, .provider-row { grid-template-columns: 1fr; }
  .provider-mark { display: none; }
  .provider-form header p { justify-self: start; text-align: left; }
  footer { align-items: stretch; flex-direction: column; }
}
</style>
