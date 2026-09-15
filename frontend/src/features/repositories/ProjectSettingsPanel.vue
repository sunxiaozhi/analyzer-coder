<script setup lang="ts">
import type { Repository } from '@/types/api';
defineProps<{ repository: Repository }>();
const emit = defineEmits<{ edit: []; remove: [] }>();
</script>
<template>
  <section class="project-settings">
    <p class="scope-note">项目统一管理代码来源、资料和权限。同步代码及索引请在分支列表中操作。</p>
    <dl class="settings-list">
      <dt>项目</dt><dd>{{ repository.name }}</dd>
      <dt>代码来源</dt><dd>{{ repository.sourceType }}</dd>
      <dt>服务端路径</dt><dd class="source-path">{{ repository.path }}</dd>
      <dt>默认分支</dt><dd>{{ repository.branch || '单版本代码源' }}<small>仅作为首次进入项目时的阅读偏好</small></dd>
      <dt>所有者</dt><dd>{{ repository.ownerDisplayName }}</dd>
    </dl>
    <el-button v-if="repository.capabilities.canEditRepository ?? repository.capabilities.canConfigure" type="primary" @click="emit('edit')">编辑资料与凭据</el-button>
    <div v-if="repository.capabilities.canDelete" class="danger-zone">
      <div><strong>删除项目</strong><p>删除平台中的项目、分支及派生数据；不修改源仓库。</p></div>
      <el-button type="danger" plain @click="emit('remove')">删除项目</el-button>
    </div>
  </section>
</template>
<style scoped>
.scope-note{color:#68778a;font-size:13px;line-height:1.7}
.settings-list{display:grid;grid-template-columns:100px minmax(0,1fr);gap:14px;color:#334155}
.settings-list dt{color:#68778a}.settings-list dd{margin:0;overflow-wrap:anywhere}
.settings-list small{display:block;color:#68778a;font-size:12px;margin-top:4px}
.source-path{font-family:Consolas,monospace;font-size:12px}
.danger-zone{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-top:28px;padding-top:20px;border-top:1px solid #ead0d0}
.danger-zone p{font-size:12px;color:#68778a}
</style>
