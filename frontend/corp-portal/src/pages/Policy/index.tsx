/**
 * 差旅政策（R-20261102-06，骨架页）。
 *
 * 目标形态：差旅政策的配置与版本——城市档位的住宿/交通上限、
 * 审批门槛、例外流程。后端未实现（依赖 DEC-17 与政策域立项）。
 */
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Empty, Typography } from 'antd';
import React from 'react';

const Policy: React.FC = () => {
  return (
    <PageContainer
      header={{
        title: '差旅政策',
      }}
    >
      <Alert
        type="info"
        showIcon
        title="政策配置接口未开放"
        description="差旅政策域尚未立项（前置：DEC-17 企业对公账户定案）。"
        style={{ marginBottom: 16 }}
      />
      <Empty
        description={
          <Typography.Text type="secondary">
            暂无数据：将来呈现 政策版本 / 城市档位（住宿·交通上限）/ 审批门槛 / 生效区间
          </Typography.Text>
        }
      />
    </PageContainer>
  );
};

export default Policy;
