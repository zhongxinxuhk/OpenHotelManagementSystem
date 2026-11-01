/**
 * 住客登录方式策略（R-20261102-02，真实接口）。
 *
 * 对应 ADR-019 §2.9 的「可用性双条件」：
 * `available = implemented（代码版本）∧ enabled（本页开关）`。
 *
 * 三条呈现规则（与后端语义严格对齐）：
 * 1. `implemented=false` 的行**不给开关**：打开开关对外仍然未开放——
 *    "接通新通道"永远是一次代码发布，不是运维配置；
 * 2. 开关操作只发 `enabled`，其他字段（implemented/available）是服务端的事实，
 *    前端不猜测、不本地乐观更新——改完以服务端返回为准重渲染；
 * 3. 没有 `guest.authpolicy.write` 动作码的账号看到的是只读列表（Switch 禁用），
 *    与后端授权一致——前端禁用只是提示，真正拦住的是服务端。
 */
import type { ProColumns } from '@ant-design/pro-components';
import { PageContainer, ProTable } from '@ant-design/pro-components';
import { useAccess } from '@umijs/max';
import { Alert, App, Popconfirm, Switch, Tag, Typography } from 'antd';
import React, { useCallback, useState } from 'react';
import {
  listGuestLoginPolicy,
  setGuestLoginPolicy,
  type PolicyView,
} from '@/services/pms/platform';
import { describeProblem, toProblem } from '@/services/pms/errors';

/** implemented=false 的方式用灰 Tag；行内说明"为什么不能开"。 */
function ImplementedTag({ policy }: { policy: PolicyView }) {
  if (policy.implemented) {
    return <Tag color="blue">已实现</Tag>;
  }
  return (
    <Tag
      color="default"
      title="后端未实现该登录通道：即使打开开关，对外仍是未开放"
    >
      未实现
    </Tag>
  );
}

const LoginPolicy: React.FC = () => {
  const { message } = App.useApp();
  const access = useAccess();
  const [policies, setPolicies] = useState<PolicyView[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);
  const [switching, setSwitching] = useState<string | undefined>(undefined);

  const canWrite = access?.canWriteAuthPolicy ?? false;

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      setPolicies(await listGuestLoginPolicy());
    } catch (e) {
      setError(describeProblem(toProblem(e)));
      setPolicies([]);
    } finally {
      setLoading(false);
    }
  }, []);

  React.useEffect(() => {
    void load();
  }, [load]);

  const toggle = useCallback(
    async (policy: PolicyView, next: boolean) => {
      setSwitching(policy.provider);
      try {
        const updated = await setGuestLoginPolicy(policy.provider ?? '', {
          enabled: next,
        });
        message.success(`${policy.provider} 已${next ? '启用' : '关停'}`);
        // 以服务端返回为准：available/implemented/updatedAt 全部刷新，不做本地拼装。
        setPolicies((list) =>
          list.map((item) =>
            item.provider === updated.provider ? updated : item,
          ),
        );
      } catch (e) {
        message.error(describeProblem(toProblem(e)));
        // 失败后重拉：避免界面停留在与库不一致的开关状态。
        await load();
      } finally {
        setSwitching(undefined);
      }
    },
    [load, message],
  );

  const columns: ProColumns<PolicyView>[] = [
    {
      title: '登录方式',
      dataIndex: 'provider',
      key: 'provider',
      width: 140,
    },
    {
      title: '实现状态',
      key: 'implemented',
      width: 110,
      render: (_, record) => <ImplementedTag policy={record} />,
    },
    {
      title: '对外可用',
      key: 'available',
      width: 110,
      render: (_, record) =>
        record.available ? (
          <Tag color="green">可用</Tag>
        ) : (
          <Tag color="orange">未开放</Tag>
        ),
    },
    {
      title: '开关',
      key: 'enabled',
      width: 120,
      render: (_, record) => {
        if (!record.implemented) {
          return (
            <Typography.Text
              type="secondary"
              title="未实现的方式不提供开关（ADR-019 §2.9）"
            >
              —
            </Typography.Text>
          );
        }
        if (!canWrite) {
          return <Switch checked={record.enabled} disabled size="small" />;
        }
        // 关停是"对外收紧"：加一步确认，防止表格里手滑。
        if (record.enabled) {
          return (
            <Popconfirm
              title={`关停 ${record.provider}？`}
              description="关停后住客将无法用该方式登录；已建立的会话不受影响。"
              okText="关停"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              onConfirm={() => toggle(record, false)}
            >
              <Switch
                checked
                loading={switching === record.provider}
                size="small"
              />
            </Popconfirm>
          );
        }
        return (
          <Switch
            checked={false}
            loading={switching === record.provider}
            size="small"
            onChange={() => toggle(record, true)}
          />
        );
      },
    },
    {
      title: '最后更新',
      key: 'updatedAt',
      render: (_, record) =>
        record.updatedAt
          ? `${new Date(record.updatedAt).toLocaleString()}（${record.updatedBy ?? '—'}）`
          : '—',
    },
  ];

  return (
    <PageContainer
      header={{
        title: '登录方式策略',
      }}
    >
      {error && (
        <Alert
          type="error"
          showIcon
          title={error}
          style={{ marginBottom: 16 }}
          action={
            <a
              onClick={() => {
                void load();
              }}
            >
              重试
            </a>
          }
        />
      )}
      {!canWrite && (
        <Alert
          type="info"
          showIcon
          title="当前账号无 guest.authpolicy.write 动作码，仅可查看"
          style={{ marginBottom: 16 }}
        />
      )}
      <ProTable<PolicyView>
        headerTitle="住客登录方式"
        rowKey={(record) => record.provider ?? ''}
        columns={columns}
        dataSource={policies}
        loading={loading}
        search={false}
        pagination={false}
        toolBarRender={false}
        options={false}
      />
      <Typography.Paragraph type="secondary" style={{ marginTop: 16 }}>
        对外可用 = 已实现（代码版本）∧ 开关（本页）。未实现的方式打开开关不会生效；
        关停只影响新的登录，已建立的会话到过期或登出为止。
      </Typography.Paragraph>
    </PageContainer>
  );
};

export default LoginPolicy;
