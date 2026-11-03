/**
 * 运营工作台（R-20261102-03）。
 *
 * 与 platform-admin 概览同源原则：**只呈现真实接口数据与真实空态**。
 * 业务员视角的聚合：实名待审核数（核心工作指标）、核验渠道熔断状态
 * （权威渠道熔断意味着队列会涨）、登录方式开关（用户"登不进来"的工单源头）。
 * 未开放模块（客服工单、业务审计、OTA 授权）给真实空态，不渲染示例数字。
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

const Workbench: React.FC = () => {
  const { initialState } = useModel('@@initialState');
  const currentUser = initialState?.currentUser;

  const [pendingState, setPendingState] = useState<CardState<number | null>>({
    kind: 'loading',
  });
  const [channelState, setChannelState] = useState<CardState<ChannelView[]>>({
    kind: 'loading',
  });
  const [policyState, setPolicyState] = useState<CardState<PolicyView[]>>({
    kind: 'loading',
  });

  const load = useCallback(async () => {
    void fetchCard(async () => {
      const page = await listGuestVerifications({ page: 1, size: 1 });
      return page.total ?? 0;
    }).then(setPendingState);
    void fetchCard(listVerificationChannels).then(setChannelState);
    void fetchCard(listGuestLoginPolicy).then(setPolicyState);
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <PageContainer
      header={{
        title: '运营工作台',
      }}
    >
      <ProCard direction="column" ghost gutter={[0, 16]}>
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
          <ProCard title="实名待审核" style={{ minWidth: 220 }}>
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
                    extra={
                      item.circuitState === 'OPEN' ? (
                        <Badge status="error" text="熔断" />
                      ) : item.circuitState === 'HALF_OPEN' ? (
                        <Badge status="warning" text="半开" />
                      ) : (
                        <Badge status="success" text="闭合" />
                      )
                    }
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
                    extra={
                      item.available ? (
                        <Tag color="green">可用</Tag>
                      ) : (
                        <Tag color="orange">未开放</Tag>
                      )
                    }
                    style={{ padding: '6px 0' }}
                  >
                    <List.Item.Meta title={item.provider} />
                  </List.Item>
                )}
              />
            )}
          </ProCard>
        </ProCard>

        {/* 未开放模块：真实空态 */}
        <ProCard split="vertical" ghost gutter={16}>
          <ProCard title="客服工单">
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="工单接口未开放"
            />
          </ProCard>
          <ProCard title="业务审计">
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="审计查询接口未开放（归 B-05）"
            />
          </ProCard>
          <ProCard title="OTA 商业授权">
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="渠道授权未到位（L 域）"
            />
          </ProCard>
        </ProCard>
      </ProCard>
    </PageContainer>
  );
};

export default Workbench;
