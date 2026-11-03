/**
 * 今日工作台（S3-01）。
 *
 * 页面定位：**今日工作台**，不是欢迎页（前端规范 §7.1）。
 * 内容是当天要处理的事：今日到店/离店/在住/待确认。
 *
 * 指标口径（写在副标题与说明里，避免数字被误读）：
 * · 到店/离店 —— 入住日 / 离店日等于今天的订单；
 * · 在住 —— 已确认且跨今日的订单数。
 *   入住/离店的完整流程属 S3-04，后端目前没有"在住清单"接口，
 *   所以这里用订单状态近似并标注口径，不编造精确数字。
 */

import type { ProColumns } from '@ant-design/pro-components';
import {
  PageContainer,
  ProCard,
  ProTable,
  StatisticCard,
} from '@ant-design/pro-components';
import { Alert, Empty, Typography } from 'antd';
import dayjs from 'dayjs';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useHotelContext } from '@/components/HotelSelector/context';
import { ReservationStatusTag, SourceTypeTag } from '@/components/StatusTag';
import {
  listReservations,
  type ReservationSummary,
} from '@/services/pms/reservation';

type TodoRow = {
  id: string;
  reservationNo: string;
  sourceType: string;
  checkIn: string;
  checkOut: string;
  lineCount: number;
  status: string;
};

/**
 * 计算今日切片。
 *
 * 半开区间 [今天, 明天)：入住日 == 今天的算到店，离店日 == 今天的算离店。
 * ISO 日期的字典序即时间序，直接用字符串比较。
 */
function sliceToday(
  items: ReservationSummary[],
  today: string,
  tomorrow: string,
) {
  const arrivals = items.filter((item) => item.checkIn === today);
  const departures = items.filter((item) => item.checkOut === today);
  const pending = items.filter((item) => item.status === 'PENDING');
  const inHouse = items.filter(
    (item) =>
      item.status === 'CONFIRMED' &&
      item.checkIn !== undefined &&
      item.checkOut !== undefined &&
      item.checkIn < today &&
      item.checkOut > tomorrow,
  );
  return { arrivals, departures, pending, inHouse };
}

const Welcome: React.FC = () => {
  const {
    currentHotelId,
    currentHotel,
    loading: hotelLoading,
  } = useHotelContext();
  const [items, setItems] = useState<ReservationSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  const today = dayjs().format('YYYY-MM-DD');
  const tomorrow = dayjs().add(1, 'day').format('YYYY-MM-DD');

  const load = useCallback(async () => {
    if (!currentHotelId) {
      setItems([]);
      return;
    }
    setLoading(true);
    setError(undefined);
    try {
      // 取一个足够宽的窗口，让今日切片落在里面。
      const from = dayjs().subtract(14, 'day').format('YYYY-MM-DD');
      const to = dayjs().add(16, 'day').format('YYYY-MM-DD');
      const page = await listReservations(currentHotelId, {
        stayFrom: from,
        stayTo: to,
        page: 1,
        size: 100,
      });
      setItems(page.items ?? []);
    } catch (e) {
      const problem = e as { response?: { data?: { detail?: string } } };
      setError(problem?.response?.data?.detail ?? '订单数据加载失败');
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, [currentHotelId]);

  useEffect(() => {
    void load();
  }, [load]);

  const metrics = useMemo(
    () => sliceToday(items, today, tomorrow),
    [items, today, tomorrow],
  );

  const todoRows: TodoRow[] = useMemo(
    () =>
      [...metrics.arrivals, ...metrics.pending]
        .filter(
          (item) =>
            item.id !== undefined &&
            item.reservationNo !== undefined &&
            item.checkIn !== undefined &&
            item.checkOut !== undefined,
        )
        .slice(0, 10)
        .map((item) => ({
          id: item.id ?? '',
          reservationNo: item.reservationNo ?? '',
          sourceType: item.sourceType ?? '',
          checkIn: item.checkIn ?? '',
          checkOut: item.checkOut ?? '',
          lineCount: item.lineCount ?? 0,
          status: item.status ?? '',
        })),
    [metrics.arrivals, metrics.pending],
  );

  const columns: ProColumns<TodoRow>[] = [
    { title: '订单号', dataIndex: 'reservationNo', copyable: true },
    {
      title: '来源',
      dataIndex: 'sourceType',
      render: (_, row) => <SourceTypeTag status={row.sourceType} />,
    },
    { title: '房数', dataIndex: 'lineCount' },
    { title: '入住日', dataIndex: 'checkIn' },
    { title: '离店日', dataIndex: 'checkOut' },
    {
      title: '状态',
      dataIndex: 'status',
      render: (_, row) => <ReservationStatusTag status={row.status} />,
    },
  ];

  if (!currentHotelId && !hotelLoading) {
    return (
      <PageContainer title="今日工作台">
        <Empty description="当前账号未分配门店，无可操作的数据范围" />
      </PageContainer>
    );
  }

  return (
    <PageContainer
      title="今日工作台"
      subTitle={`营业日 ${today}${currentHotel ? ` · ${currentHotel.name}` : ''}`}
    >
      {error && (
        <Alert
          type="error"
          showIcon
          message="数据加载失败"
          description={error}
          style={{ marginBottom: 16 }}
        />
      )}

      <ProCard gutter={16} ghost wrap>
        <ProCard colSpan={{ xs: 24, sm: 12, lg: 6 }}>
          <StatisticCard
            statistic={{
              title: '今日到店',
              value: metrics.arrivals.length,
              description: (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  入住日等于今日的订单
                </Typography.Text>
              ),
            }}
          />
        </ProCard>
        <ProCard colSpan={{ xs: 24, sm: 12, lg: 6 }}>
          <StatisticCard
            statistic={{
              title: '今日离店',
              value: metrics.departures.length,
              description: (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  离店日等于今日的订单
                </Typography.Text>
              ),
            }}
          />
        </ProCard>
        <ProCard colSpan={{ xs: 24, sm: 12, lg: 6 }}>
          <StatisticCard
            statistic={{
              title: '在住订单',
              value: metrics.inHouse.length,
              description: (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  已确认且跨今日的订单
                </Typography.Text>
              ),
            }}
          />
        </ProCard>
        <ProCard colSpan={{ xs: 24, sm: 12, lg: 6 }}>
          <StatisticCard
            statistic={{
              title: '待确认',
              value: metrics.pending.length,
              description: (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  状态为待确认的订单
                </Typography.Text>
              ),
            }}
          />
        </ProCard>
      </ProCard>

      <ProCard title="今日到店与待确认" style={{ marginTop: 16 }}>
        <ProTable<TodoRow>
          rowKey="id"
          columns={columns}
          dataSource={todoRows}
          loading={loading}
          search={false}
          options={false}
          pagination={false}
          locale={{
            emptyText: '今日无到店订单，也没有待确认订单',
          }}
        />
      </ProCard>
    </PageContainer>
  );
};

export default Welcome;
