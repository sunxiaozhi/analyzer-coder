<script setup lang="ts">
import { computed, shallowRef } from 'vue';
import {
  ArrowRight,
  Clipboard,
  Code2,
  Download,
  Search,
  SearchCheck,
  Sparkles,
} from 'lucide-vue-next';
import type { ProjectContextPack } from '@/api/repositories';

interface Props {
  pack: ProjectContextPack | null;
  busy: boolean;
  disabled: boolean;
  suggestions: string[];
}

const props = defineProps<Props>();
const emit = defineEmits<{
  analyze: [task: string];
  search: [task: string];
  generate: [task: string];
  copy: [];
  download: [];
  openFile: [path: string];
}>();
const task = shallowRef('');
const taskValue = computed(() => task.value.trim());
const canGenerate = computed(() => !props.disabled && !props.busy && taskValue.value.length > 1);

function chooseSuggestion(value: string) {
  task.value = value;
}

function generate() {
  if (canGenerate.value) emit('generate', taskValue.value);
}

function openItem(path: string | null) {
  if (path) emit('openFile', path);
}
</script>

<template>
  <section class="task-console">
    <header class="console-intro">
      <div>
        <span class="eyebrow">具体改动</span>
        <h2>为一次改动准备代码证据</h2>
        <p>写清要修改的行为或故障。你可以继续问答、检索源码，或从当前快照生成一份可追溯的上下文包。</p>
      </div>
      <span class="console-mark"><Code2 :size="22" /></span>
    </header>

    <div class="task-input">
      <el-input
        v-model="task"
        type="textarea"
        :rows="2"
        maxlength="1000"
        resize="none"
        placeholder="例如：为登录接口增加失败次数限制，并确认会影响哪些调用方"
        aria-label="描述开发任务"
        @keydown.ctrl.enter="generate"
      />
      <div v-if="suggestions.length" class="suggestion-list" aria-label="建议任务">
        <span>试试</span>
        <button
          v-for="suggestion in suggestions"
          :key="suggestion"
          type="button"
          @click="chooseSuggestion(suggestion)"
        >
          {{ suggestion }}
        </button>
      </div>
    </div>

    <div class="task-rail" aria-label="选择工作方式">
      <button type="button" class="rail-action ask-action" :disabled="disabled || !taskValue" @click="emit('analyze', taskValue)">
        <span class="action-icon"><SearchCheck :size="17" /></span>
        <span><strong>分析改动影响</strong><small>核验候选代码、模块与测试</small></span>
        <ArrowRight :size="14" />
      </button>
      <button type="button" class="rail-action" @click="emit('search', taskValue)">
        <span class="action-icon"><Search :size="17" /></span>
        <span><strong>检索相关源码</strong><small>定位文件、规则和实现片段</small></span>
        <ArrowRight :size="14" />
      </button>
      <button type="button" class="rail-action" :disabled="!canGenerate" @click="generate">
        <span class="action-icon"><Sparkles :size="17" /></span>
        <span><strong>生成上下文包</strong><small>导出当前快照的相关片段</small></span>
        <ArrowRight :size="14" />
      </button>
    </div>

    <div v-if="pack" class="context-result">
      <header class="result-head">
        <div>
          <span class="result-mark"><Sparkles :size="14" /></span>
          <div><strong>编码助手上下文已生成</strong><small>{{ pack.items.length }} 条当前版本证据 · {{ pack.commitSha?.slice(0, 10) ?? '无提交版本' }}</small></div>
        </div>
        <nav aria-label="上下文导出操作">
          <button type="button" @click="emit('copy')"><Clipboard :size="13" />复制</button>
          <button type="button" @click="emit('download')"><Download :size="13" />下载</button>
        </nav>
      </header>
      <div class="context-items">
        <button v-for="item in pack.items" :key="item.id" type="button" :disabled="!item.filePath" @click="openItem(item.filePath)">
          <span :data-type="item.assetType">{{ item.assetType }}</span>
          <div><strong>{{ item.filePath ?? item.title }}</strong><p>{{ item.excerpt }}</p></div>
          <small>{{ item.startLine ? `L${item.startLine}` : 'FILE' }}</small>
        </button>
      </div>
    </div>

    <footer class="console-foot">
      <span>关键词匹配当前快照，只生成证据材料，不会启动或执行编码助手</span>
      <span v-if="disabled">完成内容索引后可使用问答和编码助手上下文</span>
      <span v-else>按 Ctrl + Enter 生成编码助手上下文</span>
    </footer>
  </section>
</template>

