/**
 * 房型与实体房维护（S3-01）。
 *
 * 覆盖后端房间能力：房型 CRUD + 启停（S1-04）、房间 CRUD + 启停。
 *
 * 注意 `housekeepingStatus`（清洁状态）只在列表里展示，**不能改**——
 * 清洁流转属房务流程（S3-06），与销售占用事实解耦。
 */

import type { ProColumns } from '@ant-design/pro-components';
import {
  ModalForm,
  PageContainer,
  ProFormDigit,
  ProFormSelect,
  ProFormText,
  ProFormTextArea,
  ProTable,
} from '@ant-design/pro-components';
import { App, Button, Popconfirm } from 'antd';
import React, { useCallback, useEffect, useState } from 'react';
import { useHotelContext } from '@/components/HotelSelector/context';
import {
  HousekeepingStatusTag,
  OrgStatusTag,
  RoomStatusTag,
} from '@/components/StatusTag';
import { describeProblem, toProblem } from '@/services/pms/errors';
import {
  changeRoomStatus,
  changeRoomTypeStatus,
  createRoom,
  createRoomType,
  listRooms,
  listRoomTypes,
  type RoomTypeView,
  type RoomView,
} from '@/services/pms/room';

const RoomManagement: React.FC = () => {
  const { currentHotelId, currentHotel } = useHotelContext();
  const { message } = App.useApp();

  const [roomTypes, setRoomTypes] = useState<RoomTypeView[]>([]);
  const [rooms, setRooms] = useState<RoomView[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    if (!currentHotelId) {
      setRoomTypes([]);
      setRooms([]);
      return;
    }
    setLoading(true);
    try {
      const [typeList, roomList] = await Promise.all([
        listRoomTypes(currentHotelId),
        listRooms(currentHotelId),
      ]);
      setRoomTypes(typeList);
      setRooms(roomList);
    } catch (error) {
      message.error(describeProblem(toProblem(error)));
    } finally {
      setLoading(false);
    }
  }, [currentHotelId, message]);

  useEffect(() => {
    void load();
  }, [load]);

  const roomTypeColumns: ProColumns<RoomTypeView>[] = [
    { title: '编码', dataIndex: 'code', copyable: true },
    { title: '名称', dataIndex: 'name' },
    { title: '床型', dataIndex: 'bedType' },
    { title: '成人位', dataIndex: 'capacityAdults' },
    { title: '儿童位', dataIndex: 'capacityChildren' },
    {
      title: '状态',
      dataIndex: 'status',
      render: (_, row) => <OrgStatusTag status={row.status} />,
    },
    {
      title: '操作',
      valueType: 'option',
      render: (_, row) => [
        <Popconfirm
          key="toggle"
          title={row.status === 'ACTIVE' ? '停用房型' : '启用房型'}
          description={`房型 ${row.name}${row.status === 'ACTIVE' ? '停用后不可再销售' : ''}`}
          onConfirm={async () => {
            if (!currentHotelId) return;
            try {
              const roomTypeId = row.id;
              if (!roomTypeId) return;
              await changeRoomTypeStatus(currentHotelId, roomTypeId, {
                status: row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
              });
              message.success('房型状态已更新');
              void load();
            } catch (error) {
              message.error(describeProblem(toProblem(error)));
            }
          }}
        >
          <Button type="link" size="small">
            {row.status === 'ACTIVE' ? '停用' : '启用'}
          </Button>
        </Popconfirm>,
      ],
    },
  ];

  const roomColumns: ProColumns<RoomView>[] = [
    { title: '房号', dataIndex: 'code', copyable: true },
    {
      title: '房型',
      dataIndex: 'roomTypeId',
      render: (_, row) =>
        roomTypes.find((type) => type.id === row.roomTypeId)?.name ??
        row.roomTypeId,
    },
    { title: '楼层', dataIndex: 'floor' },
    {
      title: '运营状态',
      dataIndex: 'status',
      render: (_, row) => <RoomStatusTag status={row.status} />,
    },
    {
      title: '清洁状态',
      dataIndex: 'housekeepingStatus',
      render: (_, row) => (
        <HousekeepingStatusTag status={row.housekeepingStatus} />
      ),
    },
    { title: '备注', dataIndex: 'notes' },
    {
      title: '操作',
      valueType: 'option',
      render: (_, row) => [
        <Popconfirm
          key="toggle"
          title={row.status === 'ACTIVE' ? '停用房间' : '启用房间'}
          description={`房间 ${row.code}`}
          onConfirm={async () => {
            if (!currentHotelId) return;
            try {
              const roomId = row.id;
              if (!roomId) return;
              await changeRoomStatus(currentHotelId, roomId, {
                status: row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
              });
              message.success('房间状态已更新');
              void load();
            } catch (error) {
              message.error(describeProblem(toProblem(error)));
            }
          }}
        >
          <Button type="link" size="small">
            {row.status === 'ACTIVE' ? '停用' : '启用'}
          </Button>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <PageContainer
      title="房型与房间"
      subTitle={currentHotel ? `门店 ${currentHotel.name}` : undefined}
    >
      <ProTable<RoomTypeView>
        headerTitle="房型"
        rowKey="id"
        columns={roomTypeColumns}
        dataSource={roomTypes}
        loading={loading}
        search={false}
        options={false}
        pagination={false}
        toolBarRender={() => [
          <ModalForm
            key="createRoomType"
            title="新增房型"
            trigger={
              <Button type="primary" disabled={!currentHotelId}>
                新增房型
              </Button>
            }
            modalProps={{ destroyOnClose: true }}
            onFinish={async (values) => {
              if (!currentHotelId) return false;
              try {
                await createRoomType(currentHotelId, {
                  code: values.code as string,
                  name: values.name as string,
                  bedType: values.bedType as string,
                  capacityAdults: values.capacityAdults as number,
                  capacityChildren: values.capacityChildren as number,
                });
                message.success('房型已创建');
                void load();
                return true;
              } catch (error) {
                message.error(describeProblem(toProblem(error)));
                return false;
              }
            }}
          >
            <ProFormText
              name="code"
              label="编码"
              placeholder="小写字母或数字开头，可含连字符"
              rules={[{ required: true, message: '请填写房型编码' }]}
            />
            <ProFormText
              name="name"
              label="名称"
              rules={[{ required: true, message: '请填写房型名称' }]}
            />
            <ProFormSelect
              name="bedType"
              label="床型"
              options={[
                { value: 'KING', label: '大床' },
                { value: 'TWIN', label: '双床' },
                { value: 'FAMILY', label: '家庭' },
              ]}
            />
            <ProFormDigit
              name="capacityAdults"
              label="成人位数"
              min={1}
              initialValue={2}
            />
            <ProFormDigit
              name="capacityChildren"
              label="儿童位数"
              min={0}
              initialValue={0}
            />
          </ModalForm>,
        ]}
        locale={{ emptyText: '该门店尚未创建房型' }}
      />

      <ProTable<RoomView>
        headerTitle="实体房间"
        style={{ marginTop: 24 }}
        rowKey="id"
        columns={roomColumns}
        dataSource={rooms}
        loading={loading}
        search={false}
        options={false}
        pagination={false}
        toolBarRender={() => [
          <ModalForm
            key="createRoom"
            title="新增房间"
            trigger={
              <Button
                type="primary"
                disabled={!currentHotelId || roomTypes.length === 0}
              >
                新增房间
              </Button>
            }
            modalProps={{ destroyOnClose: true }}
            onFinish={async (values) => {
              if (!currentHotelId) return false;
              try {
                await createRoom(currentHotelId, {
                  code: values.code as string,
                  roomTypeId: values.roomTypeId as string,
                  floor: values.floor as number,
                  notes: values.notes as string,
                });
                message.success('房间已创建');
                void load();
                return true;
              } catch (error) {
                message.error(describeProblem(toProblem(error)));
                return false;
              }
            }}
          >
            <ProFormText
              name="code"
              label="房号"
              rules={[{ required: true, message: '请填写房号' }]}
            />
            <ProFormSelect
              name="roomTypeId"
              label="房型"
              options={roomTypes.map((type) => ({
                value: type.id ?? '',
                label: type.name ?? '',
              }))}
              rules={[{ required: true, message: '请选择房型' }]}
            />
            <ProFormDigit name="floor" label="楼层" min={1} />
            <ProFormTextArea
              name="notes"
              label="备注"
              fieldProps={{ maxLength: 200 }}
            />
          </ModalForm>,
        ]}
        locale={{ emptyText: '该门店尚未创建房间' }}
      />
    </PageContainer>
  );
};

export default RoomManagement;
