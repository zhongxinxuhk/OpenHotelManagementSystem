/**
 * 订单列表与详情（S3-01）。
 *
 * 覆盖后端订单能力：列表（S1-11）、详情、确认 / 取消 / No-show（S1-09）、建单（S1-08）。
 *
 * 三条硬约束（前端规范 §4、§5）：
 * 1. 写操作带幂等键：进表单时生成，重试复用同一个键；
 * 2. 可执行动作由后端的 `allowedActions` 决定，前端不自己判断状态；
 * 3. 结果三分类可区分：冲突提示"冲突"，结果不确定提示查原交易结果。
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import {
  ModalForm,
  PageContainer,
  ProDescriptions,
  ProFormDatePicker,
  ProFormSelect,
  ProTable,
} from '@ant-design/pro-components';
import { App, Button, Drawer, Popconfirm, Space } from 'antd';
import dayjs from 'dayjs';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useHotelContext } from '@/components/HotelSelector/context';
import { ReservationStatusTag, SourceTypeTag } from '@/components/StatusTag';
import { describeProblem, toProblem } from '@/services/pms/errors';
import {
  cancelReservation,
  confirmReservation,
  createReservation,
  getReservationDetail,
  listReservations,
  markReservationNoShow,
  RESERVATION_SOURCE,
  RESERVATION_STATUS,
  type ReservationDetailView,
  type ReservationSummary,
} from '@/services/pms/reservation';
import { listRoomTypes, type RoomTypeView } from '@/services/pms/room';

/** 生成幂等键。进入表单时生成一次，重试复用。 */
function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `key-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

const SOURCE_OPTIONS = RESERVATION_SOURCE.map((value) => ({
  value,
  label:
    value === 'FRONTDESK'
      ? '前台'
      : value === 'DIRECT'
        ? '直客'
        : value === 'OTA'
          ? 'OTA'
          : '小程序',
}));

const STATUS_OPTIONS = RESERVATION_STATUS.map((value) => ({
  value,
  label:
    value === 'PENDING'
      ? '待确认'
      : value === 'CONFIRMED'
        ? '已确认'
        : value === 'CHECKED_IN'
          ? '已入住'
          : value === 'CHECKED_OUT'
            ? '已离店'
            : value === 'CANCELLED'
              ? '已取消'
              : value === 'EXPIRED'
                ? '已过期'
                : 'No-show',
}));

const ReservationList: React.FC = () => {
  const { currentHotelId, currentHotel } = useHotelContext();
  const { message } = App.useApp();
  const actionRef = useRef<ActionType | undefined>(undefined);

  const [detail, setDetail] = useState<ReservationDetailView | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);
  const [roomTypes, setRoomTypes] = useState<RoomTypeView[]>([]);

  /** 建单表单的幂等键：打开表单时生成，提交失败重试时复用同一个键。 */
  const createKeyRef = useRef<string>('');

  useEffect(() => {
    if (!currentHotelId) {
      setRoomTypes([]);
      return;
    }
    void listRoomTypes(currentHotelId)
      .then(setRoomTypes)
      .catch(() => setRoomTypes([]));
  }, [currentHotelId]);

  const openDetail = useCallback(
    async (reservationId: string) => {
      if (!currentHotelId) return;
      try {
        const view = await getReservationDetail(currentHotelId, reservationId);
        setDetail(view);
        setDetailOpen(true);
      } catch (error) {
        message.error(describeProblem(toProblem(error)));
      }
    },
    [currentHotelId, message],
  );

  /** 执行一个状态变更命令（确认 / 取消 / No-show）。 */
  const runLifecycle = useCallback(
    async (
      action: 'confirm' | 'cancel' | 'no-show',
      reservationId: string,
      version?: number,
    ) => {
      if (!currentHotelId) return;
      const idempotencyKey = newIdempotencyKey();
      try {
        const body = { idempotencyKey, expectedVersion: version };
        if (action === 'confirm') {
          await confirmReservation(currentHotelId, reservationId, body);
        } else if (action === 'cancel') {
          await cancelReservation(currentHotelId, reservationId, body);
        } else {
          await markReservationNoShow(currentHotelId, reservationId, body);
        }
        message.success('状态已更新');
        actionRef.current?.reload();
        if (detailOpen) {
          await openDetail(reservationId);
        }
      } catch (error) {
        const problem = toProblem(error);
        message.error(describeProblem(problem));
        // 冲突意味着服务端状态已变，刷新列表让操作员看到最新事实。
        if (problem.outcome === 'CONFLICT') {
          actionRef.current?.reload();
        }
      }
    },
    [currentHotelId, detailOpen, message, openDetail],
  );

  const columns: ProColumns<ReservationSummary>[] = [
    {
      title: '订单号',
      dataIndex: 'reservationNo',
      copyable: true,
      width: 180,
      fixed: 'left',
    },
    {
      title: '状态',
      dataIndex: 'status',
      valueType: 'select',
      valueEnum: Object.fromEntries(
        STATUS_OPTIONS.map((option) => [option.value, { text: option.label }]),
      ),
      render: (_, row) => <ReservationStatusTag status={row.status} />,
    },
    {
      title: '来源',
      dataIndex: 'sourceType',
      valueType: 'select',
      valueEnum: Object.fromEntries(
        SOURCE_OPTIONS.map((option) => [option.value, { text: option.label }]),
      ),
      render: (_, row) => <SourceTypeTag status={row.sourceType} />,
    },
    { title: '房数', dataIndex: 'lineCount', search: false, width: 80 },
    {
      title: '入住日',
      dataIndex: 'checkIn',
      search: false,
      width: 120,
    },
    {
      title: '离店日',
      dataIndex: 'checkOut',
      search: false,
      width: 120,
    },
    {
      title: '操作',
      valueType: 'option',
      width: 180,
      render: (_, row) => (
        <Space size={4}>
          <Button
            type="link"
            size="small"
            onClick={() => {
              if (!row.id) return;
              void openDetail(row.id);
            }}
          >
            详情
          </Button>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      title="订单列表"
      subTitle={currentHotel ? `门店 ${currentHotel.name}` : undefined}
    >
      <ProTable<ReservationSummary>
        actionRef={actionRef}
        rowKey="id"
        columns={columns}
        // key 随门店变化：切换门店时整张表重建，
        // 顺带清掉上一家门店的分页与筛选（前端规范 §8）。
        key={currentHotelId ?? 'no-hotel'}
        request={async (params) => {
          if (!currentHotelId) {
            return { data: [], total: 0, success: true };
          }
          const page = await listReservations(currentHotelId, {
            status: params.status ? [String(params.status)] : undefined,
            sourceType: params.sourceType
              ? String(params.sourceType)
              : undefined,
            no: params.no ? String(params.no) : undefined,
            roomCode: params.roomCode ? String(params.roomCode) : undefined,
            page: params.current ?? 1,
            size: params.pageSize ?? 20,
          });
          return {
            data: page.items ?? [],
            total: page.total ?? 0,
            success: true,
          };
        }}
        search={{
          labelWidth: 'auto',
          defaultCollapsed: false,
        }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
        scroll={{ x: 900 }}
        locale={{
          emptyText: '该门店当前筛选条件下没有订单',
        }}
        toolBarRender={() => [
          <ModalForm
            key="create"
            title="创建订单"
            trigger={
              <Button type="primary" disabled={!currentHotelId}>
                创建订单
              </Button>
            }
            modalProps={{ destroyOnClose: true }}
            onOpenChange={(open) => {
              // 每次打开表单换一个新的幂等键；
              // 同一次提交的重试则复用（见 submit 里的 catch）。
              if (open) createKeyRef.current = newIdempotencyKey();
            }}
            onFinish={async (values) => {
              if (!currentHotelId) return false;
              try {
                await createReservation(
                  currentHotelId,
                  {
                    idempotencyKey: createKeyRef.current,
                    sourceType: values.sourceType as string,
                    lines: [
                      {
                        roomTypeId: values.roomTypeId as string,
                        checkIn: dayjs(values.checkIn as string).format(
                          'YYYY-MM-DD',
                        ),
                        checkOut: dayjs(values.checkOut as string).format(
                          'YYYY-MM-DD',
                        ),
                      },
                    ],
                  },
                  createKeyRef.current,
                );
                message.success('订单已创建，处于待确认状态');
                actionRef.current?.reload();
                return true;
              } catch (error) {
                const problem = toProblem(error);
                message.error(describeProblem(problem));
                // 冲突（库存不足）保留表单，让操作员改房型或日期再试；
                // 幂等键不变，重试不会被当成新订单。
                return false;
              }
            }}
          >
            <ProFormSelect
              name="sourceType"
              label="订单来源"
              options={SOURCE_OPTIONS}
              initialValue="FRONTDESK"
              rules={[{ required: true, message: '请选择订单来源' }]}
            />
            <ProFormSelect
              name="roomTypeId"
              label="房型"
              options={roomTypes.map((roomType) => ({
                value: roomType.id ?? '',
                label: roomType.name ?? '',
              }))}
              rules={[{ required: true, message: '请选择房型' }]}
            />
            <ProFormDatePicker
              name="checkIn"
              label="入住日"
              initialValue={dayjs()}
              rules={[{ required: true, message: '请选择入住日' }]}
            />
            <ProFormDatePicker
              name="checkOut"
              label="离店日"
              initialValue={dayjs().add(1, 'day')}
              rules={[{ required: true, message: '请选择离店日' }]}
            />
          </ModalForm>,
        ]}
      />

      <Drawer
        open={detailOpen}
        onClose={() => setDetailOpen(false)}
        title="订单详情"
        width={720}
        destroyOnClose
      >
        {detail && (
          <>
            <ProDescriptions<ReservationDetailView>
              dataSource={detail}
              columns={[
                { title: '订单号', dataIndex: 'reservationNo' },
                {
                  title: '状态',
                  dataIndex: 'status',
                  render: (_, row) => (
                    <ReservationStatusTag status={row.status} />
                  ),
                },
                {
                  title: '来源',
                  dataIndex: 'sourceType',
                  render: (_, row) => <SourceTypeTag status={row.sourceType} />,
                },
                {
                  title: '入住日',
                  dataIndex: 'stayRange',
                  render: (_, row) => row.stayRange?.checkIn,
                },
                {
                  title: '离店日',
                  dataIndex: 'stayRange',
                  render: (_, row) => row.stayRange?.checkOut,
                },
                { title: '版本', dataIndex: 'version' },
              ]}
            />

            <ProDescriptions<ReservationDetailView>
              style={{ marginTop: 16 }}
              dataSource={detail}
              columns={[
                {
                  title: '房号',
                  dataIndex: 'lines',
                  render: (_, row) =>
                    (row.lines ?? [])
                      .map((line) => line.assignedRoomCode ?? '未分房')
                      .join('、'),
                },
                {
                  title: '每行住宿区间',
                  dataIndex: 'lines',
                  render: (_, row) =>
                    (row.lines ?? [])
                      .map(
                        (line) =>
                          `${line.plannedRange?.checkIn} ~ ${line.plannedRange?.checkOut}`,
                      )
                      .join('；'),
                },
                {
                  title: '行状态',
                  dataIndex: 'lines',
                  render: (_, row) =>
                    (row.lines ?? [])
                      .map((line) => line.status ?? '-')
                      .join('、'),
                },
              ]}
            />

            <Space style={{ marginTop: 24 }} wrap>
              {detail.allowedActions?.includes('CONFIRM') && (
                <Popconfirm
                  title="确认该订单"
                  description={`订单 ${detail.reservationNo} 将变为已确认，占用继续有效`}
                  onConfirm={() => {
                    if (!detail.id) return;
                    void runLifecycle('confirm', detail.id, detail.version);
                  }}
                >
                  <Button type="primary">确认</Button>
                </Popconfirm>
              )}
              {detail.allowedActions?.includes('CANCEL') && (
                <Popconfirm
                  title="取消该订单"
                  description={`订单 ${detail.reservationNo} 将被取消，占用释放`}
                  onConfirm={() => {
                    if (!detail.id) return;
                    void runLifecycle('cancel', detail.id, detail.version);
                  }}
                >
                  <Button danger>取消</Button>
                </Popconfirm>
              )}
              {detail.allowedActions?.includes('NO_SHOW') && (
                <Popconfirm
                  title="标记 No-show"
                  description={`订单 ${detail.reservationNo} 将标记为 No-show 并释放占用；是否计费待业务规则确定`}
                  onConfirm={() => {
                    if (!detail.id) return;
                    void runLifecycle('no-show', detail.id, detail.version);
                  }}
                >
                  <Button danger>标记 No-show</Button>
                </Popconfirm>
              )}
            </Space>

            {detail.allowedActions?.length === 0 && (
              <div style={{ color: '#8a8f99', fontSize: 12, marginTop: 16 }}>
                当前状态没有可执行的状态变更操作。
              </div>
            )}
          </>
        )}
      </Drawer>
    </PageContainer>
  );
};

export default ReservationList;
