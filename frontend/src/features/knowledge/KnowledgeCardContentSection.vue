<script setup lang="ts">
import { Upload } from '@element-plus/icons-vue';
import type { KnowledgeAttachment, KnowledgeKind } from '@/api/intelligence';
import KnowledgeAttachmentList from './KnowledgeAttachmentList.vue';
import KnowledgeMarkdownEditor from './KnowledgeMarkdownEditor.vue';

defineProps<{
  repositoryId: string;
  attachments: KnowledgeAttachment[];
  uploading: boolean;
}>();

const emit = defineEmits<{
  chooseFiles: [event: Event];
  removeAttachment: [id: string];
  insertAttachment: [attachment: KnowledgeAttachment];
}>();

const title = defineModel<string>('title', { required: true });
const knowledgeKind = defineModel<KnowledgeKind>('knowledgeKind', { required: true });
const content = defineModel<string>('content', { required: true });
const tags = defineModel<string[]>('tags', { required: true });

const kindOptions: { value: KnowledgeKind; label: string }[] = [
  { value: 'REFERENCE', label: '参考资料' },
  { value: 'BUSINESS_RULE', label: '业务规则' },
  { value: 'ARCH_DECISION', label: '架构决策' },
  { value: 'API_CONTRACT', label: '接口契约' },
  { value: 'DATA_CONSTRAINT', label: '数据约束' },
  { value: 'TEST_OBLIGATION', label: '测试义务' },
  { value: 'SECURITY_POLICY', label: '安全策略' },
  { value: 'RUNBOOK', label: '运行手册' },
  { value: 'INCIDENT_LESSON', label: '事故经验' },
  { value: 'OWNERSHIP', label: '责任归属' },
  { value: 'TECH_DEBT', label: '技术债' },
];
</script>

<template>
  <section class="editor-section content-section">
    <header class="section-heading">
      <div>
        <h3>知识内容</h3>
        <p>写清楚规则、背景或决策，让开发者可以通过业务术语找到它。</p>
      </div>
    </header>

    <div class="title-grid">
      <el-form-item label="标题" required>
        <el-input v-model="title" maxlength="200" show-word-limit placeholder="一句话说明这条知识" />
      </el-form-item>
      <el-form-item label="知识类型">
        <el-select v-model="knowledgeKind" class="full-width" filterable>
          <el-option
            v-for="option in kindOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </el-form-item>
    </div>

    <el-form-item label="知识正文（Markdown）" required>
      <KnowledgeMarkdownEditor v-model="content" :repository-id="repositoryId" class="full-width" />
    </el-form-item>

    <el-form-item label="标签">
      <el-select
        v-model="tags"
        class="full-width"
        multiple
        filterable
        allow-create
        default-first-option
        placeholder="输入业务术语后按回车"
      />
    </el-form-item>

    <el-form-item label="附件（可选）" class="attachment-field">
      <div class="upload-area">
        <label class="upload-button" :class="{ disabled: uploading }">
          <el-icon><Upload /></el-icon>
          {{ uploading ? '上传中…' : '选择文件' }}
          <input type="file" multiple :disabled="uploading" @change="emit('chooseFiles', $event)" />
        </label>
        <span>图片最大 10 MiB；文档最大 50 MiB；每个修订最多 20 个。</span>
      </div>
      <KnowledgeAttachmentList
        :items="attachments"
        :repository-id="repositoryId"
        removable
        @remove="emit('removeAttachment', $event)"
        @insert="emit('insertAttachment', $event)"
      />
    </el-form-item>
  </section>
</template>

<style scoped>
.editor-section {
  padding: 18px;
  border: 1px solid #d9e2e8;
  border-radius: 8px;
  background: #fff;
}
.section-heading { margin-bottom: 16px; }
.section-heading h3 { margin: 0; color: #25313c; font-size: 17px; }
.section-heading p { margin: 4px 0 0; color: #6d7d89; font-size: 14px; line-height: 1.55; }
.title-grid { display: grid; grid-template-columns: minmax(0, 2fr) minmax(220px, 1fr); gap: 14px; }
.full-width { width: 100%; }
.attachment-field { margin-bottom: 0; }
.attachment-field :deep(.el-form-item__content) { display: grid; gap: 10px; }
.upload-area { display: flex; align-items: center; gap: 12px; }
.upload-area > span { color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.5; }
.upload-button {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 6px;
  padding: 7px 13px;
  color: #2f6f94;
  border: 1px solid #2f6f94;
  border-radius: 6px;
  cursor: pointer;
}
.upload-button.disabled { cursor: wait; opacity: .65; }
.upload-button input { display: none; }
@media (max-width: 760px) {
  .editor-section { padding: 14px; }
  .title-grid { grid-template-columns: 1fr; gap: 0; }
  .upload-area { align-items: flex-start; flex-direction: column; }
}
</style>
