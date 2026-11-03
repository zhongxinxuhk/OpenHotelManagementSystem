/**
 * 房态与可售（S3-01）。
 *
 * 覆盖后端 S1-12 的三个入口：可售查询、投影重建、三方对账报告。
 *
 * 口径说明（写在页面上，避免数字被误读）：
 * · 可售来自**权威记录实时计算**，不读投影表——投影可能有滞后，
 *   拿投影做可售判断会出现"页面说可售、下单被拒"；
 * · 余量表按房型 × 住宿日展开。
 */

import type { ProColumns } from '@ant-design/pro-components';
import {
  PageContainer,
  ProCard,
  ProFormSelect,
  ProTable,
  StatisticCard,
} from '@ant-design/pro-components';
import { Alert, App, Button, DatePicker, Space, Typography } from 'antd';
import dayjs from 'dayjs';
import React, { useCallback, useEffect, useState } from 'react';
import { useHotelContext } from '@/components/HotelSelector/context';
import { describeProblem, toProblem } from '@/services/pms/errors';
import {
  type AvailabilityView,
  getAvailability,
  type ReconcileReport,
  rebuildInventory,
  reconcileInventory,
} from '@/services/pms/inventory';
import { listRoomTypes, type RoomTypeView } from '@/services/pms/room';

type DayRow = {
  key: string;
  stayDate: string;
  roomTypeId: string;
  totalRooms: number;
  soldRooms: number;
  blockedRooms: number;
  availableRooms: number;
};

