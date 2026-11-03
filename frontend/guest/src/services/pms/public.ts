/**
 * 匿名公开目录服务（R-20261103-02）。
 *
 * 对应后端 `PublicCatalogController`（`/api/v1/public/**` 是全系统唯一匿名前缀）。
 * 无需登录令牌——住客在注册前就要能看到房型与可售。
 *
 * 语义（与后端硬约束对齐）：
 * · 定位用 (tenantCode, hotelCode)，不用内部 id；
 * · 租户/门店不存在或停用一律同一个 404（"门店不存在或未开放预订"）——
 *   页面按文案如实呈现，不做二次解释；
 * · 响应只含公开字段：门店名/时区/房型名/容量/可售布尔——
 *   没有总房数、已售数（"仅剩 3 间"是营销决策，不是后端该默认暴露的事实）。
 */
import { request } from '@umijs/max';
import type { PublicCatalogView } from './types';

export type { PublicCatalogView };

/**
 * 某门店在日期区间内的可售房型目录。
 *
 * @throws 404 门店不存在或未开放预订（与"未开启公开面"同形态）
 * @throws 400 日期区间不合法或超过 62 天
 */
export async function listPublicRoomTypes(
  tenantCode: string,
  hotelCode: string,
  from: string,
  to: string,
) {
  return request<PublicCatalogView>(
    `/api/v1/public/${tenantCode}/hotels/${hotelCode}/room-types`,
    {
      method: 'GET',
      params: { from, to },
      skipErrorHandler: true,
    },
  );
}
