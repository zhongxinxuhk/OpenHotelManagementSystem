/**
 * 我的（R-20261102-05，真实接口）。
 *
 * 呈现 `GET /api/v1/guest/me` 的真实字段：显示名、实名状态、会话过期。
 * 实名状态是住客能否下单的前提（`realNameRequired`）——未实名时
 * 提示去完成实名（提交身份证号的接口尚未开放，如实说明）。
 */
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { useModel } from '@umijs/max';
import { Alert, Button, Space, Tag, Typography } from 'antd';
import { history } from '@umijs/max';
import React from 'react';

/** 实名状态的标签与说明（与后端状态机一致：ADR-018 §2.6）。 */
function VerificationStatus({ status }: { status?: string }) {
  switch (status) {
    case 'VERIFIED':
      return <Tag color="green">已实名</Tag>;
    case 'PENDING':
      return <Tag color="processing">实名审核中</Tag>;
    case 'REJECTED':
      return <Tag color="red">实名被驳回</Tag>;
    default:
      return <Tag color="default">未实名</Tag>;
  }
}

const Me: React.FC = () => {
  const { initialState } = useModel('@@initialState');
  const user = initialState?.currentUser;
  const status = user?.verificationStatus;

  return (
    <PageContainer
      header={{
        title: '我的账户',
      }}
    >
      <ProCard direction="column" ghost gutter={[0, 16]}>
        <ProCard title="基本信息">
          <Space direction="vertical" size="small" style={{ width: '100%' }}>
            <Space size="large" wrap>
              <Typography.Text type="secondary">显示名</Typography.Text>
              <Typography.Text>{user?.displayName ?? '—'}</Typography.Text>
            </Space>
            <Space size="large" wrap>
              <Typography.Text type="secondary">实名状态</Typography.Text>
              <VerificationStatus status={status} />
            </Space>
            <Space size="large" wrap>
              <Typography.Text type="secondary">会话过期</Typography.Text>
              <Typography.Text>
                {user?.sessionExpiresAt
                  ? new Date(user.sessionExpiresAt).toLocaleString()
                  : '—'}
              </Typography.Text>
            </Space>
          </Space>
        </ProCard>

        {user?.realNameRequired && status !== 'VERIFIED' && (
          <Alert
            type="warning"
            showIcon
            title="完成实名后才能下单"
            description="预订需要实名（身份证号加密存储）。实名材料提交接口尚未开放——开放后在此提交，平台人工审核（1 个工作日内）。"
          />
        )}

        <ProCard title="账号与登录方式">
          <Space direction="vertical" size="small">
            <Typography.Text type="secondary">
              绑定/解绑用户名、邮箱等登录方式
            </Typography.Text>
            <Button
              type="primary"
              onClick={() => {
                history.push('/me/bindings');
              }}
            >
              管理登录方式
            </Button>
          </Space>
        </ProCard>
      </ProCard>
    </PageContainer>
  );
};

export default Me;
