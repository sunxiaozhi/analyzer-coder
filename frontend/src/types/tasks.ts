export type TaskStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'CANCEL_REQUESTED'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELED';

export type TaskType = 'FULL' | 'INCREMENTAL';

export interface Task {
  id: string;
  repositoryId: string;
  type: TaskType;
  status: TaskStatus;
  currentStep: string | null;
  errorMessage: string | null;
  startedAt: string | null;
  finishedAt: string | null;
  createdAt: string;
}
