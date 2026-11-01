/**
 * OTA 三方接口适配（R-20261102-02，骨架页）。
 *
 * 目标形态：L 域渠道适配器的管理入口——渠道账户、门店/房型映射、
 * 价格计划推送的配置与状态。当前 L 域完全未实现（渠道授权未到位，
 * 同 S5-01 的阻塞原因）。
 *
 * 骨架页只呈现将来的配置维度与真实空态，不虚构"已接入的渠道"。
 */
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Empty, Typography } from 'antd';
import React from 'react';

const Adapters: React.FC = () => {
  return (
    <PageContainer
      header={{
        title: 'OTA 接口适配',
      }}
    >
      <Alert
        type="warning"
        showIcon
        title="渠道授权未到位，适配器未实现"
        description="L 域（渠道）整体处于 BLOCKED：需要渠道方的商务授权与账号。授权到位后按 S5-01（渠道账户、适配器与安全边界）开始实现。"
        style={{ marginBottom: 16 }}
      />
      <Empty
        description={
          <Typography.Text type="secondary">
            暂无数据：将来呈现 渠道账户 / 映射范围（门店·房型）/ 推送状态 /
            最近同步结果 / 对账差异
          </Typography.Text>
        }
      />
    </PageContainer>
  );
};

export default Adapters;
