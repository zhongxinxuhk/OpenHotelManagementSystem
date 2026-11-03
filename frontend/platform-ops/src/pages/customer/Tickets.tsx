/**
 * 客服工单（R-20261102-03，骨架页）。
 *
 * 目标形态：住客/商家诉求工单的接单、流转与答复。当前无工单域后端接口
 * （需求与模型均未定），骨架页只列将来的列定义与真实空态。
 */
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Empty, Typography } from 'antd';
import React from 'react';

const Tickets: React.FC = () => {
  return (
    <PageContainer
      header={{
        title: '客服工单',
      }}
    >
      <Alert
        type="info"
        showIcon
        title="工单接口未开放"
        description="工单域（提交/流转/答复/满意度）尚未立项。接口就绪后本页呈现队列与处理台。"
        style={{ marginBottom: 16 }}
      />
      <Empty
        description={
          <Typography.Text type="secondary">
            暂无数据：将来呈现 工单号 / 来源（住客·商家）/ 类型 / 状态 /
            最近处理人 / 时限
          </Typography.Text>
        }
      />
    </PageContainer>
  );
};

export default Tickets;
