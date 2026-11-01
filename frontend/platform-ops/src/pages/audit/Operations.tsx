/**
 * 业务审计（R-20261102-03，骨架页）。
 *
 * 目标形态：**平台业务动作**的留痕查询——实名审核决定、登录方式开关变更、
 * 渠道维护等操作的可回溯视图（数据已由后端逐动作留痕，缺查询接口）。
 *
 * 与 platform-admin 的「系统级审计」边界一致（ADR-019 §2.4）：
 * 平面动作留痕，不含租户业务数据。
 */
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Empty, Typography } from 'antd';
import React from 'react';

const Operations: React.FC = () => {
  return (
    <PageContainer
      header={{
        title: '业务审计',
      }}
    >
      <Alert
        type="info"
        showIcon
        title="审计查询接口未开放"
        description="实名审核、策略变更、渠道维护等动作已在服务端留痕；查询 HTTP 接口就绪后本页呈现可回溯视图。"
        style={{ marginBottom: 16 }}
      />
      <Empty
        description={
          <Typography.Text type="secondary">
            暂无数据：查询维度为 时间 / 操作者 / 动作 / 对象（掩码）/ 结果；
            范围限平台平面动作
          </Typography.Text>
        }
      />
    </PageContainer>
  );
};

export default Operations;
