/**
 * 系统趋势（R-20261102-02，骨架页）。
 *
 * 后端没有趋势/报表接口（归 S3-09 审计与报表域）。骨架页只做两件事：
 * 1. 把将来要呈现的**指标清单**列出来（列定义即需求）；
 * 2. 呈现真实的「未开放」空态。
 *
 * 不渲染示例曲线与示例数字（前端规范 §7）：假数据会让人误以为系统在运行，
 * 这类页面在 O-11 整批删过一次，不在新控制台里复发。
 */
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Empty, Typography } from 'antd';
import React from 'react';

const Trends: React.FC = () => {
  return (
    <PageContainer
      header={{
        title: '系统趋势',
      }}
    >
      <Alert
        type="info"
        showIcon
        title="趋势接口未开放"
        description="数据源归属 S3-09（审计、经营报表与夜审）。接口就绪后本页按以下指标呈现，不引入任何示例数据。"
        style={{ marginBottom: 16 }}
      />
      <Empty
        description={
          <Typography.Text type="secondary">
            暂无数据：注册/登录趋势、验证码投递量、实名审核吞吐、渠道熔断事件、
            Outbox 积压（指标已通过 /actuator/prometheus 暴露，查询接口待建）
          </Typography.Text>
        }
      />
    </PageContainer>
  );
};

export default Trends;
