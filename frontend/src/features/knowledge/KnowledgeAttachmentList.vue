<script setup lang="ts">
import { Delete, Document, Picture } from '@element-plus/icons-vue';
import type { KnowledgeAttachment } from '@/api/intelligence';
import { scanStatusLabel } from '@/utils/displayLabels';
defineProps<{ items: KnowledgeAttachment[]; repositoryId: string; removable?: boolean }>();
const emit = defineEmits<{ remove: [id: string]; insert: [attachment: KnowledgeAttachment] }>();
const isImage = (item: KnowledgeAttachment) => item.mediaType.startsWith('image/');
const formatSize = (bytes: number) => bytes < 1024 * 1024 ? `${Math.ceil(bytes / 1024)} KiB` : `${(bytes / 1024 / 1024).toFixed(1)} MiB`;
</script>

<template>
  <div v-if="items.length" class="attachment-list">
    <div v-for="item in items" :key="item.id" class="attachment-row">
      <el-icon><Picture v-if="isImage(item)" /><Document v-else /></el-icon>
      <div><b>{{ item.originalName }}</b><span>{{ formatSize(item.sizeBytes) }} · {{ scanStatusLabel(item.scanStatus) }}</span></div>
      <el-button v-if="isImage(item)" link type="primary" @click="emit('insert', item)">插入正文</el-button>
      <a :href="`/api/repositories/${repositoryId}/knowledge/attachments/${item.id}`" target="_blank">下载</a>
      <el-button v-if="removable" link type="danger" :icon="Delete" @click="emit('remove', item.id)" />
    </div>
  </div>
</template>

<style scoped>
.attachment-list{display:grid;gap:8px}.attachment-row{display:flex;align-items:center;gap:10px;padding:9px 11px;border:1px solid var(--el-border-color-lighter);border-radius:9px;background:var(--el-fill-color-extra-light)}
.attachment-row>div{display:grid;min-width:0;flex:1}.attachment-row b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:15px}.attachment-row span{font-size:14px;color:var(--el-text-color-secondary)}.attachment-row a{font-size:15px;color:var(--el-color-primary);text-decoration:none}
</style>
