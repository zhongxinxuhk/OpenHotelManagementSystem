/**
 * 员工出行（R-20261102-06，骨架页）。
 *
 * 目标形态：出行申请的提交、审批与预订联动——申请按政策校验，
 * 审批后进入预订流程（复用 C 端交易 API）。后端未实现。
 */
import type { ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { Alert, Empty, Typography } from 'antd';
import React from 'react';

interface TravelRow {
  key: string;
}

const columns: ProColumns<TravelRow>[] = [
  { title: '申请编号', dataIndex: 'requestNo', key: 'requestNo' },
  { title: '员工', dataIndex: 'employee', key: 'employee' },
  { title: '行程', dataIndex: 'trip', key: 'trip' },
  { title: '日期', dataIndex: 'dates', key: 'dates' },
  { title: '预估费用', dataIndex: 'estimate', key: 'estimate' },
  { title: '状态', dataIndex: 'status', key: 'status' },
  {
    title: '操作',
    key: 'actions',
    render: () => (
      <Typography.Text type="secondary">接口未开放</Typography.Text>
    ),
  },
];

const Travel: React.FC = () => {
  return (
    <PageContainer
      header={{
        title: '员工出行',
      }}
    >
      <Alert
        type="info"
        showIcon
        title="出行申请接口未开放（前置：DEC-17 与差旅政策域）"
        style={{ marginBottom: 16 }}
      />
      <ProTable<TravelRow>
        headerTitle="出行申请"
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

export default Travel;
