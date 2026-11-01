/**
 * 系统级审计（R-20261102-02，骨架页）。
 *
 * 目标形态：跨租户的系统级动作留痕查询——谁在什么时候用平台身份做了什么
 * （登录方式开关变更、渠道维护、熔断复位、实名审核决定），归 B-05 审计域。
 *
 * 边界（ADR-019 §2.4）：平台动作的留痕，**不是**跨租户查看租户业务数据——
 * 平台不跨租户（DEC-02）。骨架页只列将来的查询维度与真实空态。
 */
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Empty, Typography } from 'antd';
import React from 'react';

const Audit: React.FC = () => {
  return (
    <PageContainer
      header={{
        title: '系统级审计',
      }}
    >
      <Alert
        type="info"
        showIcon
        title="审计查询接口未开放"
        description="数据源归属 B-05（审计域）。平台侧动作（策略变更、渠道维护、熔断复位、审核决定）已在服务端留痕，查询 HTTP 接口就绪后本页呈现。"
        style={{ marginBottom: 16 }}
      />
      <Empty
        description={
          <Typography.Text type="secondary">
            暂无数据：查询维度为 时间 / 操作者（平台账号） / 动作码 / 对象 / 结果；
            范围限平台平面动作，不含租户业务数据
          </Typography.Text>
        }
      />
    </PageContainer>
  );
};

export default Audit;
