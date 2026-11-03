/**
 * OTA 商业授权（R-20261102-03，骨架页）。
 *
 * 目标形态：渠道商业授权台账——哪些商家开通了哪个 OTA 渠道的对接
 * （商务协议、开通状态、生效区间）。L 域未实现且授权未到位，
 * 骨架页只呈现将来的台账维度与真实空态。
 */
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Empty, Typography } from 'antd';
import React from 'react';

const Commercial: React.FC = () => {
  return (
    <PageContainer
      header={{
        title: 'OTA 商业授权',
      }}
    >
      <Alert
        type="warning"
        showIcon
        title="渠道授权未到位，台账未启用"
        description="商业授权依赖渠道方商务协议（L 域前置）。授权落地后本页管理「商家 × 渠道」的开通台账。"
        style={{ marginBottom: 16 }}
      />
      <Empty
        description={
          <Typography.Text type="secondary">
            暂无数据：将来呈现 商家 / 渠道 / 协议编号 / 授权状态 / 生效区间 /
            对接负责人
          </Typography.Text>
        }
      />
    </PageContainer>
  );
};

export default Commercial;
