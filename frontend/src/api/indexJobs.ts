import { request } from '@/api/http';
import type { PageResult } from '@/types/pagination';
import type { Task } from '@/types/tasks';

export function listTasks(params: { pageNum: number; pageSize: number }): Promise<PageResult<Task>> {
  const search = new URLSearchParams({ pageNum: String(params.pageNum), pageSize: String(params.pageSize) });
  return request<PageResult<Task>>(`/api/index-jobs/page?${search}`);
}

export function cancelTask(taskId: string): Promise<Task> {
  return request<Task>(`/api/index-jobs/${taskId}/cancel`, { method: 'POST' });
}

export function retryTask(taskId: string): Promise<Task> {
  return request<Task>(`/api/index-jobs/${taskId}/retries`, { method: 'POST' });
}