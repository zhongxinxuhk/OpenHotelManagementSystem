/**
 * 我的订单（R-20261102-05，骨架页）。
 *
 * 目标形态（J-03）：**仅本人**的订单列表与详情——按会话账户过滤，
 * 不能凭订单号直查（订单号可枚举，那是越权读）。后端未实现，
 * 骨架页呈现列定义与真实空态。
 */
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Empty, Typography } from 'antd';
import React from 'react';

const Orders: React.FC = () => {
  return (
    <PageContainer
      header={{
        title: '我的订单',
      }}
    >
      <Alert
        type="info"
        showIcon
        title="订单查询接口未开放（归 J-03）"
        description="将来只返回当前账户的订单；在线取消（J-04）与支付（K 域）也未实现。"
        style={{ marginBottom: 16 }}
      />
      <Empty
        description={
          <Typography.Text type="secondary">
            暂无数据：将来呈现 订单号 / 门店 / 房型 / 入离日期 / 金额 / 状态 / 取消入口
          </Typography.Text>
        }
      />
    </PageContainer>
  );
};

export default Orders;
