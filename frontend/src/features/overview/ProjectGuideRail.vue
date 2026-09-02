<script setup lang="ts">
import { computed } from 'vue';
import {
  ArrowUpRight,
  Boxes,
  Braces,
  Copy,
  FileCode2,
  Play,
  Sparkles,
} from 'lucide-vue-next';
import { ElMessage } from 'element-plus';
import type { ProjectProfile } from '@/api/repositories';

interface Props {
  profile: ProjectProfile | null;
  commands: string[];
}

const props = defineProps<Props>();
const emit = defineEmits<{
  openFile: [path: string];
  openModule: [module: string];
  guide: [];
}>();

const languages = computed(() => props.profile?.languages.slice(0, 6) ?? []);
const modules = computed(() => props.profile?.modules.slice(0, 6) ?? []);
const entryPoints = computed(() => props.profile?.entryPoints.slice(0, 6) ?? []);
const languageMaximum = computed(() => Math.max(1, ...languages.value.map(item => item.count)));

function languageWidth(count: number) {
  return `${Math.max(12, Math.round((count / languageMaximum.value) * 100))}%`;
}

async function copyCommand(command: string) {
  try {
    await navigator.clipboard.writeText(command);
    ElMessage.success('命令已复制');
  } catch {
    ElMessage.error('复制失败，请检查剪贴板权限');
  }
}
</script>

<template>
  <aside class="guide-rail">
    <section class="guide-card ai-guide">
      <span class="guide-icon"><Sparkles :size="17" /></span>
      <div>
        <span class="eyebrow">智能导读</span>
        <h2>让项目自己解释自己</h2>
        <p>结合 README、设计文档和代码证据，生成面向新接手者的项目说明。</p>
      </div>
      <button type="button" @click="emit('guide')">开始生成 <ArrowUpRight :size="13" /></button>
    </section>

    <section v-if="commands.length" class="guide-card commands-card">
      <header><span><Play :size="14" />README 中的常用命令</span></header>
      <div class="command-list">
        <button v-for="command in commands" :key="command" type="button" @click="copyCommand(command)">
          <code>{{ command }}</code><Copy :size="12" />
        </button>
      </div>
      <footer>命令来自项目文档，仅供复制，不会自动执行。</footer>
    </section>

    <section class="guide-card fact-card">
      <header><span><Braces :size="14" />主要技术</span></header>
      <div v-if="languages.length" class="language-list">
        <div v-for="language in languages" :key="language.name">
          <span><strong>{{ language.name }}</strong><small>{{ language.count }} 文件</small></span>
          <i><b :style="{ width: languageWidth(language.count) }"></b></i>
        </div>
      </div>
      <p v-else class="empty-copy">完成项目扫描后展示主要技术。</p>
    </section>

    <section class="guide-card fact-card">
      <header><span><Boxes :size="14" />代码从哪里开始</span></header>
      <div v-if="entryPoints.length" class="path-list">
        <button v-for="path in entryPoints" :key="path" type="button" @click="emit('openFile', path)">
          <FileCode2 :size="12" /><span>{{ path }}</span><ArrowUpRight :size="11" />
        </button>
      </div>
      <div v-else-if="modules.length" class="path-list">
        <button v-for="module in modules" :key="module.name" type="button" @click="emit('openModule', module.name)">
          <Boxes :size="12" /><span>{{ module.name }}</span><small>{{ module.count }}</small>
        </button>
      </div>
      <p v-else class="empty-copy">尚未识别出明确入口，可以先阅读 README。</p>
    </section>
  </aside>
</template>

<style scoped>
.guide-rail {
  display: grid;
  align-content: start;
  gap: 10px;
  min-width: 0;
}

.guide-card {
  min-width: 0;
  overflow: hidden;
  border: 1px solid #d7dce2;
  border-radius: 8px;
  background: #fff;
}

.ai-guide {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr);
  gap: 11px;
  padding: 16px;
  color: #dcecf7;
  border-color: #243f53;
  background: #1a3040;
}

.guide-icon {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  color: #16384c;
  border-radius: 7px;
  background: #7fd0ae;
}

.ai-guide > div { min-width: 0; }
.eyebrow { color: #8fb0c6; font-size: 13px; font-weight: 700; letter-spacing: .08em; }
.ai-guide h2 { margin: 5px 0 4px; color: #fff; font-size: 15px; }
.ai-guide p { margin: 0; color: #a9c0d0; font-size: 13px; line-height: 1.55; }
.ai-guide button {
  grid-column: 1 / -1;
  display: flex;
  min-height: 34px;
  align-items: center;
  justify-content: center;
  gap: 5px;
  color: #18384b;
  border: 0;
  border-radius: 5px;
  background: #eaf7f1;
  font-size: 13px;
  font-weight: 650;
}
.ai-guide button:hover, .ai-guide button:focus-visible { outline: 2px solid #7fd0ae; outline-offset: 2px; background: #fff; }

.guide-card > header {
  display: flex;
  min-height: 43px;
  align-items: center;
  padding: 9px 12px;
  border-bottom: 1px solid #e9ecef;
}

.guide-card > header span { display: flex; align-items: center; gap: 6px; color: #3d4e5b; font-size: 13px; font-weight: 650; }

.command-list { display: grid; padding: 6px; }
.command-list button {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 14px;
  align-items: center;
  gap: 6px;
  padding: 7px 8px;
  color: #cad9e4;
  text-align: left;
  border: 0;
  border-radius: 4px;
  background: #223440;
}
.command-list button + button { margin-top: 4px; }
.command-list button:hover, .command-list button:focus-visible { color: #fff; outline: 2px solid rgb(0 102 204 / 30%); background: #182832; }
.command-list code { overflow: hidden; font: 13px/1.5 "SFMono-Regular", Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.commands-card footer { padding: 7px 10px; color: #7d8790; border-top: 1px solid #eceff1; background: #fafbfc; font-size: 13px; line-height: 1.45; }

.language-list { display: grid; gap: 10px; padding: 12px; }
.language-list > div { display: grid; gap: 5px; }
.language-list span { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.language-list strong { color: #3a4c59; font: 600 13px "SFMono-Regular", Consolas, monospace; }
.language-list small { color: #7c8791; font-size: 13px; }
.language-list i { height: 3px; overflow: hidden; border-radius: 2px; background: #e6ebef; }
.language-list b { display: block; height: 100%; border-radius: inherit; background: linear-gradient(90deg, var(--app-color-action), #70a9d7); }

.path-list { display: grid; padding: 6px; }
.path-list button {
  display: grid;
  grid-template-columns: 15px minmax(0, 1fr) 14px;
  align-items: center;
  gap: 6px;
  padding: 7px;
  color: #54636f;
  text-align: left;
  border: 0;
  border-radius: 4px;
  background: transparent;
}
.path-list button:hover, .path-list button:focus-visible { color: var(--app-color-action); outline: none; background: #f1f7fc; }
.path-list button span { overflow: hidden; font: 13px "SFMono-Regular", Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.path-list button small { color: #8b949c; font: 13px Consolas, monospace; text-align: right; }
.empty-copy { margin: 0; padding: 14px; color: #7b858e; font-size: 13px; line-height: 1.5; }

@media (max-width: 980px) {
  .guide-rail { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 620px) {
  .guide-rail { grid-template-columns: 1fr; }
}
</style>
