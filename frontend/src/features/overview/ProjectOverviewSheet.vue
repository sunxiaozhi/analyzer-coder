<script setup lang="ts">
import { computed } from 'vue';
import {
  AlertCircle,
  BookOpenCheck,
  Braces,
  Check,
  CircleDot,
  Database,
  Files,
  FolderTree,
  Languages,
  Network,
  PackageOpen,
  RefreshCw,
  Signpost,
} from 'lucide-vue-next';
import type { ProjectCodeFacts, ProjectProfile, RepositoryPreparation } from '@/api/repositories';
import type { Repository } from '@/types/api';

const props = defineProps<{
  repository: Repository;
  preparation: RepositoryPreparation | null;
  profile: ProjectProfile | null;
  codeFacts: ProjectCodeFacts | null;
  loading: boolean;
  preparing: boolean;
}>();

const emit = defineEmits<{
  refresh: [];
  prepare: [];
  openFile: [path: string];
}>();

const state = computed(() => props.preparation?.state ?? 'NOT_READY');
const stateCopy = computed(() => ({
  READY: { label: '已准备', tone: 'ready' },
  DEGRADED: { label: '降级可用', tone: 'warning' },
  PROCESSING: { label: '准备中', tone: 'running' },
  ACTION_REQUIRED: { label: '需要处理', tone: 'danger' },
  NOT_READY: { label: '尚未准备', tone: 'muted' },
}[state.value]));
const vectorCoverage = computed(() => {
  if (!props.profile?.chunkCount) return 0;
  return Math.round(props.profile.vectorizedChunks / props.profile.chunkCount * 100);
});
const categoryMaximum = computed(() => Math.max(1, ...(props.codeFacts?.fileCategories.map(item => item.count) ?? [])));
const languageMaximum = computed(() => Math.max(1, ...(props.profile?.languages.map(item => item.count) ?? [])));
const categories = computed(() => props.codeFacts?.fileCategories ?? []);
const technologies = computed(() => props.codeFacts?.technologies.slice(0, 16) ?? []);
const languages = computed(() => props.profile?.languages.slice(0, 10) ?? []);
const modules = computed(() => props.profile?.modules.slice(0, 10) ?? []);
const entryPoints = computed(() => props.profile?.entryPoints.slice(0, 8) ?? []);
const keyAssets = computed(() => props.profile?.keyAssets.slice(0, 8) ?? []);

function bytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`;
  return `${(value / 1024 / 1024).toFixed(1)} MiB`;
}

function width(value: number) {
  return `${Math.max(4, Math.round(value / categoryMaximum.value * 100))}%`;
}

function languageWidth(value: number) {
  return `${Math.max(6, Math.round(value / languageMaximum.value * 100))}%`;
}

function stageTone(stageState: string) {
  if (stageState === 'READY') return 'ready';
  if (stageState === 'RUNNING') return 'running';
  if (stageState === 'FAILED' || stageState === 'DEGRADED') return 'danger';
  return 'muted';
}

function prepareLabel() {
  if (state.value === 'NOT_READY') return '开始准备';
  if (state.value === 'PROCESSING') return '继续准备';
  if (state.value === 'READY') return '检查更新';
  return '重新准备';
}
</script>

<template>
  <article class="overview-board">
    <header class="project-header">
      <div class="project-title">
        <span>PROJECT OVERVIEW</span>
        <h1>{{ repository.name }}</h1>
      </div>
      <div class="header-state">
        <span class="state-pill" :data-tone="stateCopy.tone"><i></i>{{ stateCopy.label }}</span>
        <strong>{{ preparation?.progress ?? 0 }}%</strong>
      </div>
      <div class="header-actions">
        <button type="button" class="quiet-button" :disabled="loading || preparing" @click="emit('refresh')">
          <RefreshCw :size="14" :class="{ spinning: loading }" />刷新
        </button>
        <button
          v-if="repository.capabilities?.canIndex"
          type="button"
          class="primary-button"
          :disabled="loading || preparing"
          @click="emit('prepare')"
        >
          <RefreshCw :size="14" :class="{ spinning: preparing }" />{{ prepareLabel() }}
        </button>
      </div>
      <div class="snapshot-copy">
        <span>{{ profile?.fileCount ?? 0 }} 个快照文件</span>
        <i></i>
        <span>{{ bytes(profile?.totalBytes ?? 0) }}</span>
        <i></i>
        <span>{{ preparation?.message ?? '等待项目画像' }}</span>
      </div>
      <div class="readiness-progress" aria-hidden="true">
        <i :style="{ width: `${preparation?.progress ?? 0}%` }"></i>
      </div>
    </header>

    <section class="metric-grid" aria-label="项目画像指标">
      <article class="metric-card graph-card">
        <header><Network :size="16" /><span>CodeGraph</span><em :data-ready="Boolean(profile?.graphNodes)">{{ profile?.graphNodes ? '已发布' : '未构建' }}</em></header>
        <div class="metric-value"><strong>{{ profile?.graphNodes ?? 0 }}</strong><span>符号节点</span></div>
        <footer><b>{{ profile?.graphEdges ?? 0 }}</b><span>条符号关系</span></footer>
      </article>

      <article class="metric-card vector-card">
        <header><Database :size="16" /><span>向量数据</span><em>{{ profile?.retrievalCapabilityLabel ?? '未配置' }}</em></header>
        <div class="metric-value"><strong>{{ vectorCoverage }}%</strong><span>向量覆盖</span></div>
        <footer><b>{{ profile?.vectorizedChunks ?? 0 }} / {{ profile?.chunkCount ?? 0 }}</b><span>{{ profile?.missingChunks ?? 0 }} 个缺失</span></footer>
      </article>

      <article class="metric-card knowledge-card">
        <header><BookOpenCheck :size="16" /><span>知识数据</span><em>有效记录</em></header>
        <div class="metric-value"><strong>{{ profile?.knowledgeCards ?? 0 }}</strong><span>知识卡片</span></div>
        <footer><b>{{ profile?.chunkCount ?? 0 }}</b><span>个内容片段</span></footer>
      </article>

      <article class="metric-card code-card">
        <header><Files :size="16" /><span>代码快照</span><em>当前版本</em></header>
        <div class="metric-value"><strong>{{ codeFacts?.codeFileCount ?? 0 }}</strong><span>代码文件</span></div>
        <footer><b>{{ profile?.fileCount ?? 0 }}</b><span>个全部文件</span></footer>
      </article>
    </section>

    <section class="preparation-section" aria-label="项目准备状态">
      <header class="section-heading">
        <div><span>READINESS</span><h2>项目准备</h2></div>
        <p>{{ preparation?.message ?? '尚未生成项目准备状态' }}</p>
      </header>
      <div class="preparation-track">
        <article v-for="(stage, index) in preparation?.stages ?? []" :key="stage.key" :data-tone="stageTone(stage.state)">
          <div class="stage-marker">
            <span>0{{ index + 1 }}</span>
            <i>
              <Check v-if="stage.state === 'READY'" :size="14" />
              <AlertCircle v-else-if="stage.state === 'FAILED' || stage.state === 'DEGRADED'" :size="14" />
              <CircleDot v-else :size="14" />
            </i>
          </div>
          <div class="stage-copy">
            <strong>{{ stage.label }}</strong>
            <small>{{ stage.detail }}</small>
          </div>
        </article>
        <p v-if="!preparation?.stages.length" class="empty-copy">准备后将依次展示快照、内容、向量与图谱状态。</p>
      </div>
    </section>

    <section class="portrait-section" aria-label="项目画像">
      <header class="section-heading portrait-heading">
        <div><span>PROJECT PROFILE</span><h2>项目画像</h2></div>
        <p>基于当前已发布快照，不依赖 README</p>
      </header>
      <div class="portrait-grid">
      <section class="data-panel category-panel">
        <header>
          <div><h2>代码类型</h2><p>按文件职责统计</p></div>
          <strong>{{ codeFacts?.codeFileCount ?? 0 }}</strong>
        </header>
        <div v-if="categories.length" class="category-list">
          <article v-for="category in categories" :key="category.key">
            <span>{{ category.label }}</span>
            <div><i :style="{ width: width(category.count) }"></i></div>
            <b>{{ category.count }}</b>
          </article>
        </div>
        <p v-else class="empty-copy">当前快照没有可归类的代码文件。</p>
      </section>

      <section class="data-panel language-panel">
        <header>
          <div><h2>语言构成</h2><p>按快照文件数量</p></div>
          <Languages :size="17" />
        </header>
        <div v-if="languages.length" class="language-list">
          <article v-for="item in languages" :key="item.name">
            <span>{{ item.name }}</span>
            <div><i :style="{ width: languageWidth(item.count) }"></i></div>
            <b>{{ item.count }}</b>
          </article>
        </div>
        <p v-else class="empty-copy">当前快照没有可归类的文本文件。</p>
      </section>

      <section class="data-panel technology-panel">
        <header>
          <div><h2>技术栈</h2><p>源码与依赖清单归纳</p></div>
          <strong>{{ technologies.length }}</strong>
        </header>
        <div v-if="technologies.length" class="technology-list">
          <button
            v-for="technology in technologies"
            :key="technology.name"
            type="button"
            :data-category="technology.category"
            :disabled="!technology.evidencePaths.length"
            :title="technology.evidencePaths[0] ?? technology.detail"
            @click="technology.evidencePaths[0] && emit('openFile', technology.evidencePaths[0])"
          >
            <Braces :size="13" />
            <span><strong>{{ technology.name }}</strong><small>{{ technology.category }}</small></span>
            <em>{{ technology.confidence === 'HIGH' ? '已确认' : '推断' }}</em>
          </button>
        </div>
        <p v-else class="empty-copy">未从源码或依赖清单中识别到技术栈。</p>
      </section>

      <section class="data-panel structure-panel">
        <header>
          <div><h2>项目结构</h2><p>目录、入口与关键资产</p></div>
          <FolderTree :size="17" />
        </header>

        <div class="profile-block">
          <div class="profile-label"><FolderTree :size="13" /><strong>一级目录</strong><span>{{ modules.length }}</span></div>
          <div v-if="modules.length" class="module-list">
            <div v-for="item in modules" :key="item.name"><code>{{ item.name }}/</code><span>{{ item.count }} 个文件</span></div>
          </div>
          <p v-else class="empty-copy">文件均位于仓库根目录。</p>
        </div>

        <div class="profile-block">
          <div class="profile-label"><Signpost :size="13" /><strong>关键入口</strong><span>{{ entryPoints.length }}</span></div>
          <div v-if="entryPoints.length" class="path-list">
            <button v-for="path in entryPoints" :key="path" type="button" :title="path" @click="emit('openFile', path)">{{ path }}</button>
          </div>
          <p v-else class="empty-copy">尚未识别到 main、app、index 或构建入口。</p>
        </div>

        <div class="profile-block">
          <div class="profile-label"><PackageOpen :size="13" /><strong>关键资产</strong><span>{{ keyAssets.length }}</span></div>
          <div v-if="keyAssets.length" class="path-list asset-list">
            <button v-for="asset in keyAssets" :key="asset.path" type="button" :title="asset.path" @click="emit('openFile', asset.path)">
              <span>{{ asset.path }}</span><em>{{ asset.assetType }}</em>
            </button>
          </div>
          <div v-else-if="profile?.assets.length" class="asset-counts">
            <span v-for="asset in profile.assets" :key="asset.name"><b>{{ asset.count }}</b>{{ asset.name }}</span>
          </div>
          <p v-else class="empty-copy">当前快照没有识别到关键资产。</p>
        </div>
      </section>
      </div>
    </section>
  </article>
</template>

<style scoped>
.overview-board {
  --navy: #17344c;
  --navy-2: #214a69;
  --ink: #1f2d3a;
  --text: #4f5d69;
  --muted: #7b8995;
  --line: #dce3e9;
  --soft: #f3f6f8;
  --blue: #3c82bd;
  --blue-soft: #eaf3fb;
  --green: #16855b;
  --green-soft: #e8f6f0;
  --violet: #6d62a8;
  --violet-soft: #f0eef9;
  --amber: #a85b00;
  --amber-soft: #fff2df;
  display: grid;
  gap: 0;
  min-width: 0;
  overflow: hidden;
  color: var(--ink);
  border: 1px solid #d6dee5;
  border-radius: 12px;
  background: var(--soft);
  box-shadow: 0 10px 30px rgb(23 52 76 / 7%);
  font-family: Inter, "Microsoft YaHei", sans-serif;
}

button { font: inherit; cursor: pointer; }
button:disabled { cursor: not-allowed; opacity: .55; }

.project-header {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 18px;
  min-height: 148px;
  overflow: hidden;
  padding: 27px 30px 24px;
  color: #fff;
  background:
    radial-gradient(circle at 88% 20%, rgb(104 171 213 / 20%), transparent 26%),
    linear-gradient(132deg, var(--navy) 0%, #173b57 58%, var(--navy-2) 100%);
}
.project-header::after { position: absolute; right: -72px; bottom: -112px; width: 260px; height: 190px; content: ''; border: 1px solid rgb(255 255 255 / 8%); border-radius: 50%; box-shadow: 0 0 0 30px rgb(255 255 255 / 2%), 0 0 0 64px rgb(255 255 255 / 2%); pointer-events: none; }

.project-title { min-width: 0; }
.project-title > span { color: #9dc7e2; font: 650 10px "SFMono-Regular", Consolas, monospace; letter-spacing: .15em; }
.project-title h1 { overflow: hidden; margin: 9px 0 0; color: #fff; font-size: clamp(26px, 3vw, 37px); font-weight: 680; letter-spacing: -.025em; text-overflow: ellipsis; white-space: nowrap; }
.header-state { display: flex; align-items: center; gap: 10px; }
.header-state > strong { color: #fff; font: 650 21px "SFMono-Regular", Consolas, monospace; }
.state-pill { display: inline-flex; align-items: center; gap: 7px; padding: 6px 9px; color: #d3dce2; border: 1px solid rgb(255 255 255 / 14%); border-radius: 5px; background: rgb(255 255 255 / 8%); font-size: 11px; }
.state-pill i { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.state-pill[data-tone='ready'] { color: #91e0bd; border-color: rgb(145 224 189 / 25%); background: rgb(22 133 91 / 24%); }
.state-pill[data-tone='warning'] { color: #ffd08a; border-color: rgb(255 208 138 / 25%); background: rgb(168 91 0 / 24%); }
.state-pill[data-tone='running'] { color: #acd8f5; border-color: rgb(172 216 245 / 25%); background: rgb(60 130 189 / 24%); }
.state-pill[data-tone='danger'] { color: #ffc0ba; border-color: rgb(255 192 186 / 25%); background: rgb(166 72 67 / 24%); }
.header-actions { display: flex; gap: 7px; }
.header-actions button { position: relative; z-index: 1; display: inline-flex; min-height: 36px; align-items: center; gap: 6px; padding: 7px 12px; border-radius: 5px; font-size: 11px; }
.quiet-button { color: #d7e7f2; border: 1px solid rgb(255 255 255 / 22%); background: rgb(255 255 255 / 7%); }
.quiet-button:hover { background: rgb(255 255 255 / 13%); }
.primary-button { color: var(--navy); border: 1px solid #fff; background: #fff; }
.primary-button:hover { background: #eaf4fa; }
.header-actions button:focus-visible, .technology-list button:focus-visible, .path-list button:focus-visible { outline: 2px solid rgb(23 93 134 / 25%); outline-offset: 2px; }
.snapshot-copy { z-index: 1; grid-column: 1 / -1; display: flex; flex-wrap: wrap; align-items: center; gap: 9px; color: #b9d3e8; font-size: 10px; }
.snapshot-copy i { width: 3px; height: 3px; border-radius: 50%; background: #7196b0; }
.readiness-progress { position: absolute; right: 0; bottom: 0; left: 0; height: 4px; background: rgb(255 255 255 / 10%); }
.readiness-progress i { display: block; height: 100%; background: linear-gradient(90deg, #45ad85, #7ed8b2); box-shadow: 0 0 14px rgb(126 216 178 / 48%); transition: width .35s ease; }

.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; padding: 18px 20px; border-bottom: 1px solid var(--line); background: var(--soft); }
.metric-card { --accent: var(--blue); --accent-soft: var(--blue-soft); position: relative; display: grid; min-height: 160px; overflow: hidden; align-content: start; padding: 17px 18px 16px; border: 1px solid #dbe3e9; border-radius: 8px; background: #fff; box-shadow: 0 3px 10px rgb(23 52 76 / 4%); }
.metric-card::before { position: absolute; top: 0; right: 0; left: 0; height: 3px; content: ''; background: var(--accent); }
.vector-card { --accent: var(--violet); --accent-soft: var(--violet-soft); }
.knowledge-card { --accent: var(--green); --accent-soft: var(--green-soft); }
.code-card { --accent: var(--amber); --accent-soft: var(--amber-soft); }
.metric-card > header { display: grid; grid-template-columns: 27px minmax(0, 1fr) auto; align-items: center; gap: 8px; color: var(--accent); }
.metric-card > header > svg { box-sizing: content-box; padding: 6px; border-radius: 6px; background: var(--accent-soft); }
.metric-card > header span { color: var(--text); font-size: 11px; font-weight: 650; }
.metric-card > header em { color: var(--muted); font: 9px "SFMono-Regular", Consolas, monospace; font-style: normal; }
.metric-card > header em[data-ready='true'] { color: var(--green); }
.metric-value { display: flex; align-items: baseline; gap: 8px; margin-top: 19px; }
.metric-value strong { color: var(--ink); font: 650 clamp(29px, 4vw, 39px) "SFMono-Regular", Consolas, monospace; letter-spacing: -.05em; }
.metric-value span { color: var(--muted); font-size: 10px; }
.metric-card footer { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; margin-top: auto; padding-top: 13px; border-top: 1px solid #e8ecef; }
.metric-card footer b { color: var(--accent); font: 650 12px "SFMono-Regular", Consolas, monospace; }
.metric-card footer span { color: var(--muted); font-size: 10px; }

.preparation-section, .portrait-section { border-bottom: 1px solid var(--line); background: #fff; }
.section-heading { display: flex; align-items: end; justify-content: space-between; gap: 20px; padding: 22px 24px 14px; }
.section-heading > div { display: grid; gap: 4px; }
.section-heading span { color: var(--blue); font: 650 9px "SFMono-Regular", Consolas, monospace; letter-spacing: .13em; }
.section-heading h2 { margin: 0; color: var(--navy); font-size: 16px; }
.section-heading > p { max-width: 55%; margin: 0; color: var(--muted); font-size: 10px; text-align: right; }
.preparation-track { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); margin: 0 20px 24px; overflow: hidden; border: 1px solid var(--line); border-radius: 8px; box-shadow: 0 3px 12px rgb(23 52 76 / 4%); }
.preparation-track article { position: relative; display: grid; min-width: 0; min-height: 116px; padding: 15px 16px; border-right: 1px solid var(--line); background: #fff; }
.preparation-track article:last-of-type { border-right: 0; }
.preparation-track article::before { position: absolute; top: 0; right: 0; left: 0; height: 3px; content: ''; background: #c7d0d7; }
.preparation-track article::after { position: absolute; z-index: 1; top: 33px; right: -5px; width: 9px; height: 9px; content: ''; border-top: 1px solid var(--line); border-right: 1px solid var(--line); background: #fff; transform: rotate(45deg); }
.preparation-track article:last-of-type::after { display: none; }
.stage-marker { display: flex; align-items: center; justify-content: space-between; color: #9aa5ad; }
.stage-marker > span { font: 650 9px "SFMono-Regular", Consolas, monospace; letter-spacing: .08em; }
.stage-marker i { display: grid; width: 29px; height: 29px; place-items: center; border-radius: 50%; background: #f0f2f4; }
.preparation-track article[data-tone='ready'] .stage-marker { color: var(--green); }
.preparation-track article[data-tone='ready'] .stage-marker i { background: var(--green-soft); }
.preparation-track article[data-tone='ready']::before { background: var(--green); }
.preparation-track article[data-tone='running'] .stage-marker { color: var(--blue); }
.preparation-track article[data-tone='running'] .stage-marker i { background: var(--blue-soft); }
.preparation-track article[data-tone='running']::before { background: var(--blue); }
.preparation-track article[data-tone='danger'] .stage-marker { color: var(--amber); }
.preparation-track article[data-tone='danger'] .stage-marker i { background: var(--amber-soft); }
.preparation-track article[data-tone='danger']::before { background: var(--amber); }
.stage-copy { display: grid; min-width: 0; align-content: end; gap: 5px; margin-top: 14px; }
.stage-copy strong { color: var(--ink); font-size: 12px; }
.stage-copy small { color: var(--muted); font-size: 10px; line-height: 1.45; overflow-wrap: anywhere; }
.preparation-track > .empty-copy { grid-column: 1 / -1; padding: 18px; }

.portrait-section { border-bottom: 0; background: var(--soft); }
.portrait-heading { padding-bottom: 16px; border-bottom: 0; }
.portrait-grid { display: grid; grid-template-columns: repeat(12, minmax(0, 1fr)); gap: 12px; padding: 0 20px 22px; }
.data-panel { position: relative; min-width: 0; overflow: hidden; padding: 21px 22px 24px; border: 1px solid var(--line); border-radius: 8px; background: #fff; box-shadow: 0 3px 12px rgb(23 52 76 / 4%); }
.data-panel::before { position: absolute; top: 0; bottom: 0; left: 0; width: 3px; content: ''; background: var(--blue); }
.category-panel, .technology-panel { grid-column: span 7; }
.language-panel, .structure-panel { grid-column: span 5; }
.language-panel::before { background: var(--violet); }
.technology-panel::before { background: var(--green); }
.structure-panel::before { background: var(--amber); }
.data-panel > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 18px; }
.data-panel > header h2 { margin: 0; color: var(--navy); font-size: 14px; }
.data-panel > header p { margin: 4px 0 0; color: var(--muted); font-size: 10px; }
.data-panel > header > strong { color: var(--blue); font: 650 19px "SFMono-Regular", Consolas, monospace; }
.data-panel > header > svg { box-sizing: content-box; padding: 6px; color: var(--violet); border-radius: 6px; background: var(--violet-soft); }
.structure-panel > header > svg { color: var(--amber); background: var(--amber-soft); }
.category-list { display: grid; gap: 9px; }
.category-list article, .language-list article { display: grid; grid-template-columns: minmax(85px, .75fr) minmax(120px, 1.25fr) 34px; align-items: center; gap: 9px; }
.category-list span, .language-list span { overflow: hidden; color: var(--text); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.category-list article > div, .language-list article > div { height: 6px; overflow: hidden; border-radius: 4px; background: #e8edf0; }
.category-list i, .language-list i { display: block; height: 100%; border-radius: inherit; background: var(--blue); }
.category-list article:nth-child(4n+2) i { background: var(--green); }
.category-list article:nth-child(4n+3) i { background: var(--amber); }
.category-list article:nth-child(4n+4) i { background: var(--violet); }
.category-list b, .language-list b { text-align: right; font: 600 10px "SFMono-Regular", Consolas, monospace; }
.language-list { display: grid; gap: 9px; }
.language-list i { background: var(--violet); }
.language-list article:nth-child(3n+2) i { background: #4a91b5; }
.language-list article:nth-child(3n+3) i { background: #8c75b8; }
.technology-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 7px; }
.technology-list button { --tech: var(--blue); --tech-soft: var(--blue-soft); display: grid; grid-template-columns: 27px minmax(0, 1fr) auto; align-items: center; gap: 7px; min-width: 0; min-height: 52px; padding: 9px 10px; color: var(--tech); text-align: left; border: 1px solid #e1e7eb; border-radius: 6px; background: linear-gradient(90deg, var(--tech-soft), #fff 34%); transition: border-color .18s ease, transform .18s ease; }
.technology-list button[data-category='FRAMEWORK'], .technology-list button[data-category='STATE'] { --tech: var(--violet); --tech-soft: var(--violet-soft); }
.technology-list button[data-category='DATA'], .technology-list button[data-category='TEST'] { --tech: var(--green); --tech-soft: var(--green-soft); }
.technology-list button[data-category='BUILD'], .technology-list button[data-category='INFRASTRUCTURE'] { --tech: var(--amber); --tech-soft: var(--amber-soft); }
.technology-list button:hover { border-color: color-mix(in srgb, var(--tech) 35%, #dce3e9); transform: translateY(-1px); }
.technology-list button > svg { box-sizing: content-box; padding: 5px; border-radius: 5px; background: var(--tech-soft); }
.technology-list button > span { display: grid; min-width: 0; gap: 2px; }
.technology-list strong { overflow: hidden; color: var(--ink); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.technology-list small { color: var(--muted); font: 8px "SFMono-Regular", Consolas, monospace; }
.technology-list em { color: var(--tech); font-size: 8px; font-style: normal; }
.profile-block { display: grid; gap: 9px; padding: 13px 0; border-top: 1px solid #e8ecef; }
.profile-block:first-of-type { padding-top: 0; border-top: 0; }
.profile-label { display: grid; grid-template-columns: 20px minmax(0, 1fr) auto; align-items: center; color: var(--amber); }
.profile-label > svg { box-sizing: content-box; padding: 3px; border-radius: 4px; background: var(--amber-soft); }
.profile-label strong { color: var(--text); font-size: 10px; }
.profile-label span { color: var(--muted); font: 9px "SFMono-Regular", Consolas, monospace; }
.module-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 5px 12px; }
.module-list > div { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 8px; padding-bottom: 5px; border-bottom: 1px solid #edf0f2; }
.module-list code { overflow: hidden; color: #31536d; font: 9px "SFMono-Regular", Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.module-list span { color: var(--muted); font-size: 9px; white-space: nowrap; }
.path-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 5px; }
.path-list button { overflow: hidden; min-width: 0; padding: 8px 9px; color: #31536d; text-align: left; text-overflow: ellipsis; white-space: nowrap; border: 0; border-left: 2px solid var(--amber); background: #f7f8f9; font: 9px "SFMono-Regular", Consolas, monospace; }
.path-list button:hover { background: var(--amber-soft); }
.asset-list button { display: flex; align-items: center; justify-content: space-between; gap: 7px; }
.asset-list button span { overflow: hidden; text-overflow: ellipsis; }
.asset-list button em { flex: none; color: var(--muted); font-size: 8px; font-style: normal; }
.asset-counts { display: flex; flex-wrap: wrap; gap: 5px; }
.asset-counts span { display: inline-flex; align-items: baseline; gap: 4px; padding: 6px 8px; color: var(--muted); border: 1px solid #eadfce; border-radius: 4px; background: #fffaf3; font-size: 8px; }
.asset-counts b { color: var(--amber); font: 650 10px "SFMono-Regular", Consolas, monospace; }
.empty-copy { margin: 0; color: var(--muted); font-size: 10px; }
.spinning { animation: spin .85s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 900px) {
  .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .category-panel, .technology-panel, .language-panel, .structure-panel { grid-column: span 12; }
}

@media (max-width: 640px) {
  .project-header { grid-template-columns: minmax(0, 1fr) auto; padding: 20px; }
  .header-state { justify-self: end; }
  .header-actions { grid-column: 1 / -1; }
  .snapshot-copy { grid-column: 1 / -1; }
  .metric-grid, .preparation-track { grid-template-columns: 1fr; }
  .metric-grid { padding: 14px; }
  .metric-card { min-height: 145px; }
  .section-heading { align-items: start; flex-direction: column; gap: 7px; }
  .section-heading > p { max-width: none; text-align: left; }
  .preparation-track { margin-right: 14px; margin-left: 14px; }
  .preparation-track article { min-height: 96px; border-right: 0; border-bottom: 1px solid var(--line); }
  .preparation-track article:last-of-type { border-bottom: 0; }
  .preparation-track article::after { display: none; }
  .portrait-grid { padding-right: 14px; padding-left: 14px; }
  .technology-list { grid-template-columns: 1fr; }
  .module-list, .path-list { grid-template-columns: 1fr; }
}

@media (prefers-reduced-motion: reduce) { .spinning { animation: none; } }
</style>
