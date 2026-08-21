<script setup lang="ts">
import { computed, shallowRef } from 'vue';
import { Bot, Clipboard, Download, Sparkles } from 'lucide-vue-next';
import type { ProjectContextPack } from '@/api/repositories';

interface Props {
  pack: ProjectContextPack | null;
  busy: boolean;
  disabled: boolean;
}

const props = defineProps<Props>();
const emit = defineEmits<{
  generate: [task: string];
  copy: [];
  download: [];
  openFile: [path: string];
}>();
const task = shallowRef('');
const canGenerate = computed(() => !props.disabled && !props.busy && task.value.trim().length > 1);

function generate() {
  const value = task.value.trim();
  if (value) emit('generate', value);
}
</script>

<template>
  <section class="context-panel">
    <div class="context-intro">
      <span class="agent-mark"><Bot :size="21" /></span>
      <div>
        <span class="eyebrow">AGENT CONTEXT PACK</span>
        <h2>把项目边界交给任何开发智能体</h2>
        <p>输入任务后，系统从当前 commit 中组合规则、任务、文档、代码与配置，并保留可追溯引用。</p>
      </div>
    </div>

    <div class="context-command">
      <el-input
        v-model="task"
        type="textarea"
        :rows="3"
        maxlength="1000"
        show-word-limit
        placeholder="例如：为订单创建接口增加幂等校验，保持现有领域边界和错误返回约定"
        @keydown.ctrl.enter="generate"
      />
      <div class="command-actions">
        <span>Ctrl + Enter 生成 · 只读 · 绑定当前快照</span>
        <el-button type="primary" :loading="busy" :disabled="!canGenerate" @click="generate">
          <Sparkles :size="14" /> 生成上下文包
        </el-button>
      </div>
    </div>

    <div class="context-result">
      <template v-if="pack">
        <header class="result-head">
          <div>
            <strong>{{ pack.items.length }} 条当前版本证据</strong>
            <span class="mono">{{ pack.commitSha?.slice(0, 10) ?? '无 commit' }}</span>
          </div>
          <div>
            <el-button size="small" @click="emit('copy')"><Clipboard :size="13" />复制</el-button>
            <el-button size="small" @click="emit('download')"><Download :size="13" />下载 .md</el-button>
          </div>
        </header>
        <div class="context-items">
          <button v-for="item in pack.items" :key="item.chunkId" type="button" @click="emit('openFile', item.filePath)">
            <span :data-type="item.assetType">{{ item.assetType }}</span>
            <div><strong>{{ item.filePath }}</strong><p>{{ item.excerpt }}</p></div>
            <small>{{ item.startLine ? `L${item.startLine}` : 'FILE' }}</small>
          </button>
        </div>
      </template>
      <el-empty v-else :image-size="52" description="描述一个开发任务，生成可交给 Codex、Claude Code 或 Kimi 的项目上下文" />
    </div>
  </section>
</template>

<style scoped>
.context-panel { display: grid; grid-template-columns: minmax(240px, .75fr) minmax(320px, 1fr) minmax(360px, 1.25fr); min-width: 0; overflow: hidden; border: 1px solid #ccd9e2; border-radius: 8px; background: #fff; box-shadow: 0 10px 30px rgb(28 58 78 / 7%); }
.context-intro { display: flex; gap: 12px; padding: 18px; color: #fff; background: #223b4b; }
.agent-mark { display: grid; flex: none; width: 40px; height: 40px; place-items: center; border: 1px solid rgb(255 255 255 / 22%); border-radius: 6px; background: #16735a; }
.context-intro div { min-width: 0; }
.eyebrow { color: #8ed6c1; font: 700 9px Consolas, monospace; letter-spacing: .13em; }
.context-intro h2 { max-width: 310px; margin: 8px 0 10px; font-size: 17px; line-height: 1.35; }
.context-intro p { margin: 0; color: #c1d0d9; font-size: 10px; line-height: 1.65; }
.context-command { display: grid; align-content: center; gap: 10px; padding: 16px; border-right: 1px solid #e3e8ec; }
.context-command :deep(.el-textarea__inner) { min-height: 88px !important; border-radius: 5px; box-shadow: 0 0 0 1px #cfd9e0 inset; font-size: 11px; line-height: 1.55; resize: none; }
.command-actions { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.command-actions span { color: #89939b; font-size: 8px; }
.command-actions .el-button { gap: 5px; }
.context-result { min-width: 0; min-height: 210px; background: #fafbfc; }
.result-head { display: flex; min-height: 48px; align-items: center; justify-content: space-between; gap: 10px; padding: 8px 12px; border-bottom: 1px solid #e3e8ec; }
.result-head > div { display: flex; align-items: center; gap: 8px; }
.result-head strong { color: #344653; font-size: 11px; }
.result-head span { color: #7d8992; font-size: 9px; }
.result-head .el-button { gap: 4px; }
.context-items { max-height: 250px; overflow: auto; }
.context-items button { display: grid; grid-template-columns: 62px minmax(0, 1fr) 38px; align-items: start; gap: 9px; width: 100%; padding: 9px 12px; text-align: left; border: 0; border-bottom: 1px solid #e8ecef; background: transparent; }
.context-items button:hover { background: #f0f6f9; }
.context-items > button > span { padding: 3px 4px; color: #526672; text-align: center; border-radius: 3px; background: #e4eaee; font: 8px Consolas, monospace; }
.context-items > button > span[data-type='RULE'] { color: #76501f; background: #f2e6d4; }
.context-items > button > span[data-type='TASK'] { color: #624d77; background: #ece6f2; }
.context-items div { display: grid; min-width: 0; gap: 3px; }
.context-items strong { overflow: hidden; color: #385064; font: 600 9px Consolas, monospace; text-overflow: ellipsis; white-space: nowrap; }
.context-items p { display: -webkit-box; margin: 0; overflow: hidden; color: #77838c; font-size: 9px; line-height: 1.4; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.context-items small { color: #8a969f; font: 8px Consolas, monospace; text-align: right; }
@media (max-width: 1100px) {
  .context-panel { grid-template-columns: 1fr 1.4fr; }
  .context-result { grid-column: 1 / -1; border-top: 1px solid #e3e8ec; }
}
@media (max-width: 700px) {
  .context-panel { grid-template-columns: 1fr; }
  .context-result { grid-column: auto; }
  .command-actions { align-items: stretch; flex-direction: column; }
}
</style>
