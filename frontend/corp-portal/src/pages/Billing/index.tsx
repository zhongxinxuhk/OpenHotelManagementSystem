/**
 * 账单与对账（R-20261102-06，骨架页）。
 *
 * 目标形态：企业月度账单、按成本中心分摊、与酒店结算数据的对账。
 * 依赖 K 域（支付与账务）与企业对公账户（DEC-17），均未实现。
 */
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Empty, Typography } from 'antd';
import React from 'react';

const Billing: React.FC = () => {
  return (
    <PageContainer
      header={{
        title: '账单与对账',
      }}
    >
      <Alert
        type="info"
        showIcon
        title="账务接口未开放（前置：DEC-17 与 K 域支付）"
        description="企业月度账单、成本中心分摊、与酒店结算对账，依赖支付与账务域建设。"
        style={{ marginBottom: 16 }}
      />
      <Empty
        description={
          <Typography.Text type="secondary">
            暂无数据：将来呈现 账期 / 应付金额 / 成本中心分摊 / 对账差异 / 结算状态
          </Typography.Text>
        }
      />
    </PageContainer>
  );
};

export default Billing;
