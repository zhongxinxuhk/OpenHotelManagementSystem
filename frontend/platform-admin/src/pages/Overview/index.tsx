/**
 * 系统概览（R-20261102-02）。
 *
 * 数据原则（前端规范 §7 同源）：**只呈现真实接口数据与真实空态**。
 * 三张卡片来自已接通的平台接口；尚未开放后端接口的模块（趋势、系统审计、OTA）
 * 只给「未开放」的空态说明，不渲染任何示例数字——假数字会让人误以为系统在运行。
 *
 * 权限差异的呈现：每张卡片按动作码独立拉取；无权限（403）时该卡片显示
 * 「当前账号无此动作码」，而不是整页报错——平台账号的动作是按需授予的，
 * 只读账号打开概览不该看到一片红。
 */
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { useModel } from '@umijs/max';
import {
  Alert,
  Badge,
  Empty,
  List,
  Skeleton,
  Space,
  Statistic,
  Tag,
  Typography,
} from 'antd';
import React, { useCallback, useEffect, useState } from 'react';
import {
  listGuestLoginPolicy,
  listGuestVerifications,
  listVerificationChannels,
  type ChannelView,
  type PolicyView,
} from '@/services/pms/platform';
import { describeProblem, toProblem } from '@/services/pms/errors';

/** 卡片的四种数据态：加载中 / 无权限 / 失败 / 有数据。 */
type CardState<T> =
  | { kind: 'loading' }
  | { kind: 'forbidden' }
  | { kind: 'error'; message: string }
  | { kind: 'ready'; data: T };

/** 拉取一张卡的数据：403（无动作码）单独成态，不当成错误。 */
async function fetchCard<T>(loader: () => Promise<T>): Promise<CardState<T>> {
  try {
    return { kind: 'ready', data: await loader() };
  } catch (error) {
    const problem = toProblem(error);
    if (problem?.status === 403) {
      return { kind: 'forbidden' };
    }
    return { kind: 'error', message: describeProblem(problem) };
  }
}

/** 登录方式的可用性标签：available = implemented ∧ enabled（ADR-019 §2.9）。 */
function PolicyStatus({ policy }: { policy: PolicyView }) {
  if (!policy.implemented) {
    return (
      <Tag color="default" title="后端尚未实现该登录通道，打开开关也不会生效">
        未实现
      </Tag>
    );
  }
  return policy.enabled ? (
    <Tag color="green" title="实现 ∧ 开关都已满足">
      可用
    </Tag>
  ) : (
    <Tag color="orange" title="已实现但开关关闭">
      已关停
    </Tag>
  );
}

/** 渠道熔断状态徽标。 */
function CircuitBadge({ channel }: { channel: ChannelView }) {
  switch (channel.circuitState) {
    case 'OPEN':
      return (
        <Badge
          status="error"
          text={`熔断中${channel.openedUntil ? `（至 ${new Date(channel.openedUntil).toLocaleTimeString()}）` : ''}`}
        />
      );
    case 'HALF_OPEN':
      return <Badge status="warning" text="半开（试探中）" />;
    default:
      return <Badge status="success" text="闭合" />;
  }
}

