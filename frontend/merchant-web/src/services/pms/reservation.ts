/**
 * 订单服务（S3-01）。
 *
 * 对应后端 `ReservationController` 与 `ReservationQueryController`，
 * operationId：createReservation / confirmReservation / cancelReservation /
 * markReservationNoShow / listReservations / getReservationDetail。
 */
import { request } from '@umijs/max';
import type {
  CreateReservationRequest,
  LifecycleRequest,
  PageViewReservationSummary,
  ReservationDetailView,
  ReservationSummary,
  ReservationView,
} from './types';

export type {
  CreateReservationRequest,
  LifecycleRequest,
  PageViewReservationSummary,
  ReservationDetailView,
  ReservationSummary,
  ReservationView,
};


/** 订单状态（与后端枚举一致）。 */
export const RESERVATION_STATUS = [
  'PENDING',
  'CONFIRMED',
  'CHECKED_IN',
  'CHECKED_OUT',
  'CANCELLED',
  'EXPIRED',
  'NO_SHOW',
] as const;

/** 订单来源（与后端枚举一致）。 */
export const RESERVATION_SOURCE = ['FRONTDESK', 'DIRECT', 'OTA', 'MINIPROGRAM'] as const;

/** 详情里可执行动作的取值。 */
export const RESERVATION_ACTIONS = ['CONFIRM', 'CANCEL', 'NO_SHOW'] as const;

export interface ListReservationsParams {
  /** 可重复：后端按列表收 status。 */
  status?: string[];
  sourceType?: string;
  /** 订单号前缀（不是模糊匹配）。 */
  no?: string;
  roomCode?: string;
  /** 住宿区间筛选，与 stayTo 成对出现，按区间交集命中。 */
  stayFrom?: string;
  stayTo?: string;
  page?: number;
  size?: number;
}

/**
 * 订单列表。
 *
 * 数组参数需要展开成重复键（`status=A&status=B`），
 * axios 默认把数组序列化成 `status[]=A`，后端绑定不到 —— 所以这里手工拼。
 */
export async function listReservations(
  hotelId: string,
  params?: ListReservationsParams,
): Promise<PageViewReservationSummary> {
  const query = new URLSearchParams();
  if (params) {
    params.status?.forEach((value) => query.append('status', value));
    if (params.sourceType) query.append('sourceType', params.sourceType);
    if (params.no) query.append('no', params.no);
    if (params.roomCode) query.append('roomCode', params.roomCode);
    if (params.stayFrom) query.append('stayFrom', params.stayFrom);
    if (params.stayTo) query.append('stayTo', params.stayTo);
    if (params.page) query.append('page', String(params.page));
    if (params.size) query.append('size', String(params.size));
  }
  const suffix = query.toString();
  return request<PageViewReservationSummary>(
    `/api/v1/hotels/${hotelId}/reservations${suffix ? `?${suffix}` : ''}`,
    { method: 'GET', skipErrorHandler: true },
  );
}

/**
 * 订单详情。
 *
 * 返回里带 `allowedActions`（后端状态机守卫算出的可执行动作）与 `version`
 * （状态变更的期望版本）。前端据此显示按钮，**不自己判断**能不能操作。
 */
export async function getReservationDetail(hotelId: string, reservationId: string) {
  return request<ReservationDetailView>(
    `/api/v1/hotels/${hotelId}/reservations/${reservationId}`,
    { method: 'GET', skipErrorHandler: true },
  );
}

/**
 * 创建订单（锁房）。
 *
 * `idempotencyKey` 由调用方生成并在重试时复用 —— 前端规范 §4.1。
 * 同键不同内容会被后端拒绝（幂等键持久化且同键不同内容拒绝）。
 * `X-Caller-Namespace` 区分调用方命名空间，避免不同客户端的键互相顶替。
 */
export async function createReservation(
  hotelId: string,
  body: CreateReservationRequest,
  idempotencyKey: string,
) {
  return request<ReservationView>(`/api/v1/hotels/${hotelId}/reservations`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
      'X-Caller-Namespace': 'frontend',
    },
    data: body,
    skipErrorHandler: true,
  });
}

/** 确认订单。`expectedVersion` 是乐观锁，版本不匹配时后端返回 409。 */
export async function confirmReservation(
  hotelId: string,
  reservationId: string,
  body: LifecycleRequest,
) {
  return request<ReservationView>(
    `/api/v1/hotels/${hotelId}/reservations/${reservationId}/confirm`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      skipErrorHandler: true,
    },
  );
}

/** 取消订单。已入住的订单一律拒绝普通取消。 */
export async function cancelReservation(
  hotelId: string,
  reservationId: string,
  body: LifecycleRequest,
) {
  return request<ReservationView>(
    `/api/v1/hotels/${hotelId}/reservations/${reservationId}/cancel`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      skipErrorHandler: true,
    },
  );
}

/** 标记 No-show。是否计费仍待 DEC-08 定案，后端目前只做状态迁移与占用释放。 */
export async function markReservationNoShow(
  hotelId: string,
  reservationId: string,
  body: LifecycleRequest,
) {
  return request<ReservationView>(
    `/api/v1/hotels/${hotelId}/reservations/${reservationId}/no-show`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      skipErrorHandler: true,
    },
  );
}
