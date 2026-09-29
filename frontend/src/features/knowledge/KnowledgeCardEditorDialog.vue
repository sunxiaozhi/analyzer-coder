<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue';
import { ElMessage } from 'element-plus';
import {
  intelligenceApi,
  type CardInput,
  type CodeReference,
  type KnowledgeAttachment,
  type KnowledgeCard,
} from '@/api/intelligence';
import { listChunks } from '@/api/repositories';
import { repositoryGovernanceApi, type RepositoryMember } from '@/api/repositoryGovernance';
import { useAuthStore } from '@/stores/authStore';
import KnowledgeCardContentSection from './KnowledgeCardContentSection.vue';
import KnowledgeCardPolicySection from './KnowledgeCardPolicySection.vue';
import KnowledgeCodeAssociationSection from './KnowledgeCodeAssociationSection.vue';
import { useKnowledgeCardEditor } from './useKnowledgeCardEditor';
import KnowledgeBranchScopeSection from './KnowledgeBranchScopeSection.vue';
import type { RepositoryBranch } from '@/api/branches';

const props = defineProps<{
  modelValue: boolean;
  repositoryId: string;
  contextId?: string | null;
  card: KnowledgeCard | null;
  busy: boolean;
  saveError?: string | null;
  branchName?: string | null;
  branchId?: string | null;
  branches?: RepositoryBranch[];
  initialReference?: {
    filePath: string;
    symbolName: string | null;
    contentVersion: string | null;
  } | null;
}>();
const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  submit: [value: CardInput];
  openCode: [reference: CodeReference];
}>();

const auth = useAuthStore();
const { form, reset, toPayload } = useKnowledgeCardEditor(() => auth.account?.id ?? null, () => props.branchId ?? null);
const submitted = shallowRef(false);
const titleError = computed(() => submitted.value && !form.title.trim() ? '请填写标题' : '');
const contentError = computed(() => submitted.value && !form.content.trim() ? '请填写知识正文' : '');
const scopeError = computed(() => submitted.value && props.branchId && form.branchScope?.mode === 'SELECTED_BRANCHES' && !form.branchScope.branchIds.length ? '请选择至少一个共享分支' : '');
const policySummary = computed(() => {
  const legacyCount = form.scope.pathPatterns.length + form.scope.symbols.length + form.scope.modules.length
    + form.obligations.requiredTests.length + form.obligations.requiredApproverAccountIds.length
    + form.obligations.instructions.length + form.obligations.prohibitedPathPatterns.length
    + Number(form.obligations.knowledgeUpdateRequired);
  return `${form.enforcement === 'REQUIRED' ? '强约束' : form.enforcement === 'ADVISORY' ? '重点提醒' : '参考'}${legacyCount ? ` · 保留 ${legacyCount} 项已有规则` : ''}`;
});
const attachments = shallowRef<KnowledgeAttachment[]>([]);
const uploading = shallowRef(false);
const codeReferences = shallowRef<CodeReference[]>([]);
const members = shallowRef<RepositoryMember[]>([]);
const membersLoading = shallowRef(false);
const loadedMembersRepository = shallowRef<string | null>(null);
let referencePrimeVersion = 0;


watch(
  () => [props.modelValue, props.card] as const,
  () => {
    if (!props.modelValue) {
      referencePrimeVersion += 1;
      return;
    }
    reset(props.card);
    submitted.value = false;
    const cardReferences = props.card?.codeReferences ?? [];
    attachments.value = props.card ? [...props.card.attachments] : [];
    codeReferences.value = [...cardReferences];
    if (form.enforcement === 'REQUIRED') void loadMembers();
    const primeVersion = ++referencePrimeVersion;
    if (!props.card && props.initialReference) {
      void primeInitialReference(props.initialReference, primeVersion);
    }
  },
  { immediate: true },
);

watch(
  () => [props.modelValue, form.enforcement, props.repositoryId] as const,
  ([visible, enforcement]) => {
    if (visible && enforcement === 'REQUIRED') void loadMembers();
  },
);

async function primeInitialReference(
  target: NonNullable<typeof props.initialReference>,
  version: number,
) {
  try {
    const response = await listChunks(props.repositoryId, {
      q: target.symbolName || target.filePath,
      limit: 20,
    }, props.contextId);
    if (version !== referencePrimeVersion || !props.modelValue || props.card) return;
    const candidates = response.chunks.filter(chunk => chunk.filePath === target.filePath
      && (!target.contentVersion || chunk.contentVersion === target.contentVersion));
    const selected = candidates.find(chunk => target.symbolName && chunk.symbolName === target.symbolName)
      ?? candidates.find(chunk => chunk.chunkType === 'FILE')
      ?? candidates[0];
    if (!selected) {
      ElMessage.warning('内容索引中没有可直接绑定的代码片段，请在关联代码区域手动选择');
      return;
    }
    codeReferences.value = [{
      repositoryId: props.repositoryId,
      chunkId: selected.id,
      contentVersion: selected.contentVersion,
      filePath: selected.filePath,
      symbolName: selected.symbolName,
      startLine: selected.startLine,
      endLine: selected.endLine,
      contentHash: selected.contentHash,
      stale: false,
    }];
    ElMessage.success(`已关联 ${selected.symbolName || selected.filePath}`);
  } catch (error) {
    if (version === referencePrimeVersion) {
      ElMessage.warning(error instanceof Error ? error.message : '代码引用预绑定失败，请手动选择');
    }
  }
}

