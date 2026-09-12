<script setup lang="ts">
import { computed } from 'vue';
import type {
  ProjectCodeTypeCount,
  ProjectFileCategory,
  ProjectProfileCount,
} from '@/api/repositories';

interface Props {
  codeTypes: ProjectCodeTypeCount[];
  projectType: string;
  categories: ProjectFileCategory[];
  modules: ProjectProfileCount[];
}

const props = defineProps<Props>();

const STRUCTURE_LAYERS = [
  { key: 'ENTRY', label: '交互与入口', categories: ['VIEW', 'COMPONENT', 'API'] },
  { key: 'APPLICATION', label: '应用逻辑', categories: ['SERVICE', 'STATE'] },
  { key: 'DOMAIN', label: '领域模型', categories: ['DOMAIN'] },
  { key: 'DATA', label: '数据与集成', categories: ['DATA', 'DATABASE_SCRIPT', 'INFRASTRUCTURE'] },
] as const;

const codeFileTotal = computed(() => (
  props.codeTypes.reduce((total, codeType) => total + codeType.count, 0)
));

const codeTypeRows = computed(() => (
  props.codeTypes
    .filter(codeType => codeType.count > 0)
    .slice()
    .sort((left, right) => right.count - left.count || left.name.localeCompare(right.name))
    .map(codeType => {
      const percent = codeFileTotal.value > 0 ? codeType.count / codeFileTotal.value * 100 : 0;
      return {
        ...codeType,
        percent,
        percentLabel: `${Math.round(percent * 10) / 10}%`,
      };
    })
));

const structureLayers = computed(() => (
  STRUCTURE_LAYERS.map(layer => {
    const matches = props.categories.filter(category => (
      category.count > 0 && layer.categories.some(key => key === category.key)
    ));
    return {
      ...layer,
      count: matches.reduce((total, category) => total + category.count, 0),
    };
  }).filter(layer => layer.count > 0)
));

const primaryModules = computed(() => (
  props.modules.filter(module => module.count > 0).slice(0, 4)
));
</script>

<template>
  <div class="project-composition">
    <section class="composition-panel code-type-panel" aria-labelledby="source-types-title">
      <header class="panel-heading">
        <h3 id="source-types-title">代码类型</h3>
      </header>

      <ul v-if="codeTypeRows.length" class="code-type-list">
        <li v-for="codeType in codeTypeRows" :key="codeType.name" data-testid="code-type-row">
          <div class="code-type-heading">
            <strong>{{ codeType.name }}</strong>
            <span>{{ codeType.count }} 个 · {{ codeType.percentLabel }}</span>
          </div>
          <span
            class="code-type-track"
            role="progressbar"
            :aria-label="`${codeType.name}占源码文件的${codeType.percentLabel}`"
            aria-valuemin="0"
            aria-valuemax="100"
            :aria-valuenow="codeType.percent"
          >
            <i :style="{ width: `${codeType.percent}%` }"></i>
          </span>
        </li>
      </ul>
      <p v-else class="empty-copy">当前快照没有可识别的源码类型。</p>
    </section>

    <section class="composition-panel structure-panel" aria-labelledby="system-structure-title">
      <header class="panel-heading">
        <h3 id="system-structure-title">系统结构</h3>
        <span>基于代码目录推断</span>
      </header>

      <div class="project-kind">
        <span>项目形态</span>
        <strong>{{ projectType || '暂未识别' }}</strong>
      </div>

      <div v-if="structureLayers.length" class="structure-flow">
        <article v-for="layer in structureLayers" :key="layer.key" class="structure-layer">
          <div>
            <strong>{{ layer.label }}</strong>
            <span>{{ layer.count }} 个文件</span>
          </div>
        </article>
      </div>
      <p v-else class="empty-copy">目录命名不足，暂无法稳定识别系统分层。</p>

      <div v-if="primaryModules.length" class="module-line">
        <span>主要模块</span>
        <div>
          <code v-for="module in primaryModules" :key="module.name">{{ module.name }}</code>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.project-composition {
  display: grid;
  grid-template-columns: minmax(220px, .85fr) minmax(300px, 1.15fr);
  gap: 18px;
}

.composition-panel {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 4px;
  background: #fff;
}

.panel-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.panel-heading h3 {
  margin: 0;
  color: var(--navy);
  font-size: 13px;
}

.panel-heading span {
  color: var(--muted);
  font-size: 11px;
}

.code-type-list {
  display: grid;
  gap: 13px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.code-type-list li {
  display: grid;
  gap: 6px;
}

.code-type-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.code-type-heading strong {
  color: var(--ink);
  font: 700 12px/1.2 "SFMono-Regular", Consolas, monospace;
}

.code-type-heading span {
  color: var(--muted);
  font: 600 11px/1.2 "SFMono-Regular", Consolas, monospace;
  white-space: nowrap;
}

.code-type-track {
  display: block;
  height: 6px;
  overflow: hidden;
  border-radius: 2px;
  background: #e7edf0;
}

.code-type-track i {
  display: block;
  min-width: 3px;
  height: 100%;
  border-radius: inherit;
  background: var(--blue);
}

.project-kind {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  align-items: baseline;
  gap: 10px;
  padding: 10px 12px;
  border-left: 3px solid var(--blue);
  background: var(--soft);
}

.project-kind span,
.module-line > span {
  color: var(--muted);
  font-size: 11px;
}

.project-kind strong {
  color: var(--ink);
  font-size: 13px;
}

.structure-flow {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-top: 12px;
  border: 1px solid var(--line);
}

.structure-layer {
  display: grid;
  min-width: 0;
  gap: 4px;
  padding: 10px;
  border-right: 1px solid var(--line);
}

.structure-layer:last-child {
  border-right: 0;
}

.structure-layer div {
  display: grid;
  gap: 2px;
}

.structure-layer strong {
  color: #405663;
  font-size: 12px;
}

.structure-layer span {
  color: var(--muted);
  font-size: 10px;
  line-height: 1.4;
}

.module-line {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  align-items: start;
  gap: 10px;
  margin-top: 12px;
}

.module-line > span {
  padding-top: 3px;
}

.module-line div {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.module-line code {
  padding: 2px 6px;
  overflow-wrap: anywhere;
  color: #49606d;
  border: 1px solid #dce5e9;
  border-radius: 3px;
  background: #f6f8f9;
  font: 500 11px/1.4 "SFMono-Regular", Consolas, monospace;
}

.empty-copy {
  margin: 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
}

@media (max-width: 760px) {
  .project-composition {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 500px) {
  .structure-flow {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .structure-layer:nth-child(2n) {
    border-right: 0;
  }

  .structure-layer:nth-child(-n + 2) {
    border-bottom: 1px solid var(--line);
  }
}
</style>
