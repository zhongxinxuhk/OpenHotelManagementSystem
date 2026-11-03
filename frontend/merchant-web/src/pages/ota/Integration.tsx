/**
 * OTA 对接（R-20261102-04，骨架页）。
 *
 * 目标形态（L 域 / S5-01—S5-08）：渠道账户配置、门店房型映射、
 * 价格计划推送、订单接收与对账。渠道授权未到位，L 域未实现——
 * 骨架页只呈现将来的对接维度与真实空态。
 */
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Empty, Typography } from 'antd';
import React from 'react';

const Integration: React.FC = () => {
  return (
    <PageContainer
      header={{
        title: 'OTA 对接',
      }}
    >
      <Alert
        type="warning"
        showIcon
        title="渠道授权未到位，对接未启用"
        description="需要渠道方商务授权与账号（S5-01 前置）。授权到位后本页配置渠道账户与映射，库存与价格可靠推送走 L 域适配器。"
        style={{ marginBottom: 16 }}
      />
      <Empty
        description={
          <Typography.Text type="secondary">
            暂无数据：将来呈现 渠道账户 / 门店与房型映射 / 价格计划 /
            推送状态 / 对账差异
          </Typography.Text>
        }
      />
    </PageContainer>
  );
};

export default Integration;
