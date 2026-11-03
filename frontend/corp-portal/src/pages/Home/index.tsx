/**
 * 企业商旅工作台（R-20261102-06，骨架页）。
 *
 * 目标形态：企业差旅总览——待审批出行申请、本期差旅支出、
 * 政策命中率。DEC-17 OPEN（企业对公账户未定案），无后端接口，
 * 骨架页只呈现将来的指标维度与真实空态。
 */
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Empty, Typography } from 'antd';
import React from 'react';

const Home: React.FC = () => {
  return (
    <PageContainer
      header={{
        title: '差旅工作台',
      }}
    >
      <Alert
        type="info"
        showIcon
        title="企业对公账户方案未定案（DEC-17）"
        description="企业身份归属平面（CORP_SSO 进住客平面，或独立企业平面）与「企业 ≠ 租户」的边界待定案；接口就绪后本页呈现差旅总览。"
        style={{ marginBottom: 16 }}
      />
      <Empty
        description={
          <Typography.Text type="secondary">
            暂无数据：将来呈现 待审批出行申请 / 本期差旅支出 / 政策命中率 / 员工出行排行
          </Typography.Text>
        }
      />
    </PageContainer>
  );
};

export default Home;
