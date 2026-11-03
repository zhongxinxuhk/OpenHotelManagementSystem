/**
 * 原交易结果查询服务（S3-01）。
 *
 * 对应后端 `OperationController`，
 * operationId：getOperationById / getOperationByKey。
 *
 * 用途：写操作返回 outcome=UNKNOWN（结果不确定）时，用这里的接口查回原交易结果，
 * 而不是让操作员重试 —— 重试会造成重复下单与真实库存损失。
 */
import { request } from '@umijs/max';
import type {
  OperationView,
} from './types';

export type {
  OperationView,
};


/** 按 operationId 查。 */
export async function getOperationById(operationId: string) {
  return request<OperationView>(`/api/v1/operations/${operationId}`, {
    method: 'GET',
    skipErrorHandler: true,
  });
}

/** 按幂等键查（客户端只知道自己的键时用这个）。 */
export async function getOperationByKey(params: {
  hotelId: string;
  namespace: string;
  command: string;
  key: string;
}) {
  return request<OperationView>('/api/v1/operations/lookup', {
    method: 'GET',
    params,
    skipErrorHandler: true,
  });
}
