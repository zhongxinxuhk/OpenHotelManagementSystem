/**
 * 当前门店上下文（S3-01）。
 *
 * 背景：后端几乎所有业务接口都是 `/api/v1/hotels/{hotelId}/...` 形状，
 * 而一个身份可能有多家门店。前端必须有一个"当前门店"的全局状态，
 * 各页面从这里取 hotelId，并在切换门店时**清空旧查询条件与缓存**（前端规范 §8）。
 *
 * 为什么放在 initialState 而不是独立 store：门店选择要参与路由守卫
 * （没有门店范围时不该进业务页），放进 initialState 才能在 getInitialState 里判断。
 */
import { useModel } from '@umijs/max';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { type HotelView, listHotels } from '@/services/pms/hotel';

/** 门店选择持久化键：刷新页面后仍停在同一门店，避免每次都重选。 */
const SELECTED_HOTEL_KEY = 'pms.selected.hotelId';

function readSelectedHotelId(): string | null {
  try {
    return localStorage.getItem(SELECTED_HOTEL_KEY);
  } catch {
    return null;
  }
}

function writeSelectedHotelId(hotelId: string | null): void {
  try {
    if (hotelId) {
      localStorage.setItem(SELECTED_HOTEL_KEY, hotelId);
    } else {
      localStorage.removeItem(SELECTED_HOTEL_KEY);
    }
  } catch {
    // 存储不可用时仅影响"记住选择"，不影响本次会话使用。
  }
}

export type HotelContextValue = {
  /** 当前身份可操作的门店清单。 */
  hotels: HotelView[];
  /** 当前选中的门店；未选择时为 null。 */
  currentHotel: HotelView | null;
  currentHotelId: string | null;
  /** 门店清单是否还在加载。 */
  loading: boolean;
  /** 门店清单加载失败时的原因。 */
  error?: string;
  /** 切换门店。 */
  setCurrentHotelId: (hotelId: string | null) => void;
  /** 重新拉取门店清单（例如权限变更后）。 */
  reload: () => Promise<void>;
};

/**
 * 门店上下文 Hook。
 *
 * @param enabled 是否启用（例如未登录时不该发请求）。
 */
export function useHotelContext(enabled = true): HotelContextValue {
  const [hotels, setHotels] = useState<HotelView[]>([]);
  const [currentHotelId, setCurrentHotelIdState] = useState<string | null>(
    null,
  );
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  const load = useCallback(async () => {
    if (!enabled) return;
    setLoading(true);
    setError(undefined);
    try {
      const list = await listHotels();
      setHotels(list);
      // 选中项要落在**当前范围内**：之前选的门店如果已被撤权，
      // 继续用它的 id 去请求只会得到一路 403。
      setCurrentHotelIdState((previous) => {
        const stillValid =
          previous && list.some((hotel) => hotel.id === previous);
        if (stillValid) return previous as string;
        const fallback = list.length > 0 ? (list[0].id as string) : null;
        writeSelectedHotelId(fallback);
        return fallback;
      });
    } catch (e) {
      const problem = e as { response?: { data?: { detail?: string } } };
      setError(problem?.response?.data?.detail ?? '门店清单加载失败');
      setHotels([]);
      setCurrentHotelIdState(null);
    } finally {
      setLoading(false);
    }
  }, [enabled]);

  useEffect(() => {
    if (!enabled) return;
    // 首次进入时优先用上次的选择；若它已不在范围内，load 里的回退逻辑会纠正。
    const stored = readSelectedHotelId();
    if (stored) {
      setCurrentHotelIdState(stored);
    }
    void load();
  }, [enabled, load]);

  const setCurrentHotelId = useCallback((hotelId: string | null) => {
    setCurrentHotelIdState(hotelId);
    writeSelectedHotelId(hotelId);
  }, []);

  const currentHotel = useMemo(
    () => hotels.find((hotel) => hotel.id === currentHotelId) ?? null,
    [hotels, currentHotelId],
  );

  return {
    hotels,
    currentHotel,
    currentHotelId,
    loading,
    error,
    setCurrentHotelId,
    reload: load,
  };
}

/**
 * 只读版本：需要在组件外读当前门店时用（例如 ProLayout 顶栏）。
 *
 * 这里刻意不做成全局 store——umi 的 initialState 已经是全局状态载体，
 * 再引入一层 store 会让"门店切换要清哪些缓存"更难追踪。
 */
export function useCurrentHotelId(): string | null {
  const { initialState } = useModel('@@initialState');
  const hotelIds = (
    initialState?.currentUser as { hotelIds?: string[] } | undefined
  )?.hotelIds;
  return hotelIds?.[0] ?? null;
}
