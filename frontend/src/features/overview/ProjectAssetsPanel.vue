<script setup lang="ts">
import { computed } from 'vue';
import { BookOpenText, Braces, CheckSquare, FileKey2, Settings2 } from 'lucide-vue-next';
import type {
  PreparationStage,
  ProjectKeyAsset,
  ProjectProfileCount,
} from '@/api/repositories';
import type { RepositoryAssetType } from '@/types/api';

interface Props {
  assets: ProjectProfileCount[];
  keyAssets: ProjectKeyAsset[];
  stages: PreparationStage[];
}

const props = defineProps<Props>();
const emit = defineEmits<{ openAsset: [path: string] }>();

const assetMeta: Record<RepositoryAssetType, { label: string; description: string; icon: typeof Braces }> = {
  CODE: { label: '代码', description: '实现事实', icon: Braces },
  DOCUMENT: { label: '文档', description: '设计与说明', icon: BookOpenText },
  RULE: { label: '规则', description: 'Agent 与团队约束', icon: FileKey2 },
  TASK: { label: '任务', description: '目标与验收门禁', icon: CheckSquare },
  CONFIG: { label: '配置', description: '运行与交付边界', icon: Settings2 },
};

const assetRows = computed(() => {
  const counts = new Map(props.assets.map(item => [item.name as RepositoryAssetType, item.count]));
  return (Object.keys(assetMeta) as RepositoryAssetType[]).map(type => ({
    type,
    count: counts.get(type) ?? 0,
    ...assetMeta[type],
  }));
});

function stageClass(state: PreparationStage['state']) {
  return state.toLowerCase();
}
</script>

<template>
  <section class="assets-panel">
    <header class="panel-head">
      <div>
        <span class="eyebrow">PROJECT ASSETS</span>
        <h2>项目资产</h2>
      </div>
      <span>代码不是唯一事实来源</span>
    </header>

    <div class="asset-ledger">
      <article v-for="asset in assetRows" :key="asset.type" :data-type="asset.type">
        <component :is="asset.icon" :size="15" />
        <div><strong>{{ asset.label }}</strong><small>{{ asset.description }}</small></div>
        <b>{{ asset.count }}</b>
      </article>
    </div>

    <div class="key-assets">
      <h3>优先阅读</h3>
      <div v-if="keyAssets.length" class="key-asset-list">
        <button v-for="asset in keyAssets" :key="asset.path" type="button" @click="emit('openAsset', asset.path)">
          <span :data-type="asset.assetType">{{ assetMeta[asset.assetType].label }}</span>
          <strong>{{ asset.path }}</strong>
        </button>
      </div>
      <el-empty v-else :image-size="42" description="未发现 README、规则或任务资产" />
    </div>

    <footer class="readiness">
      <div v-for="stage in stages" :key="stage.key">
        <i :class="stageClass(stage.state)"></i>
        <span>{{ stage.label }}</span>
        <small>{{ stage.state === 'READY' ? '就绪' : stage.state === 'RUNNING' ? '进行中' : '待处理' }}</small>
      </div>
    </footer>
  </section>
</template>

<style scoped>
.assets-panel { min-width: 0; overflow: hidden; border: 1px solid #d9e1e8; border-radius: 8px; background: #fff; }
.panel-head { display: flex; min-height: 70px; align-items: center; justify-content: space-between; gap: 12px; padding: 13px 16px; border-bottom: 1px solid #e5eaef; }
.eyebrow { color: #8a5b23; font: 700 9px/1.2 Consolas, monospace; letter-spacing: .13em; }
.panel-head h2 { margin: 5px 0 0; color: #23313d; font-size: 15px; }
.panel-head > span { color: #7b8792; font-size: 10px; }
.asset-ledger { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); border-bottom: 1px solid #e5eaef; }
.asset-ledger article { display: grid; grid-template-columns: 24px minmax(0, 1fr) auto; align-items: center; gap: 7px; min-height: 68px; padding: 10px; border-right: 1px solid #edf0f3; }
.asset-ledger article:last-child { border-right: 0; }
.asset-ledger svg { color: #617482; }
.asset-ledger div { display: grid; gap: 2px; }
.asset-ledger strong { color: #344653; font-size: 11px; }
.asset-ledger small { color: #89939b; font-size: 8px; }
.asset-ledger b { color: #263846; font: 700 16px Consolas, monospace; }
.asset-ledger article[data-type='RULE'] { box-shadow: inset 0 -2px #8a5b23; }
.asset-ledger article[data-type='TASK'] { box-shadow: inset 0 -2px #7c6398; }
.key-assets { min-height: 190px; padding: 15px 16px; }
.key-assets h3 { margin: 0 0 10px; color: #40515d; font-size: 11px; }
.key-asset-list { display: grid; gap: 6px; }
.key-asset-list button { display: grid; grid-template-columns: 46px minmax(0, 1fr); align-items: center; gap: 8px; min-height: 34px; padding: 6px 8px; text-align: left; border: 1px solid transparent; border-radius: 4px; background: #f7f9fa; }
.key-asset-list button:hover { border-color: #bfd4e4; background: #f1f7fb; }
.key-asset-list span { padding: 3px 4px; color: #536571; text-align: center; border-radius: 3px; background: #e8edf0; font-size: 8px; }
.key-asset-list span[data-type='RULE'] { color: #79501f; background: #f4e9d9; }
.key-asset-list span[data-type='TASK'] { color: #654e7c; background: #eee8f4; }
.key-asset-list strong { overflow: hidden; color: #40515d; font: 500 10px Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.readiness { display: grid; grid-template-columns: repeat(4, 1fr); border-top: 1px solid #e5eaef; background: #fafbfc; }
.readiness div { display: grid; grid-template-columns: 8px 1fr; gap: 2px 6px; padding: 10px; border-right: 1px solid #edf0f3; }
.readiness div:last-child { border-right: 0; }
.readiness i { width: 7px; height: 7px; margin-top: 3px; border-radius: 50%; background: #aab3ba; }
.readiness i.ready { background: #16855b; }
.readiness i.running { background: #2a74b8; box-shadow: 0 0 0 3px rgb(42 116 184 / 12%); }
.readiness i.failed { background: #c23e3e; }
.readiness span { color: #53616c; font-size: 9px; }
.readiness small { grid-column: 2; color: #9099a0; font-size: 8px; }
@media (max-width: 860px) {
  .asset-ledger { grid-template-columns: repeat(2, 1fr); }
  .asset-ledger article { border-bottom: 1px solid #edf0f3; }
  .readiness { grid-template-columns: repeat(2, 1fr); }
}
</style>
