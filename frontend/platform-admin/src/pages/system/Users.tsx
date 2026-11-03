/**
 * 平台用户与权限（R-20261102-02，骨架页）。
 *
 * 目标形态：平台账号（platform_user）的开通、动作码（platform_action）的
 * 动态授予与回收——「权限动态管控授权」的管理入口。
 *
 * 当前后端没有平台用户管理 API（只有认证与种子脚本）；动作码字典可从
 * `/api/v1/platform/auth/me` 看到自己持有的部分。骨架页呈现：
 * 1. 将来的管理表格列定义（登录名/显示名/动作码/状态/强制改密）；
 * 2. 动作码字典的**真实内容**（来自 V15/V16 迁移种子，静态列出）——
 *    这是当前唯一可靠的事实源，标明它与后端字典同步的义务；
 * 3. 真实空态：管理接口未开放。
 *
 * 注意：动态管控的边界是**动作码**，不是租户权限点（两套字典刻意分离，
 * ADR-019 §2.4）。
 */
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { Alert, Empty, Space, Tag, Typography } from 'antd';
import React from 'react';

/** 与后端 V15/V16 种子一致的动作码字典（变更需同步迁移脚本）。 */
const ACTION_DICTIONARY: { code: string; name: string }[] = [
  { code: 'guest.authpolicy.read', name: '查看住客登录方式开关' },
  { code: 'guest.authpolicy.write', name: '设置住客登录方式开关' },
  { code: 'guest.channel.read', name: '查看核验渠道配置' },
  { code: 'guest.channel.write', name: '维护核验渠道配置' },
  { code: 'guest.verification.read', name: '查看实名审核队列' },
  { code: 'guest.verification.review', name: '执行实名人工审核' },
];

const Users: React.FC = () => {
  return (
    <PageContainer
      header={{
        title: '平台用户与权限',
      }}
    >
      <Alert
        type="info"
        showIcon
        title="平台用户管理接口未开放"
        description="当前平台账号由种子脚本创建（openbackupsaas/scripts/seed-platform-admin.sh）。开通、动作码授予与回收的 HTTP 接口就绪后，本页提供动态管控。"
        style={{ marginBottom: 16 }}
      />
      <ProCard title="动作码字典（与 V15/V16 种子同步）">
        <Space direction="vertical" size={4}>
          {ACTION_DICTIONARY.map((action) => (
            <Space key={action.code} size="small">
              <Tag color="blue">{action.code}</Tag>
              <Typography.Text type="secondary">{action.name}</Typography.Text>
            </Space>
          ))}
        </Space>
        <Typography.Paragraph
          type="secondary"
          style={{ marginTop: 12, marginBottom: 0 }}
        >
          平台动作不复用租户权限字典（ADR-019 §2.4）：这里没有门店范围、没有租户维度。
        </Typography.Paragraph>
      </ProCard>
      <ProCard title="账号清单" style={{ marginTop: 16 }}>
        <Empty description="管理接口未开放：列定义为 登录名 / 显示名 / 动作码 / 状态 / 强制改密" />
      </ProCard>
    </PageContainer>
  );
};

export default Users;
