/**
 * 排房（R-20261102-04，骨架页）。
 *
 * 目标形态（S3-04 排房、入住与离店）：把已确认订单排到具体房间——
 * 按到店日列订单、给每条订单选房型下的可用房间、落库后进入入住流程。
 * 后端接口未实现，骨架页呈现列定义与真实空态。
 *
 * 数据口径：排房读「已确认未排房」的订单 + 房型可售余量；
 * 写操作是「订单 ↔ 房间」的绑定（占用排斥由数据库约束保证，S1-05）。
 */
import type { ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { Alert, Empty, Typography } from 'antd';
import React from 'react';

interface AssignmentRow {
  key: string;
}

const columns: ProColumns<AssignmentRow>[] = [
  { title: '订单号', dataIndex: 'reservationNo', key: 'reservationNo' },
  { title: '客人', dataIndex: 'guest', key: 'guest' },
  { title: '房型', dataIndex: 'roomType', key: 'roomType' },
  { title: '到店日', dataIndex: 'checkIn', key: 'checkIn' },
  { title: '离店日', dataIndex: 'checkOut', key: 'checkOut' },
  { title: '已排房间', dataIndex: 'room', key: 'room' },
  {
    title: '操作',
    key: 'actions',
    render: () => (
      <Typography.Text type="secondary">接口未开放</Typography.Text>
    ),
  },
];

const Assignment: React.FC = () => {
  return (
    <PageContainer
      header={{
        title: '排房',
      }}
    >
      <Alert
        type="info"
        showIcon
        title="排房接口未开放（归 S3-04）"
        description="将来的流程：按到店日列出已确认订单 → 选可用房间 → 绑定后进入入住办理。占用互斥由数据库约束保证（S1-05 已落地）。"
        style={{ marginBottom: 16 }}
      />
      <ProTable<AssignmentRow>
        headerTitle="待排房订单"
        rowKey="key"
        columns={columns}
        dataSource={[]}
        search={false}
        pagination={false}
        toolBarRender={false}
        options={false}
        locale={{ emptyText: <Empty description="暂无数据（接口未开放）" /> }}
      />
    </PageContainer>
  );
};

export default Assignment;
