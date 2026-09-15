<script setup lang="ts">
import { computed } from 'vue';
import type { BranchCodeOperation, BranchContext, BranchIndexStatus, BranchPreparationJob, RepositoryBranch } from '@/api/branches';
const props=defineProps<{
  branch: RepositoryBranch; context: BranchContext | null; status?: BranchIndexStatus;
  jobs: BranchPreparationJob[]; disabled: boolean; canMaintain: boolean;
}>();
const emit=defineEmits<{ operate: [kind: BranchCodeOperation]; vectors: []; refresh: []; read: [target: 'search' | 'atlas']; copy: [] }>();
const currentStatus=computed(()=>props.status?.snapshotId===props.context?.snapshotId?props.status:undefined);
const currentJobs = computed(() => {
  const running = props.jobs.filter(job => ['QUEUED', 'RUNNING'].includes(job.status));
  return running.length ? running : props.jobs.filter(job => job.status === 'FAILED' && job.snapshotId === props.branch.snapshotId);
});
const active=computed(()=>props.jobs.some(job=>['QUEUED','RUNNING'].includes(job.status)));
const unavailable=computed(()=>props.disabled || active.value || props.branch.trackingStatus!=='ACTIVE');
const contentReady=computed(()=>Boolean(currentStatus.value?.contentReady));
const graphReady=computed(()=>Boolean(currentStatus.value?.graphReady));
const vectorsReady=computed(()=>Boolean(currentStatus.value?.vectorsReady));
const syncedAtLabel=computed(()=>props.status?.syncedAt ? new Date(props.status.syncedAt).toLocaleString() : '按需获取该分支代码');
const syncLabel=computed(()=>props.branch.status==='BUILDING'?'同步中':props.branch.status==='FAILED'?'上次同步失败':props.branch.snapshotId?'已同步':'未同步');
const labels:Record<string,string>={SYNC:'同步代码',CONTENT:'内容索引',GRAPH:'代码图谱',VECTORS:'向量索引',PREPARE:'一键准备',SNAPSHOT:'旧版准备'};
const stages:Record<string,string>={QUEUED:'等待执行',RESOLVING:'确认目标版本',SNAPSHOT:'导出代码',PUBLISHING:'发布快照',INDEXING:'构建内容索引',GRAPH:'构建代码图谱',EMBEDDING:'构建向量',COMPLETED:'已完成',FAILED:'失败'};
const statuses:Record<string,string>={QUEUED:'排队中',RUNNING:'执行中',SUCCEEDED:'已完成',FAILED:'失败'};
</script>
<template>
  <section class="branch-index-panel" aria-label="分支代码与索引">
    <header class="snapshot-strip">
      <div><span class="scope-label">分支代码版本</span><strong>{{ branch.name }}</strong></div>
      <code>{{ context?.commitSha.slice(0,12) ?? branch.commitSha?.slice(0,12) ?? '未同步' }}</code>
      <small>快照 {{ context?.snapshotId.slice(0,8) ?? branch.snapshotId?.slice(0,8) ?? '—' }}</small>
      <el-button v-if="branch.snapshotId" link type="primary" @click="emit('refresh')">刷新已准备版本</el-button>
    </header>
    <div class="index-states">
      <div><span>代码同步</span><strong>{{ syncLabel }}</strong><small>{{ syncedAtLabel }}</small></div>
      <div :data-ready="contentReady"><span>内容索引</span><strong>{{ contentReady?'已就绪':'待构建' }}</strong><small>当前快照的关键词与符号</small></div>
      <div :data-ready="graphReady"><span>代码图谱</span><strong>{{ graphReady?'已就绪':'待构建' }}</strong><small>当前快照的节点与关系</small></div>
      <div :data-ready="vectorsReady"><span>向量索引</span><strong>{{ vectorsReady?'已就绪':'待构建' }}</strong><small>基于当前内容索引，按需执行</small></div>
    </div>
    <div class="index-actions">
      <template v-if="canMaintain">
        <el-button :disabled="unavailable" @click="emit('operate','SYNC')">同步代码</el-button>
        <el-button type="primary" :disabled="unavailable" @click="emit('operate','PREPARE')">一键准备</el-button>
      </template>
      <el-button :disabled="!context || !contentReady" @click="emit('read','search')">打开代码</el-button>
    </div>
    <details class="advanced-actions">
      <summary>更多操作</summary>
      <div v-if="canMaintain" class="index-actions">
        <el-button :disabled="unavailable || !context" @click="emit('operate','CONTENT')">重建内容索引</el-button>
        <el-button :disabled="unavailable || !context" @click="emit('operate','GRAPH')">重建代码图谱</el-button>
        <el-button :disabled="unavailable || !context || !contentReady" @click="emit('vectors')">构建向量索引</el-button>
      </div>
      <el-button v-if="context && graphReady" link type="primary" @click="emit('read','atlas')">打开此分支代码图谱</el-button>
      <el-button v-if="context" link @click="emit('copy')">复制 MCP 参数</el-button>
      <p class="scope-note">同步只更新此分支。一键准备包含同步、内容索引和图谱；向量索引按需构建。</p>
    </details>
    <div v-if="currentJobs.length" class="branch-task-list" aria-label="分支任务">
      <div v-for="job in currentJobs" :key="job.id" class="branch-task">
        <strong>{{ labels[job.kind] }}</strong><span>{{ statuses[job.status] }} · {{ stages[job.stage] ?? job.stage }}</span>
        <small>{{ job.snapshotId ? '快照 '+job.snapshotId.slice(0,8) : '等待锁定提交' }}{{ job.snapshotId && job.snapshotId!==branch.snapshotId?' · 历史版本任务':'' }}</small>
        <p v-if="job.error" class="task-error">{{ job.error }}</p>
      </div>
    </div>
  </section>
</template>
<style scoped>
.branch-index-panel{border:1px solid #dbe3ec;border-radius:6px;overflow:hidden;color:#334155}
.snapshot-strip{display:flex;align-items:center;flex-wrap:wrap;gap:12px;padding:14px 16px;background:#eff6ff;border-left:3px solid #2563eb}
.snapshot-strip>div{display:grid;gap:4px}.scope-label{font-size:11px;color:#68778a}.snapshot-strip code{font:13px Consolas,monospace}.snapshot-strip small{font-size:12px;color:#68778a}
.index-states{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));padding:16px;gap:16px}
.index-states>div{display:grid;gap:5px}.index-states span,.index-states small{font-size:12px;color:#68778a}.index-states strong{font-size:14px;color:#8a5a20}.index-states [data-ready=true] strong{color:#275f4a}
.index-actions{display:flex;flex-wrap:wrap;gap:8px;padding:0 16px}.index-actions :deep(.el-button+.el-button){margin-left:0}
.scope-note{font-size:12px;line-height:1.7;color:#68778a;margin:12px 16px}
.branch-index-panel>:deep(.el-button){margin:0 16px 14px}
.branch-task-list{border-top:1px solid #dbe3ec;padding:0 16px}.branch-task{display:flex;flex-wrap:wrap;align-items:center;gap:10px;padding:10px 0;font-size:12px}.branch-task small{color:#68778a}.task-error{flex-basis:100%;color:#b44242;margin:0}
@media(max-width:760px){.index-states{grid-template-columns:repeat(2,minmax(0,1fr))}}
.advanced-actions { margin: 14px 16px; border-top: 1px solid #dbe3ec; padding-top: 12px; }
.advanced-actions summary { cursor: pointer; color: #2563eb; margin-bottom: 12px; }
.advanced-actions .index-actions { padding: 0; }
</style>
