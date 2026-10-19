export interface TaskTiming {
  status: string;
  createdAt?: string | null;
  updatedAt?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
}

export type DurationMode = 'execution' | 'total';

export function formatDuration(milliseconds: number): string {
  let seconds = Math.floor(milliseconds / 1000);
  if (!Number.isFinite(seconds) || seconds < 0) return '—';
  if (seconds === 0) return '不足1秒';
  const parts: string[] = [];
  for (const [size, unit] of [[86400, '天'], [3600, '小时'], [60, '分'], [1, '秒']] as const) {
    const amount = Math.floor(seconds / size);
    if (amount) parts.push(`${amount}${unit}`);
    seconds %= size;
  }
  return parts.join('');
}

function timestamp(value?: string | null): number {
  return value ? Date.parse(value) : NaN;
}

export function taskDuration(task: TaskTiming, mode: DurationMode, now: number) {
  const active = ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(task.status);
  const waiting = mode === 'execution' && task.status === 'QUEUED' && !task.startedAt;
  const start = timestamp(mode === 'total' || waiting ? task.createdAt : task.startedAt);
  const end = mode === 'total' ? (active ? now : timestamp(task.updatedAt))
    : task.finishedAt ? timestamp(task.finishedAt) : active ? now : NaN;
  const live = active && Number.isFinite(start) && (mode === 'total' || !task.finishedAt);
  const title = mode === 'total' ? '从创建到结束的总耗时，包含排队等待时间'
    : waiting ? '尚未开始执行，显示排队等待时间' : '从开始执行到结束的耗时，不含排队等待时间';
  if (mode === 'execution' && !active && !task.startedAt) {
    return { text: '—', title: '任务未记录开始执行时间', live: false };
  }
  const formatted = formatDuration(end - start);
  return { text: waiting && formatted !== '—' ? `排队 ${formatted}` : formatted, title, live };
}
