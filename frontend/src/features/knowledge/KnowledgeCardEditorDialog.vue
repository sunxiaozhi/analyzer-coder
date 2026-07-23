<script setup lang="ts">
import { Upload } from '@element-plus/icons-vue';
import { reactive, shallowRef, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { intelligenceApi, type CardInput, type KnowledgeAttachment, type KnowledgeCard } from '@/api/intelligence';
import KnowledgeAttachmentList from './KnowledgeAttachmentList.vue';
import KnowledgeMarkdownEditor from './KnowledgeMarkdownEditor.vue';

const props = defineProps<{
  modelValue: boolean; repositoryId: string; card: KnowledgeCard | null; busy: boolean;
}>();
const emit = defineEmits<{
  'update:modelValue': [value: boolean]; submit: [value: CardInput];
}>();
const form = reactive<CardInput>({
  title: '', cardType: '业务规则', content: '', tags: [], status: 'DRAFT', attachmentIds: [],
});
const tagText = shallowRef('');
const items = shallowRef<KnowledgeAttachment[]>([]);
const uploading = shallowRef(false);

watch(() => [props.modelValue, props.card] as const, () => {
  if (!props.modelValue) return;
  Object.assign(form, props.card ? {
    title: props.card.title, cardType: props.card.cardType, content: props.card.content,
    tags: [...props.card.tags], status: props.card.status,
    attachmentIds: props.card.attachments.map(item => item.id),
  } : { title: '', cardType: '业务规则', content: '', tags: [], status: 'DRAFT', attachmentIds: [] });
  tagText.value = form.tags.join(', ');
  items.value = props.card ? [...props.card.attachments] : [];
}, { immediate: true });

async function choose(event: Event) {
  const input = event.target as HTMLInputElement;
  const files = Array.from(input.files ?? []);
  if (!files.length) return;
  uploading.value = true;
  try {
    for (const file of files) {
      const attachment = await intelligenceApi.uploadAttachment(props.repositoryId, file);
      items.value = [...items.value, attachment];
    }
    ElMessage.success(`已上传 ${files.length} 个附件`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '附件上传失败');
  } finally {
    uploading.value = false;
    input.value = '';
  }
}
function remove(id: string) { items.value = items.value.filter(item => item.id !== id); }
function insert(item: KnowledgeAttachment) {
  const alt = item.originalName.replace(/\.[^.]+$/, '');
  form.content += `${form.content.endsWith('\n') || !form.content ? '' : '\n'}![${alt}](knowledge-attachment://${item.id})\n`;
}
function save() {
  form.tags = tagText.value.split(',').map(value => value.trim()).filter(Boolean);
  form.attachmentIds = items.value.map(item => item.id);
  emit('submit', { ...form, tags: [...form.tags], attachmentIds: [...form.attachmentIds] });
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="card ? '编辑知识卡片' : '新建知识卡片'" width="860"
             destroy-on-close @update:model-value="emit('update:modelValue', $event)">
    <el-form label-position="top">
      <div class="form-grid">
        <el-form-item label="标题" required><el-input v-model="form.title" maxlength="120" show-word-limit /></el-form-item>
        <el-form-item label="类型"><el-select v-model="form.cardType" style="width:100%"><el-option v-for="value in ['业务规则','技术决策','接口约定','模块说明']" :key="value" :label="value" :value="value" /></el-select></el-form-item>
      </div>
      <el-form-item label="知识正文（Markdown）" required>
        <KnowledgeMarkdownEditor v-model="form.content" :repository-id="repositoryId" class="full-width" />
      </el-form-item>
      <el-form-item label="图片与附件">
        <div class="upload-area">
          <label class="upload-button"><el-icon><Upload /></el-icon>{{ uploading ? '上传中…' : '选择文件' }}<input type="file" multiple :disabled="uploading" @change="choose" /></label>
          <span>图片最大 10 MiB；文档最大 50 MiB；每个修订最多 20 个、合计 200 MiB</span>
        </div>
        <KnowledgeAttachmentList :items="items" :repository-id="repositoryId" removable
                                 @remove="remove" @insert="insert" />
      </el-form-item>
      <div class="form-grid">
        <el-form-item label="标签（逗号分隔）"><el-input v-model="tagText" /></el-form-item>
        <el-form-item label="状态"><el-select v-model="form.status" style="width:100%"><el-option label="草稿" value="DRAFT" /><el-option label="已发布" value="PUBLISHED" /><el-option label="需要复核" value="NEEDS_REVIEW" /><el-option label="已归档" value="ARCHIVED" /></el-select></el-form-item>
      </div>
    </el-form>
    <template #footer><el-button @click="emit('update:modelValue', false)">取消</el-button><el-button type="primary" :loading="busy" :disabled="uploading || !form.title.trim() || !form.content.trim()" @click="save">{{ card ? '保存新修订' : '创建' }}</el-button></template>
  </el-dialog>
</template>

<style scoped>
.form-grid{display:grid;grid-template-columns:2fr 1fr;gap:16px}.full-width,.upload-area{width:100%}.upload-area{display:flex;align-items:center;gap:12px;margin-bottom:10px}.upload-area>span{font-size:12px;color:var(--el-text-color-secondary)}.upload-button{display:inline-flex;align-items:center;gap:6px;padding:7px 13px;border:1px solid var(--el-color-primary);border-radius:8px;color:var(--el-color-primary);cursor:pointer}.upload-button input{display:none}
</style>
