<script setup lang="ts">
import { computed } from 'vue';
import {
  CircleCheckFilled,
  Connection,
  DataAnalysis,
  Document,
  Loading,
  Refresh,
  WarningFilled,
} from '@element-plus/icons-vue';
import type { RepositoryPreparation, PreparationStageState } from '@/api/repositories';
import type { Repository } from '@/types/api';

const visible = defineModel<boolean>({ required: true });
const props = defineProps<{
  repository: Repository | null;
  preparation: RepositoryPreparation | null;
  loading: boolean;
  running: boolean;
}>();
const emit = defineEmits<{
  prepare: [];
  openAsk: [];
  openSearch: [];
  openGraph: [];
}>();

const canPrepare = computed(() => props.repository?.capabilities.canIndex ?? false);
const maxLanguageCount = computed(() => Math.max(1, ...(props.preparation?.profile.languages.map(item => item.count) ?? [])));

function stageIcon(state: PreparationStageState) {
  if (state === 'READY') return CircleCheckFilled;
  if (state === 'RUNNING') return Loading;
  if (state === 'FAILED' || state === 'DEGRADED') return WarningFilled;
  return Document;
}

function stateLabel(state: RepositoryPreparation['state']) {
  return {
    READY: '已就绪',
    DEGRADED: '降级可用',
    PROCESSING: '准备中',
    ACTION_REQUIRED: '需要处理',
    NOT_READY: '尚未准备',
  }[state];
}

function stateType(state: RepositoryPreparation['state']) {
  if (state === 'READY') return 'success';
  if (state === 'PROCESSING') return 'primary';
  if (state === 'DEGRADED' || state === 'ACTION_REQUIRED') return 'warning';
  return 'info';
}

function bytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`;
  return `${(value / 1024 / 1024).toFixed(1)} MiB`;
}
</script>

<template>
  <el-drawer v-model="visible" size="min(760px, 96vw)" class="preparation-drawer" destroy-on-close>
    <template #header>
      <div class="drawer-title">
        <span class="drawer-eyebrow">PROJECT READINESS</span>
        <div>
          <h2>{{ repository?.name ?? '项目画像' }}</h2>
          <el-tag v-if="preparation" :type="stateType(preparation.state)" effect="plain">
            {{ stateLabel(preparation.state) }}
          </el-tag>
        </div>
      </div>
    </template>

    <div v-loading="loading" class="preparation-content">
      <template v-if="preparation">
        <section class="readiness-hero">
          <div class="readiness-copy">
            <span>准备进度 {{ preparation.progress }}%</span>
            <h3>{{ preparation.message }}</h3>
          </div>
          <el-button
            v-if="canPrepare"
            type="primary"
            :icon="Refresh"
            :loading="running"
            @click="emit('prepare')"
          >
            {{ preparation.state === 'NOT_READY' ? '开始准备' : preparation.state === 'READY' ? '检查更新' : '继续准备' }}
          </el-button>
        </section>

        <section class="preparation-track" aria-label="项目准备轨道">
          <article v-for="stage in preparation.stages" :key="stage.key" :class="['track-stage', `is-${stage.state.toLowerCase()}`]">
            <div class="stage-marker">
              <el-icon :class="{ 'is-spinning': stage.state === 'RUNNING' }"><component :is="stageIcon(stage.state)" /></el-icon>
            </div>
            <div>
              <b>{{ stage.label }}</b>
              <span>{{ stage.detail }}</span>
            </div>
          </article>
        </section>

        <section class="profile-metrics" aria-label="项目画像摘要">
          <article><Document /><b>{{ preparation.profile.fileCount }}</b><span>快照文件</span><small>{{ bytes(preparation.profile.totalBytes) }}</small></article>
          <article><DataAnalysis /><b>{{ preparation.profile.chunkCount }}</b><span>代码片段</span><small>{{ preparation.profile.vectorizedChunks }} 个可语义检索</small></article>
          <article><Connection /><b>{{ preparation.profile.graphNodes }}</b><span>图谱节点</span><small>{{ preparation.profile.graphEdges }} 条关系</small></article>
          <article><CircleCheckFilled /><b>{{ preparation.profile.knowledgeCards }}</b><span>有效知识</span><small>{{ preparation.profile.missingChunks }} 个向量缺失</small></article>
        </section>

        <div class="profile-grid">
          <section class="profile-panel language-panel">
            <header><h3>语言构成</h3><span>按文件数量</span></header>
            <div v-if="preparation.profile.languages.length" class="language-list">
              <div v-for="item in preparation.profile.languages" :key="item.name" class="language-row">
                <span>{{ item.name }}</span>
                <div><i :style="{ width: `${Math.max(8, item.count / maxLanguageCount * 100)}%` }" /></div>
                <b>{{ item.count }}</b>
              </div>
            </div>
            <p v-else class="empty-copy">当前快照没有可归类的文本文件。</p>
          </section>

          <section class="profile-panel module-panel">
            <header><h3>一级目录</h3><span>按文件数量</span></header>
            <div v-if="preparation.profile.modules.length" class="module-list">
              <div v-for="item in preparation.profile.modules" :key="item.name">
                <code>{{ item.name }}/</code><span>{{ item.count }} 个文件</span>
              </div>
            </div>
            <p v-else class="empty-copy">文件均位于仓库根目录。</p>
          </section>

          <section class="profile-panel entry-panel">
            <header><h3>关键入口</h3><span>从约定文件名识别</span></header>
            <div v-if="preparation.profile.entryPoints.length" class="entry-list">
              <code v-for="path in preparation.profile.entryPoints" :key="path">{{ path }}</code>
            </div>
            <p v-else class="empty-copy">尚未识别到 main、app、index 或构建入口。</p>
          </section>
        </div>

        <section class="profile-actions">
          <div><b>从这里开始理解项目</b><span>所有入口均绑定当前已发布快照。</span></div>
          <el-button @click="emit('openSearch')">浏览源码</el-button>
          <el-button :disabled="preparation.profile.graphNodes === 0" @click="emit('openGraph')">查看图谱</el-button>
          <el-button type="primary" :disabled="preparation.profile.chunkCount === 0" @click="emit('openAsk')">开始问答</el-button>
        </section>
      </template>

      <el-empty v-else description="项目画像暂时无法加载" />
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer-title { display: grid; gap: 5px; }
.drawer-title > div { display: flex; align-items: center; gap: 10px; }
.drawer-title h2 { margin: 0; color: #17344c; font-size: 20px; }
.drawer-eyebrow { color: #6f8295; font: 600 10px/1.2 "SFMono-Regular", Consolas, monospace; letter-spacing: .12em; }
.preparation-content { display: grid; gap: 18px; min-height: 360px; padding: 0 2px 24px; }
.readiness-hero { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 18px 20px; color: #fff; background: #17344c; border-radius: 8px; }
.readiness-copy { display: grid; gap: 6px; }
.readiness-copy span { color: #b9d3e8; font: 11px "SFMono-Regular", Consolas, monospace; }
.readiness-copy h3 { margin: 0; font-size: 16px; font-weight: 600; }
.preparation-track { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border: 1px solid #dce2e8; border-radius: 8px; overflow: hidden; }
.track-stage { position: relative; display: grid; grid-template-columns: 30px minmax(0, 1fr); gap: 9px; min-height: 92px; padding: 16px 12px; background: #fff; border-right: 1px solid #e5e9ed; }
.track-stage:last-child { border-right: 0; }
.track-stage::after { position: absolute; right: -5px; top: 27px; z-index: 1; width: 9px; height: 9px; content: ''; background: #fff; border-top: 1px solid #dce2e8; border-right: 1px solid #dce2e8; transform: rotate(45deg); }
.track-stage:last-child::after { display: none; }
.stage-marker { display: grid; width: 28px; height: 28px; place-items: center; color: #8b96a1; background: #f1f3f5; border-radius: 50%; }
.track-stage > div:last-child { display: grid; align-content: start; gap: 5px; min-width: 0; }
.track-stage b { font-size: 12px; }
.track-stage span { color: #77818b; font-size: 10px; line-height: 1.45; overflow-wrap: anywhere; }
.is-ready .stage-marker { color: #16855b; background: #e8f6f0; }
.is-running .stage-marker { color: #0066cc; background: #e9f3fd; }
.is-failed .stage-marker, .is-degraded .stage-marker { color: #a85b00; background: #fff2df; }
.is-spinning { animation: spin 1.1s linear infinite; }
.profile-metrics { display: grid; grid-template-columns: repeat(4, 1fr); border: 1px solid #dce2e8; border-radius: 8px; }
.profile-metrics article { position: relative; display: grid; gap: 2px; padding: 15px 16px 14px 42px; border-right: 1px solid #e5e9ed; }
.profile-metrics article:last-child { border-right: 0; }
.profile-metrics svg { position: absolute; left: 15px; top: 18px; width: 17px; color: #54718b; }
.profile-metrics b { color: #1f2d3a; font-size: 18px; }
.profile-metrics span { color: #4f5d69; font-size: 11px; }
.profile-metrics small { color: #8a949d; font-size: 9px; }
.profile-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.profile-panel { min-width: 0; padding: 16px; border: 1px solid #dce2e8; border-radius: 8px; }
.profile-panel header { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 14px; }
.profile-panel h3 { margin: 0; color: #2b3945; font-size: 13px; }
.profile-panel header span { color: #8a949d; font-size: 9px; }
.entry-panel { grid-column: 1 / -1; }
.language-list, .module-list, .entry-list { display: grid; gap: 9px; }
.language-row { display: grid; grid-template-columns: 82px minmax(0, 1fr) 30px; align-items: center; gap: 9px; font-size: 11px; }
.language-row > div { height: 5px; overflow: hidden; background: #edf0f3; border-radius: 4px; }
.language-row i { display: block; height: 100%; background: #3c82bd; border-radius: inherit; }
.language-row b { text-align: right; font: 600 10px "SFMono-Regular", Consolas, monospace; }
.module-list > div { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 7px; border-bottom: 1px solid #eff1f3; }
.module-list code, .entry-list code { min-width: 0; overflow: hidden; color: #31536d; font: 11px "SFMono-Regular", Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.module-list span { color: #8a949d; font-size: 10px; white-space: nowrap; }
.entry-list { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.entry-list code { padding: 8px 10px; background: #f5f8fa; border-left: 2px solid #3c82bd; }
.empty-copy { margin: 0; color: #8a949d; font-size: 11px; }
.profile-actions { display: flex; align-items: center; gap: 9px; padding: 14px 16px; background: #f6f8fa; border-radius: 8px; }
.profile-actions > div { display: grid; gap: 3px; margin-right: auto; }
.profile-actions b { font-size: 12px; }
.profile-actions span { color: #7b858e; font-size: 10px; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .is-spinning { animation: none; } }
@media (max-width: 640px) {
  .readiness-hero, .profile-actions { align-items: stretch; flex-direction: column; }
  .preparation-track, .profile-metrics, .profile-grid { grid-template-columns: 1fr 1fr; }
  .track-stage:nth-child(2) { border-right: 0; }
  .track-stage::after { display: none; }
  .profile-metrics article:nth-child(2) { border-right: 0; }
  .profile-metrics article:nth-child(-n+2) { border-bottom: 1px solid #e5e9ed; }
  .entry-panel { grid-column: auto; }
  .entry-list { grid-template-columns: 1fr; }
}
</style>
