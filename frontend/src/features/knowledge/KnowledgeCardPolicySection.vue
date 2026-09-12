<script setup lang="ts">
import { computed } from 'vue';
import type { KnowledgeEnforcement } from '@/api/intelligence';
import type { RepositoryMember } from '@/api/repositoryGovernance';
import KnowledgeAccountSelect from './KnowledgeAccountSelect.vue';

const props = defineProps<{
  members: RepositoryMember[];
  membersLoading: boolean;
  currentAccountAvailable: boolean;
  scopeReady: boolean;
}>();
const emit = defineEmits<{ useCurrentAccount: [] }>();

const enforcement = defineModel<KnowledgeEnforcement>('enforcement', { required: true });
const ownerAccountId = defineModel<string | null>('ownerAccountId', { required: true });

const enforcementOptions: { value: KnowledgeEnforcement; label: string; hint: string }[] = [
  { value: 'REFERENCE', label: '参考', hint: '作为理解项目的背景信息' },
  { value: 'ADVISORY', label: '重点提醒', hint: '命中相关代码时提醒开发者关注' },
  { value: 'REQUIRED', label: '强约束', hint: '发布前需要负责人、适用范围和当前代码证据' },
];
const selectedHint = computed(() => enforcementOptions.find(option => option.value === enforcement.value)?.hint ?? '');
const requiredIncomplete = computed(() => enforcement.value === 'REQUIRED'
  && (!ownerAccountId.value || !props.scopeReady));
</script>

<template>
  <section class="editor-section policy-section">
    <header class="section-heading">
      <div>
        <h3>使用级别</h3>
        <p>说明开发者在相关代码旁看到这条知识时，应当如何对待它。</p>
      </div>
    </header>

    <el-form-item label="使用级别">
      <el-segmented v-model="enforcement" :options="enforcementOptions" block />
      <small class="level-hint">{{ selectedHint }}</small>
    </el-form-item>

    <div v-if="enforcement === 'REQUIRED'" class="owner-area">
      <el-form-item label="负责人">
        <div class="owner-control">
          <KnowledgeAccountSelect
            v-model="ownerAccountId"
            :members="members"
            :loading="membersLoading"
            placeholder="选择负责维护这条知识的成员"
          />
          <el-button :disabled="!currentAccountAvailable" @click="emit('useCurrentAccount')">设为我</el-button>
        </div>
      </el-form-item>
      <el-alert
        v-if="requiredIncomplete"
        type="warning"
        :closable="false"
        title="草稿可以保存；发布强约束知识前，需要负责人和至少一种路径或符号范围。"
      />
    </div>
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
.level-hint { display: block; margin-top: 6px; color: #6f7d87; line-height: 1.45; }
.owner-area { display: grid; gap: 10px; margin-top: 16px; padding-top: 16px; border-top: 1px solid #e2e8ec; }
.owner-control { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; width: 100%; }
@media (max-width: 760px) {
  .editor-section { padding: 14px; }
  .owner-control { grid-template-columns: 1fr; }
}
</style>
