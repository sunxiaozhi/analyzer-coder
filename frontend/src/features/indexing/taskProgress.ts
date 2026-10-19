export type StepState = 'waiting' | 'running' | 'completed' | 'failed' | 'canceled' | 'blocked' | 'unknown';
export interface ProgressTask { kind: string; status: string; stage?: string | null }

const stageLabels: Record<string, string> = {
  queued: '等待执行', resolving: '确认目标版本', sync: '同步代码', publishing: '发布分支代码',
  indexing: '构建内容索引', graph: '构建代码图谱', embedding: '构建向量索引',
  scan_repository: '扫描代码文件', write_chunks: '写入内容索引', build_embeddings: '构建向量索引',
  prepare_codegraph: '准备代码图谱', copy_contentversion: '准备代码文件',
  building_codegraph: '构建代码图谱', build_codegraph: '构建代码图谱',
  inspect_codegraph: '检查图谱结果', publish_codegraph: '发布代码图谱',
  codegraph_published: '图谱已发布', prepare_knowledge_drift: '准备知识失效检查',
  check_knowledge_drift: '检查知识失效', knowledge_drift_completed: '知识检查已完成',
  timed_out: '执行超时', completed: '已完成',
};
const taskLabels: Record<string, string> = {
  SYNC: '同步代码', CONTENT: '内容索引', GRAPH: '代码图谱', VECTORS: '向量索引',
  FULL: '全量内容索引', INCREMENTAL: '增量内容索引', CODEGRAPH: '代码图谱', KNOWLEDGE_DRIFT: '知识失效检查',
};
function stageKey(stage?: string | null) { return stage?.split(':')[0]?.toLowerCase() ?? ''; }

export function taskStageLabel(stage: string | null | undefined, status: string): string {
  if (status === 'QUEUED') return '等待执行';
  if (status === 'SUCCEEDED') return '已完成';
  if (status === 'CANCELED') return '已取消';
  const label = stageLabels[stageKey(stage)];
  if (status === 'FAILED') {
    if (stageKey(stage) === 'timed_out') return '执行超时';
    if (stageKey(stage) === 'completed') return '任务收尾失败';
    return label ? `${label}失败` : '执行失败（未记录失败阶段）';
  }
  if (status === 'CANCEL_REQUESTED') return `${label ?? '正在执行'}（等待取消）`;
  if (stageKey(stage) === 'completed') return '正在完成收尾';
  return label ?? '正在执行（阶段未记录）';
}

export function taskProgress(task: ProgressTask) {
  const composite = task.kind === 'PREPARE';
  const labels = composite ? ['同步代码', '内容索引', '代码图谱'] : [taskLabels[task.kind] ?? '任务执行'];
  const key = stageKey(task.stage);
  const indices: Record<string, number> = { resolving: 0, sync: 0, publishing: 0, indexing: 1, graph: 2 };
  const current = composite ? indices[key] : 0;
  const finishing = composite && key === 'completed';
  const known = !composite || current !== undefined || finishing;
  let note = '';
  if (composite && task.status !== 'QUEUED' && task.status !== 'SUCCEEDED' && !known) {
    note = task.status === 'FAILED' ? '此任务未记录失败阶段，无法确定各步骤进度。' : '此任务未记录当前阶段，无法确定各步骤进度。';
  }
  if (finishing && task.status === 'FAILED') note = '各步骤已完成，任务收尾失败，请查看错误信息。';
  const steps = labels.map((label, index): { label: string; state: StepState } => {
    let state: StepState = 'unknown';
    if (task.status === 'SUCCEEDED') state = 'completed';
    else if (task.status === 'QUEUED') state = 'waiting';
    else if (finishing) state = 'completed';
    else if (known) {
      if (index < current) state = 'completed';
      else if (index > current) state = ['FAILED', 'CANCELED'].includes(task.status) ? 'blocked' : 'waiting';
      else if (task.status === 'FAILED') state = 'failed';
      else if (task.status === 'CANCELED') state = 'canceled';
      else if (['RUNNING', 'CANCEL_REQUESTED'].includes(task.status)) state = 'running';
    }
    return { label, state };
  });
  return { steps, note, current: taskStageLabel(task.stage, task.status) };
}