const Overview: React.FC = () => {
  const { initialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser;

  const [policyState, setPolicyState] = useState<CardState<PolicyView[]>>({
    kind: 'loading',
  });
  const [channelState, setChannelState] = useState<CardState<ChannelView[]>>({
    kind: 'loading',
  });
  const [pendingState, setPendingState] = useState<CardState<number | null>>({
    kind: 'loading',
  });

  const load = useCallback(async () => {
    void fetchCard(listGuestLoginPolicy).then(setPolicyState);
    void fetchCard(listVerificationChannels).then(setChannelState);
    void fetchCard(async () => {
      // 只取 total：待审核数量。完整队列与决定动作在 platform-ops。
      const page = await listGuestVerifications({ page: 1, size: 1 });
      return page.total ?? 0;
    }).then(setPendingState);
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <PageContainer
      header={{
        title: '系统概览',
      }}
    >
      <ProCard direction="column" ghost gutter={[0, 16]}>
        {/* 当前账号：来自会话（无额外请求），动作码即"我能做什么" */}
        <ProCard title="当前账号">
          <Space direction="vertical" size="small" style={{ width: '100%' }}>
            <Space size="large" wrap>
              <Statistic title="登录名" value={currentUser?.username ?? '—'} />
              <Statistic
                title="显示名"
                value={currentUser?.displayName ?? '—'}
              />
              <Statistic
                title="会话过期"
                value={
                  currentUser?.sessionExpiresAt
                    ? new Date(currentUser.sessionExpiresAt).toLocaleString()
                    : '—'
                }
              />
            </Space>
            <Typography.Text type="secondary">动作码</Typography.Text>
            {currentUser?.actions?.length ? (
              <Space size={[8, 8]} wrap>
                {currentUser.actions.map((action) => (
                  <Tag key={action} color="blue">
                    {action}
                  </Tag>
                ))}
              </Space>
            ) : (
              <Typography.Text type="secondary">
                当前账号未持有任何动作码
              </Typography.Text>
            )}
          </Space>
        </ProCard>

        <ProCard split="vertical" ghost gutter={16}>
          {/* 登录方式策略（真实接口） */}
          <ProCard title="住客登录方式" style={{ minWidth: 280 }}>
            {policyState.kind === 'loading' && <Skeleton active />}
            {policyState.kind === 'forbidden' && (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="当前账号无 guest.authpolicy.read 动作码"
              />
            )}
            {policyState.kind === 'error' && (
              <Alert type="error" showIcon title={policyState.message} />
            )}
            {policyState.kind === 'ready' && (
              <List
                size="small"
                dataSource={policyState.data}
                rowKey={(item) => item.provider ?? ''}
                renderItem={(item) => (
                  <List.Item
                    extra={<PolicyStatus policy={item} />}
                    style={{ padding: '6px 0' }}
                  >
                    <List.Item.Meta
                      title={item.provider}
                      description={
                        item.updatedAt
                          ? `更新于 ${new Date(item.updatedAt).toLocaleString()}（${item.updatedBy ?? '—'}）`
                          : undefined
                      }
                    />
                  </List.Item>
                )}
              />
            )}
          </ProCard>

          {/* 核验渠道（真实接口） */}
          <ProCard title="核验渠道" style={{ minWidth: 280 }}>
            {channelState.kind === 'loading' && <Skeleton active />}
            {channelState.kind === 'forbidden' && (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="当前账号无 guest.channel.read 动作码"
              />
            )}
            {channelState.kind === 'error' && (
              <Alert type="error" showIcon title={channelState.message} />
            )}
            {channelState.kind === 'ready' && (
              <List
                size="small"
                dataSource={channelState.data}
                rowKey={(item) => item.code ?? ''}
                renderItem={(item) => (
                  <List.Item
                    extra={<CircuitBadge channel={item} />}
                    style={{ padding: '6px 0' }}
                  >
                    <List.Item.Meta
                      title={`${item.name}（${item.kind}）`}
                      description={item.status === 'ACTIVE' ? '启用' : '停用'}
                    />
                  </List.Item>
                )}
              />
            )}
          </ProCard>

          {/* 实名待审核数（真实接口，只读统计） */}
          <ProCard title="实名待审核" style={{ minWidth: 200 }}>
            {pendingState.kind === 'loading' && <Skeleton active />}
            {pendingState.kind === 'forbidden' && (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="当前账号无 guest.verification.read 动作码"
              />
            )}
            {pendingState.kind === 'error' && (
              <Alert type="error" showIcon title={pendingState.message} />
            )}
            {pendingState.kind === 'ready' && (
              <Statistic
                title="队列中的待审核数"
                value={pendingState.data ?? 0}
                suffix="人"
              />
            )}
          </ProCard>
        </ProCard>

        {/* 未开放模块：真实空态，不渲染示例数据 */}
        <ProCard split="vertical" ghost gutter={16}>
          <ProCard title="系统趋势">
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="趋势接口未开放（归 S3-09 审计与报表）"
            />
          </ProCard>
          <ProCard title="系统级审计">
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="审计查询接口未开放（归 B-05 审计域）"
            />
          </ProCard>
          <ProCard title="OTA 渠道接入">
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="渠道适配器未实现（L 域，授权未到位）"
            />
          </ProCard>
        </ProCard>
      </ProCard>
    </PageContainer>
  );
};

export default Overview;