<style scoped>
.task-console { min-width: 0; overflow: hidden; border: 1px solid var(--el-border-color, #dedee3); border-radius: 7px; background: #fff; }
.console-intro { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; padding: 17px 18px 12px; }
.eyebrow { color: var(--app-text-muted); font-size: 13px; font-weight: 650; letter-spacing: .04em; }
.console-intro h2 { margin: 6px 0 4px; color: var(--el-text-color-primary, #1d1d1f); font-size: 20px; font-weight: 650; line-height: 1.25; }
.console-intro p { max-width: 620px; margin: 0; color: var(--app-text-muted); font-size: 14px; line-height: 1.6; }
.console-mark { display: grid; flex: none; width: 42px; height: 42px; place-items: center; color: #fff; border-radius: 7px; background: var(--el-color-primary, var(--app-color-action)); }
.task-input { padding: 0 18px 13px; }
.task-input :deep(.el-textarea__inner) { min-height: 58px !important; padding: 10px 12px; color: var(--el-text-color-primary, #1d1d1f); border-radius: 6px !important; background: #fafbfc; box-shadow: 0 0 0 1px #d8dce2 inset; font-size: 14px; line-height: 1.6; }
.task-input :deep(.el-textarea__inner:hover) { background: #fff; box-shadow: 0 0 0 1px #aeb7c2 inset; }
.task-input :deep(.el-textarea__inner:focus) { background: #fff; box-shadow: 0 0 0 1px var(--el-color-primary, var(--app-color-action)) inset, 0 0 0 3px rgb(0 102 204 / 10%); }
.suggestion-list { display: flex; min-width: 0; align-items: center; gap: 6px; margin-top: 8px; overflow-x: auto; }
.suggestion-list > span { flex: none; color: var(--app-text-muted); font-size: 13px; }
.suggestion-list button { flex: none; max-width: 270px; padding: 5px 8px; overflow: hidden; color: #5e6670; border: 1px solid #d8dce2; border-radius: 999px; background: #fff; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.suggestion-list button:hover, .suggestion-list button:focus-visible { color: var(--el-color-primary, var(--app-color-action)); border-color: #90bde5; outline: none; background: #f7fbff; }
.task-rail { display: grid; grid-template-columns: repeat(3, 1fr); gap: 1px; border-top: 1px solid #ececef; border-bottom: 1px solid #ececef; background: #ececef; }
.rail-action { display: grid; grid-template-columns: 34px minmax(0, 1fr) 18px; min-width: 0; align-items: center; gap: 8px; min-height: 68px; padding: 10px 12px; color: #52525b; text-align: left; border: 0; background: #fff; }
.rail-action:hover, .rail-action:focus-visible { position: relative; z-index: 1; color: var(--el-color-primary, var(--app-color-action)); outline: 2px solid rgb(0 102 204 / 22%); outline-offset: -2px; background: #f7fbff; }
.rail-action:disabled { cursor: not-allowed; opacity: .48; }
.rail-action > span:nth-child(2) { display: grid; min-width: 0; gap: 3px; }
.rail-action strong { color: var(--el-text-color-primary, #1d1d1f); font-size: 14px; }
.rail-action small { overflow: hidden; color: var(--app-text-muted); font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.action-icon { display: grid; width: 32px; height: 32px; place-items: center; color: var(--el-color-primary, var(--app-color-action)); border-radius: 6px; background: var(--app-color-action-soft); }
.ask-action .action-icon { color: var(--el-color-primary, var(--app-color-action)); background: var(--app-color-action-soft); }
.context-result { background: #fafafa; }
.result-head { display: flex; min-height: 54px; align-items: center; justify-content: space-between; gap: 12px; padding: 9px 13px; border-bottom: 1px solid #ececef; }
.result-head > div { display: flex; align-items: center; gap: 9px; }
.result-mark { display: grid; width: 28px; height: 28px; place-items: center; color: var(--app-color-success); border-radius: 6px; background: var(--app-color-success-soft); }
.result-head > div > div { display: grid; gap: 2px; }
.result-head strong { color: var(--el-text-color-primary, #1d1d1f); font-size: 14px; }
.result-head small { color: var(--app-text-muted); font: 13px "SFMono-Regular", Consolas, monospace; }
.result-head nav { display: flex; gap: 5px; }
.result-head nav button { display: flex; align-items: center; gap: 4px; padding: 5px 7px; color: #52525b; border: 1px solid #dedee3; border-radius: 4px; background: #fff; font-size: 13px; }
.result-head nav button:hover, .result-head nav button:focus-visible { color: var(--el-color-primary, var(--app-color-action)); border-color: #90bde5; outline: none; }
.context-items { max-height: 220px; overflow: auto; }
.context-items > button { display: grid; grid-template-columns: 58px minmax(0, 1fr) 38px; align-items: start; gap: 9px; width: 100%; padding: 9px 13px; text-align: left; border: 0; border-bottom: 1px solid #ececef; background: transparent; }
.context-items > button:hover, .context-items > button:focus-visible { outline: none; background: #f1f7fd; }
.context-items > button:disabled { cursor: default; }
.context-items > button > span { padding: 3px 4px; color: #5e6670; text-align: center; border-radius: 3px; background: #e9ecef; font: 13px "SFMono-Regular", Consolas, monospace; }
.context-items > button > span[data-type='RULE'] { color: #78521f; background: #f3e7d6; }
.context-items > button > span[data-type='TASK'] { color: #654e7a; background: #ede7f3; }
.context-items div { display: grid; min-width: 0; gap: 3px; }
.context-items strong { overflow: hidden; color: #3f4b56; font: 600 13px "SFMono-Regular", Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.context-items p { display: -webkit-box; margin: 0; overflow: hidden; color: #5f6973; font-size: 14px; line-height: 1.5; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.context-items small { color: var(--app-text-muted); font: 13px "SFMono-Regular", Consolas, monospace; text-align: right; }
.console-foot { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 33px; padding: 7px 13px; color: var(--app-text-muted); background: #fafafa; font-size: 13px; }
@media (max-width: 700px) {
  .task-rail { grid-template-columns: 1fr; }
  .rail-action { min-height: 58px; }
  .suggestion-list { align-items: flex-start; flex-direction: column; }
  .suggestion-list button { max-width: 100%; }
  .console-foot { align-items: flex-start; flex-direction: column; }
}
</style>