const AvailabilityPage: React.FC = () => {
  const { currentHotelId, currentHotel } = useHotelContext();
  const { message } = App.useApp();

  const [from, setFrom] = useState<string>(dayjs().format('YYYY-MM-DD'));
  const [to, setTo] = useState<string>(
    dayjs().add(7, 'day').format('YYYY-MM-DD'),
  );
  const [roomTypeId, setRoomTypeId] = useState<string | undefined>(undefined);
  const [roomTypes, setRoomTypes] = useState<RoomTypeView[]>([]);
  const [availability, setAvailability] = useState<AvailabilityView | null>(
    null,
  );
  const [report, setReport] = useState<ReconcileReport | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!currentHotelId) return;
    void listRoomTypes(currentHotelId)
      .then(setRoomTypes)
      .catch(() => setRoomTypes([]));
  }, [currentHotelId]);

  const load = useCallback(async () => {
    if (!currentHotelId) return;
    setLoading(true);
    try {
      const [view, reconcile] = await Promise.all([
        getAvailability(currentHotelId, { from, to, roomTypeId }),
        reconcileInventory(currentHotelId, { from, to, roomTypeId }),
      ]);
      setAvailability(view);
      setReport(reconcile);
    } catch (error) {
      message.error(describeProblem(toProblem(error)));
      setAvailability(null);
      setReport(null);
    } finally {
      setLoading(false);
    }
  }, [currentHotelId, from, to, roomTypeId, message]);

  useEffect(() => {
    void load();
  }, [load]);

  const rows: DayRow[] =
    availability?.days
      ?.filter((day) => day.stayDate !== undefined && day.roomTypeId !== undefined)
      ?.map((day) => ({
        key: `${day.stayDate}-${day.roomTypeId}`,
        stayDate: day.stayDate ?? '',
        roomTypeId: day.roomTypeId ?? '',
        totalRooms: day.totalRooms ?? 0,
        soldRooms: day.soldRooms ?? 0,
        blockedRooms: day.blockedRooms ?? 0,
        availableRooms: day.availableRooms ?? 0,
      })) ?? [];

  const columns: ProColumns<DayRow>[] = [
    { title: '住宿日', dataIndex: 'stayDate', width: 120 },
    {
      title: '房型',
      dataIndex: 'roomTypeId',
      render: (_, row) =>
        roomTypes.find((type) => type.id === row.roomTypeId)?.name ??
        row.roomTypeId,
    },
    { title: '总房数', dataIndex: 'totalRooms', width: 100 },
    { title: '已售', dataIndex: 'soldRooms', width: 100 },
    { title: '维修占用', dataIndex: 'blockedRooms', width: 100 },
    { title: '可售', dataIndex: 'availableRooms', width: 100 },
  ];

  const bookable = availability?.bookable ?? [];

  return (
    <PageContainer
      title="房态与可售"
      subTitle={
        currentHotel
          ? `${currentHotel.name} · ${from} 至 ${to}（半开区间，共 ${availability?.nights ?? 0} 晚）`
          : undefined
      }
      extra={[
        <Space key="filter">
          <DatePicker
            value={dayjs(from)}
            onChange={(value) => value && setFrom(value.format('YYYY-MM-DD'))}
            allowClear={false}
          />
          <DatePicker
            value={dayjs(to)}
            onChange={(value) => value && setTo(value.format('YYYY-MM-DD'))}
            allowClear={false}
          />
          <ProFormSelect
            noStyle
            width={160}
            fieldProps={{
              value: roomTypeId,
              onChange: setRoomTypeId,
              options: [
                { value: undefined, label: '全部房型' },
                ...roomTypes.map((type) => ({
                  value: type.id ?? '',
                  label: type.name ?? '',
                })),
              ],
            }}
          />
          <Button
            type="primary"
            onClick={async () => {
              if (!currentHotelId) return;
              try {
                const result = await rebuildInventory(currentHotelId, {
                  from,
                  to,
                  roomTypeId,
                });
                message.success(
                  `投影已重建：写入 ${result.writtenRows ?? 0} 行，删除 ${result.deletedRows ?? 0} 行`,
                );
                void load();
              } catch (error) {
                message.error(describeProblem(toProblem(error)));
              }
            }}
          >
            重建投影
          </Button>
        </Space>,
      ]}
    >
      <ProCard gutter={16} ghost wrap>
        <ProCard colSpan={{ xs: 24, sm: 12, lg: 6 }}>
          <StatisticCard
            statistic={{
              title: '整段可售房数',
              value: bookable.reduce(
                (sum, item) => sum + (item.bookableRooms ?? 0),
                0,
              ),
              description: (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  区间内每一晚都可用的房间数之和
                </Typography.Text>
              ),
            }}
          />
        </ProCard>
        <ProCard colSpan={{ xs: 24, sm: 12, lg: 6 }}>
          <StatisticCard
            statistic={{
              title: '投影行数',
              value: report?.storedRows ?? 0,
              description: (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  权威重算应为 {report?.expectedRows ?? 0} 行
                </Typography.Text>
              ),
            }}
          />
        </ProCard>
        <ProCard colSpan={{ xs: 24, sm: 12, lg: 6 }}>
          <StatisticCard
            statistic={{
              title: '缺失行',
              value: report?.missingRows ?? 0,
              description: (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  投影未建出的行
                </Typography.Text>
              ),
            }}
          />
        </ProCard>
        <ProCard colSpan={{ xs: 24, sm: 12, lg: 6 }}>
          <StatisticCard
            statistic={{
              title: '流水不符',
              value: report?.ledgerMismatches ?? 0,
              description: (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  库存流水与权威记录对不上的行
                </Typography.Text>
              ),
            }}
          />
        </ProCard>
      </ProCard>

      {report && (report.differences?.length ?? 0) > 0 && (
        <Alert
          style={{ marginTop: 16 }}
          type="warning"
          showIcon
          message={`对账发现 ${report.differences?.length} 处差异`}
          description={
            <ul style={{ margin: 0, paddingLeft: 18 }}>
              {(report.differences ?? []).slice(0, 5).map((difference) => (
                <li
                  key={`${difference.kind}-${difference.stayDate}-${difference.roomId}`}
                >
                  {difference.kind} · {difference.stayDate} ·{' '}
                  {difference.detail}
                </li>
              ))}
            </ul>
          }
        />
      )}

      <ProTable<DayRow>
        style={{ marginTop: 16 }}
        headerTitle="每日余量"
        rowKey="key"
        columns={columns}
        dataSource={rows}
        loading={loading}
        search={false}
        options={false}
        pagination={{ pageSize: 20 }}
        locale={{
          emptyText: '该窗口内没有房态数据；可点击「重建投影」从权威记录重建',
        }}
      />
    </PageContainer>
  );
};

export default AvailabilityPage;
