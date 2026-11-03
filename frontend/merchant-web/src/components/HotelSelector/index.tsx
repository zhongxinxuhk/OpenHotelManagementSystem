/**
 * 门店选择器（S3-01）。
 *
 * 顶栏的门店切换器。所有业务页的 hotelId 都来自这里。
 *
 * 切换门店时必须清空旧查询条件与列表缓存（前端规范 §8）：
 * 否则会出现"在 A 店筛选出的订单列表，切到 B 店后还挂着 A 店的筛选条件"。
 * 本组件通过 key 变化让使用方重建列表来达成这一点。
 */
import { Select } from 'antd';
import React from 'react';
import { useHotelContext } from './context';

export type HotelSelectorProps = {
  /** 门店清单未就绪或加载失败时的占位文案。 */
  placeholder?: string;
  style?: React.CSSProperties;
  onChange?: (hotelId: string) => void;
};

const HotelSelector: React.FC<HotelSelectorProps> = ({
  placeholder = '选择门店',
  style,
  onChange,
}) => {
  const { hotels, currentHotelId, loading, setCurrentHotelId } =
    useHotelContext();

  // 没有门店范围：不是"加载中"，而是"这个账号没有任何门店权限"。
  // 两者必须区分，否则用户会一直等一个永远不来的下拉框。
  if (!loading && hotels.length === 0) {
    return (
      <span style={{ color: '#8a8f99', fontSize: 12 }}>当前账号未分配门店</span>
    );
  }

  return (
    <Select
      value={currentHotelId ?? undefined}
      loading={loading}
      placeholder={placeholder}
      style={{ minWidth: 180, ...style }}
      onChange={(hotelId: string) => {
        setCurrentHotelId(hotelId);
        onChange?.(hotelId);
      }}
      options={hotels
        .filter((hotel) => hotel.id !== undefined)
        .map((hotel) => ({
          value: hotel.id ?? '',
          label: `${hotel.name ?? ''}（${hotel.code ?? ''}）`,
        }))}
      showSearch
      optionFilterProp="label"
    />
  );
};

export default HotelSelector;
