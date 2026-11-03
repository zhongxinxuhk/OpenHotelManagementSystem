/**
 * 运维处置服务（S3-01）。
 *
 * 对应后端 `OpsController`，
 * operationId：getOpsPending / listDeadLetters / listExternalTasks /
 * listOpsInterventions / replayDeadLetter / dismissDeadLetter /
 * replayExternalTask / resolveExternalTask。
 */
import { request } from '@umijs/max';
import type {
  DeadLetter,
  ExternalTask,
  OpsIntervention,
  PendingSummary,
  ReplayResult,
} from './types';

export type {
  DeadLetter,
  ExternalTask,
  OpsIntervention,
  PendingSummary,
  ReplayResult,
};


/** 待处置队列概览：死信、卡住的外部任务、挂起的 Inbox 事件。 */
export async function getOpsPending() {
  return request<PendingSummary>('/api/v1/ops/pending', {
    method: 'GET',
    skipErrorHandler: true,
  });
}

/** 待处理死信。 */
export async function listDeadLetters(params?: { limit?: number }) {
  return request<DeadLetter[]>('/api/v1/ops/dead-letters', {
    method: 'GET',
    params,
    skipErrorHandler: true,
  });
}

/** 卡住的外部任务。status 默认 DEAD。 */
export async function listExternalTasks(params?: { status?: string; limit?: number }) {
  return request<ExternalTask[]>('/api/v1/ops/external-tasks', {
    method: 'GET',
    params,
    skipErrorHandler: true,
  });
}

/** 处置留痕记录。 */
export async function listOpsInterventions(params?: { limit?: number }) {
  return request<OpsIntervention[]>('/api/v1/ops/interventions', {
    method: 'GET',
    params,
    skipErrorHandler: true,
  });
}

/** 重放死信：会**再次向外部系统发请求**，因此必须给原因并留痕。 */
export async function replayDeadLetter(deadLetterId: string, reason: string) {
  return request<ReplayResult>(`/api/v1/ops/dead-letters/${deadLetterId}/replay`, {
    method: 'POST',
    params: { reason },
    skipErrorHandler: true,
  });
}

/** 忽略死信：确认不再重放。 */
export async function dismissDeadLetter(deadLetterId: string, reason: string) {
  return request<ReplayResult>(`/api/v1/ops/dead-letters/${deadLetterId}/dismiss`, {
    method: 'POST',
    params: { reason },
    skipErrorHandler: true,
  });
}

/**
 * 重放外部任务。
 *
 * 高风险动作需要复核人：`reviewer` 传入第二个操作人的名字，
 * 后端会把它写进处置留痕。重放保留原幂等键。
 */
export async function replayExternalTask(
  taskId: string,
  reason: string,
  reviewer?: string,
) {
  return request<ReplayResult>(`/api/v1/ops/external-tasks/${taskId}/replay`, {
    method: 'POST',
    params: { reason, reviewer },
    skipErrorHandler: true,
  });
}

/** 人工结案外部任务。 */
export async function resolveExternalTask(
  taskId: string,
  params: { succeeded: boolean; reason: string; resultRef?: string },
) {
  return request<ReplayResult>(`/api/v1/ops/external-tasks/${taskId}/resolve`, {
    method: 'POST',
    params: {
      succeeded: String(params.succeeded),
      reason: params.reason,
      resultRef: params.resultRef,
    },
    skipErrorHandler: true,
  });
}
