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

const props = defineProps<{
  modelValue: boolean;
  repositoryId: string;
  card: KnowledgeCard | null;
  busy: boolean;
  initialReference?: {
    filePath: string;
    symbolName: string | null;
    snapshotId: string | null;
  } | null;
}>();
const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  submit: [value: CardInput];
  openCode: [reference: CodeReference];
}>();

const auth = useAuthStore();
const { form, scopeText, reset, toPayload } = useKnowledgeCardEditor(() => auth.account?.id ?? null);
const attachments = shallowRef<KnowledgeAttachment[]>([]);
const uploading = shallowRef(false);
const codeReferences = shallowRef<CodeReference[]>([]);
const members = shallowRef<RepositoryMember[]>([]);
const membersLoading = shallowRef(false);
const loadedMembersRepository = shallowRef<string | null>(null);
let referencePrimeVersion = 0;

const scopeReady = computed(() => Boolean(
  scopeText.paths.trim()
  || scopeText.symbols.trim()
  || form.scope.modules.length
  || form.scope.repositoryIds.length
  || form.scope.serviceNames.length
  || form.scope.contractIds.length,
));

watch(
  () => [props.modelValue, props.card] as const,
  () => {
    if (!props.modelValue) {
      referencePrimeVersion += 1;
      return;
    }
    reset(props.card);
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
    });
    if (version !== referencePrimeVersion || !props.modelValue || props.card) return;
    const candidates = response.chunks.filter(chunk => chunk.filePath === target.filePath
      && (!target.snapshotId || chunk.snapshotId === target.snapshotId));
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
      snapshotId: selected.snapshotId,
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
    ElMessage.error(error instanceof Error ? error.message : '仓库成员加载失败');
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
  emit('submit', toPayload({
    attachmentIds: attachments.value.map(item => item.id),
    codeReferences: codeReferences.value,
  }));
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="card ? '编辑知识卡片' : '新建知识卡片'"
    width="920"
    top="3vh"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p class="editor-intro">记录一条可检索、可回到源码的项目知识。新卡片会先保存为草稿。</p>

    <el-form label-position="top" class="knowledge-card-form">
      <KnowledgeCardContentSection
        v-model:title="form.title"
        v-model:knowledge-kind="form.knowledgeKind"
        v-model:content="form.content"
        v-model:tags="form.tags"
        :repository-id="repositoryId"
        :attachments="attachments"
        :uploading="uploading"
        @choose-files="chooseFiles"
        @remove-attachment="removeAttachment"
        @insert-attachment="insertAttachment"
      />

      <KnowledgeCodeAssociationSection
        v-model:references="codeReferences"
        v-model:paths="scopeText.paths"
        v-model:symbols="scopeText.symbols"
        :repository-id="repositoryId"
        @open-code="emit('openCode', $event)"
      />

      <KnowledgeCardPolicySection
        v-model:enforcement="form.enforcement"
        v-model:owner-account-id="form.ownerAccountId"
        :members="members"
        :members-loading="membersLoading"
        :current-account-available="Boolean(auth.account)"
        :scope-ready="scopeReady"
        @use-current-account="useCurrentAccount"
      />

      <el-alert
        class="draft-note"
        type="info"
        :closable="false"
        title="保存后可在卡片列表中完成评审、来源确认和发布。"
      />
    </el-form>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button
        type="primary"
        :loading="busy"
        :disabled="uploading || !form.title.trim() || !form.content.trim()"
        @click="save"
      >
        {{ card ? '保存新修订' : '创建草稿' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.editor-intro {
  margin: -6px 0 16px;
  padding-left: 11px;
  color: #5f707c;
  border-left: 3px solid #2f6f94;
  font-size: 14px;
  line-height: 1.55;
}
.knowledge-card-form {
  display: grid;
  max-height: calc(94vh - 176px);
  gap: 14px;
  padding: 2px 8px 2px 0;
  overflow: auto;
  overscroll-behavior: contain;
}
.draft-note { margin-bottom: 2px; }
</style>
