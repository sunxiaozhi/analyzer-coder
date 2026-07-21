import { request } from '@/api/http';
import type { Task } from '@/types/tasks';

export function listTasks(): Promise<Task[]> {
  return request<Task[]>('/api/index-jobs');
}

export function cancelTask(taskId: string): Promise<Task> {
  return request<Task>(`/api/index-jobs/${taskId}/cancel`, { method: 'POST' });
}

export function retryTask(taskId: string): Promise<Task> {
  return request<Task>(`/api/index-jobs/${taskId}/retries`, { method: 'POST' });
}
