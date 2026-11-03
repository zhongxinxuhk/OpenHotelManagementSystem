/**
 * 对账工单服务（S3-01）。
 *
 * 对应后端 `ReconcileController`，
 * operationId：getReconcileSummary / listReconcileFindings / listReconcileRuns /
 * runReconcile / repairReconcileFinding / ignoreReconcileFinding。
 */
import { request } from '@umijs/max';
import type {
  FindingView,
  ReconcileRunReport,
  ReconcileRunView,
  RepairResult,
  SummaryView,
} from './types';

export type {
  FindingView,
  ReconcileRunReport,
  ReconcileRunView,
  RepairResult,
  SummaryView,
};


/**
 * 待处理概览。
 *
 * `lastRun` 为 null 表示**还没跑过对账**，界面必须显示"未对账"，
 * 而不是"0 条差异"——后者会被当成"一切正常"。
 */
export async function getReconcileSummary(hotelId: string) {
  return request<SummaryView>(`/api/v1/hotels/${hotelId}/reconcile/summary`, {
    method: 'GET',
    skipErrorHandler: true,
  });
}

/** 差异工单：status=OPEN（默认，未结）或 SETTLED（已结，供复盘）。 */
export async function listReconcileFindings(
  hotelId: string,
  params?: { status?: string; severity?: string; limit?: number },
) {
  return request<FindingView[]>(`/api/v1/hotels/${hotelId}/reconcile/findings`, {
    method: 'GET',
    params,
    skipErrorHandler: true,
  });
}

/** 最近的批次，最新在前。 */
export async function listReconcileRuns(hotelId: string, params?: { limit?: number }) {
  return request<ReconcileRunView[]>(`/api/v1/hotels/${hotelId}/reconcile/runs`, {
    method: 'GET',
    params,
    skipErrorHandler: true,
  });
}

/** 人工触发一轮对账。窗口长度受 62 天上限约束。 */
export async function runReconcile(
  hotelId: string,
  params: { from: string; to: string; autoRepair?: boolean },
) {
  return request<ReconcileRunReport>(`/api/v1/hotels/${hotelId}/reconcile/run`, {
    method: 'POST',
    params,
    skipErrorHandler: true,
  });
}

/**
 * 定向修复一条派生差异。
 *
 * 事实类差异调用会得到 409 —— 系统不知道该改哪一边，假装修好只会把矛盾藏起来。
 */
export async function repairReconcileFinding(hotelId: string, findingId: string) {
  return request<RepairResult>(
    `/api/v1/hotels/${hotelId}/reconcile/findings/${findingId}/repair`,
    { method: 'POST', skipErrorHandler: true },
  );
}

/** 忽略一条差异（必须给原因）。操作者取自服务端会话，不接受前端自报。 */
export async function ignoreReconcileFinding(
  hotelId: string,
  findingId: string,
  reason: string,
) {
  return request<RepairResult>(
    `/api/v1/hotels/${hotelId}/reconcile/findings/${findingId}/ignore`,
    { method: 'POST', params: { reason }, skipErrorHandler: true },
  );
}
