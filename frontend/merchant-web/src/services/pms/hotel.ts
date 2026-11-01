/**
 * 门店服务（S3-01）。
 *
 * 对应后端 `HotelScopeController`，operationId：listHotels。
 */
import { request } from '@umijs/max';
import type {
  HotelView,
} from './types';

export type {
  HotelView,
};


/**
 * 当前身份可操作的门店清单。
 *
 * 返回的是**数据范围**，不是租户全量：范围外的门店不会出现。
 * 前端所有业务接口都是 `/api/v1/hotels/{hotelId}/...` 形状，
 * 这个清单决定了页面上门店选择器里能看到什么。
 */
export async function listHotels() {
  return request<HotelView[]>('/api/v1/hotels', {
    method: 'GET',
    skipErrorHandler: true,
  });
}
