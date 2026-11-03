/**
 * 库存与可售服务（S3-01）。
 *
 * 对应后端 `InventoryController`，
 * operationId：getAvailability / rebuildInventory / reconcileInventory。
 */
import { request } from '@umijs/max';
import type {
  AvailabilityView,
  RebuildResult,
  ReconcileReport,
} from './types';

export type {
  AvailabilityView,
  RebuildResult,
  ReconcileReport,
};


export interface AvailabilityParams {
  roomTypeId?: string;
  /** 起始住宿日（含）。 */
  from: string;
  /** 结束住宿日（不含）。窗口上限 62 天，超限后端返回 400。 */
  to: string;
}

/** 每日余量 + 整段可售房间数。 */
export async function getAvailability(hotelId: string, params: AvailabilityParams) {
  return request<AvailabilityView>(`/api/v1/hotels/${hotelId}/availability`, {
    method: 'GET',
    params,
    skipErrorHandler: true,
  });
}

/** 重建房态投影（运维动作，幂等）。 */
export async function rebuildInventory(hotelId: string, params: AvailabilityParams) {
  return request<RebuildResult>(`/api/v1/hotels/${hotelId}/inventory/rebuild`, {
    method: 'POST',
    params,
    skipErrorHandler: true,
  });
}

/** 三方对账报告：权威重算 vs 投影 vs 流水。 */
export async function reconcileInventory(hotelId: string, params: AvailabilityParams) {
  return request<ReconcileReport>(`/api/v1/hotels/${hotelId}/inventory/reconcile`, {
    method: 'GET',
    params,
    skipErrorHandler: true,
  });
}