async function loadMembers() {
  if (!props.modelValue || !props.repositoryId
    || membersLoading.value || loadedMembersRepository.value === props.repositoryId) return;
  membersLoading.value = true;
  try {
    members.value = await repositoryGovernanceApi.members(props.repositoryId);
  } catch (error) {
    members.value = auth.account ? [{
      accountId: auth.account.id,
      username: auth.account.username,
      displayName: auth.account.displayName,
      accountRole: auth.account.role,
      enabled: true,
      relationship: 'MAINTAIN',
      permissionLevel: 'MAINTAIN',
    }] : [];
    ElMessage.error(error instanceof Error ? error.message : '项目成员加载失败');
  } finally {
    loadedMembersRepository.value = props.repositoryId;
    membersLoading.value = false;
  }
}

function useCurrentAccount() {
  form.ownerAccountId = auth.account?.id ?? null;
}

async function chooseFiles(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  if (!files.length) return;
  uploading.value = true;
  try {
    for (const file of files) {
      const attachment = await intelligenceApi.uploadAttachment(props.repositoryId, file);
      attachments.value = [...attachments.value, attachment];
    }
    ElMessage.success(`已上传 ${files.length} 个附件`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '附件上传失败');
  } finally {
    uploading.value = false;
    input.value = '';
  }
}

function removeAttachment(id: string) {
  attachments.value = attachments.value.filter(item => item.id !== id);
}

function insertAttachment(item: KnowledgeAttachment) {
  const alt = item.originalName.replace(/\.[^.]+$/, '');
  form.content += `${form.content.endsWith('\n') || !form.content ? '' : '\n'}![${alt}](knowledge-attachment://${item.id})\n`;
}

function save() {
  submitted.value = true;
  if (titleError.value || contentError.value || scopeError.value || props.busy || uploading.value) return;
  emit('submit', toPayload({
    attachmentIds: attachments.value.map(item => item.id),
    codeReferences: codeReferences.value,
  }));
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="card ? '编辑知识卡片' : '新建知识卡片'"
    width="min(1200px, 96vw)" top="3vh" destroy-on-close
    :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy"
    @update:model-value="emit('update:modelValue', $event)">
    <p class="editor-intro">保存生成草稿修订，确认后通过列表发布。</p>
    <el-form label-position="top" class="knowledge-card-form">
      <KnowledgeBranchScopeSection v-if="branchId" v-model="form.branchScope" class="editor-scope"
        :key="card?.id ?? 'new'" :current-branch-id="branchId" :branch-name="branchName" :branches="branches ?? []" :error="scopeError" />
      <KnowledgeCardContentSection class="editor-content"
        v-model:title="form.title" v-model:knowledge-kind="form.knowledgeKind" v-model:content="form.content" v-model:tags="form.tags"
        :repository-id="repositoryId" :attachments="attachments" :uploading="uploading" :title-error="titleError" :content-error="contentError" />
      <aside class="editor-settings" aria-label="知识设置">
        <KnowledgeCardContentSection part="settings"
          v-model:title="form.title" v-model:knowledge-kind="form.knowledgeKind" v-model:content="form.content" v-model:tags="form.tags"
          :repository-id="repositoryId" :attachments="attachments" :uploading="uploading"
          @choose-files="chooseFiles" @remove-attachment="removeAttachment" @insert-attachment="insertAttachment" />
        <details class="settings-group">
          <summary>关联代码 · {{ codeReferences.length }} 处</summary>
          <KnowledgeCodeAssociationSection v-model:references="codeReferences" :context-id="contextId"
            :repository-id="repositoryId" @open-code="emit('openCode', $event)" />
        </details>
        <details class="settings-group">
          <summary>约束设置 · {{ policySummary }}</summary>
          <KnowledgeCardPolicySection v-model:enforcement="form.enforcement" v-model:owner-account-id="form.ownerAccountId"
            :members="members" :members-loading="membersLoading" :current-account-available="Boolean(auth.account)"
            @use-current-account="useCurrentAccount" />
        </details>
      </aside>
    </el-form>
    <template #footer>
      <el-alert v-if="saveError" class="save-error" type="error" :closable="false" :title="saveError" role="alert" />
      <el-button :disabled="busy" @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="busy" :disabled="uploading" @click="save">{{ card ? '保存新修订' : '创建草稿' }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.editor-intro { margin: 0 0 16px; color: var(--app-text-muted); font-size: 14px; }
.knowledge-card-form { display: grid; grid-template-columns: minmax(0, 7fr) minmax(280px, 3fr); grid-template-rows: auto 1fr; align-items: start; max-height: calc(94dvh - 190px); gap: 16px; overflow: auto; overscroll-behavior: contain; padding: 2px; }
.editor-content { grid-column: 1; grid-row: 1 / 3; min-width: 0; }
.editor-scope { grid-column: 2; grid-row: 1; min-width: 0; }
.editor-settings { grid-column: 2; display: grid; gap: 12px; min-width: 0; }
.settings-group { border: 1px solid var(--app-border); border-radius: 8px; }
.settings-group > summary { padding: 12px; cursor: pointer; font-size: 14px; overflow-wrap: anywhere; }
.settings-group :deep(.editor-section) { padding: 12px; border: 0; }
.save-error { margin-bottom: 12px; text-align: left; }
@media (max-width: 900px) {
  .knowledge-card-form { grid-template-columns: minmax(0, 1fr); }
  .editor-scope, .editor-content, .editor-settings { grid-column: 1; grid-row: auto; }
}
</style>
