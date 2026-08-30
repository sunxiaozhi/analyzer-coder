export type TaskStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'CANCEL_REQUESTED'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELED';

export type TaskType = 'FULL' | 'INCREMENTAL' | 'CODEGRAPH' | 'KNOWLEDGE_DRIFT';

export interface Task {
  id: string;
  repositoryId: string;
  type: TaskType;
  status: TaskStatus;
  currentStep: string | null;
  executionMode: 'FULL' | 'INCREMENTAL' | null;
  fallbackReason: string | null;
  failureCode: string | null;
  errorMessage: string | null;
  startedAt: string | null;
  heartbeatAt: string | null;
  timeoutAt: string | null;
  finishedAt: string | null;
  createdAt: string;
}
