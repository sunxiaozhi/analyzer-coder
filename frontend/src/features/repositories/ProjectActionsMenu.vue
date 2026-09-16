<script setup lang="ts">
import { MoreHorizontal, ChevronDown } from 'lucide-vue-next';
import type { Repository } from '@/types/api';
const props = withDefaults(defineProps<{ repository: Repository; compact?: boolean; disabled?: boolean }>(), { compact: false, disabled: false });
const emit = defineEmits<{ settings: []; govern: []; remove: [] }>();
function command(value: string) {
  if (value === 'settings') emit('settings');
  else if (value === 'govern' && (props.repository.capabilities.canGrant || props.repository.capabilities.canTransferOwnership)) emit('govern');
  else if (value === 'remove' && props.repository.capabilities.canDelete) emit('remove');
}
</script>
<template>
  <el-dropdown trigger="click" :disabled="disabled" @command="command">
    <button type="button" class="project-menu-trigger" :class="{ compact }" :disabled="disabled" :aria-label="repository.name + '的项目操作'">
      <MoreHorizontal v-if="compact" :size="19" /><template v-else>项目操作<ChevronDown :size="15" /></template>
    </button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item command="settings">项目设置</el-dropdown-item>
        <el-dropdown-item v-if="repository.capabilities.canGrant || repository.capabilities.canTransferOwnership" command="govern">成员与权限</el-dropdown-item>
        <el-dropdown-item v-if="repository.capabilities.canDelete" command="remove" divided class="delete-command">删除项目</el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>
<style scoped>
.project-menu-trigger { display: inline-flex; align-items: center; justify-content: center; gap: 7px; min-height: 36px; padding: 0 13px; border: 1px solid var(--app-border); border-radius: 7px; background: var(--app-surface); color: var(--app-text-regular); font: inherit; font-size: 13px; white-space: nowrap; }
.project-menu-trigger:hover { border-color: var(--app-border-strong); color: var(--app-color-action); background: var(--app-surface-subtle); }
.project-menu-trigger.compact { width: 32px; min-height: 32px; padding: 0; border-color: transparent; background: transparent; }
.project-menu-trigger:disabled { opacity: .5; cursor: wait; }
.delete-command { color: var(--app-color-danger); }
</style>
