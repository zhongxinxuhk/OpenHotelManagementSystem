/**
 * 核验渠道管理（R-20261102-02，真实接口）。
 *
 * 语义对齐 ADR-019 §2.5/§2.6：
 * · 渠道是**配置载体**：kind=AUTHORITY（权威接口）或 MANUAL（人工）；
 * · 人工渠道不可停用——权威渠道熔断时人工审核照常是**结构保证**，
 *   界面上也不给停用入口，别让运维在界面上找"绕过结构"的路径；
 * · 熔断复位必须填原因（后端强制）：防止"悄悄重置掩盖真实故障"；
 * · `implementationAvailable=false` 表示该 kind 没有代码实现（如权威渠道
 *   的具体对接还没写），如实展示，不装作能用。
 */
import type { ProColumns } from '@ant-design/pro-components';
import {
  ModalForm,
  PageContainer,
  ProFormDigit,
  ProFormSelect,
  ProFormText,
  ProTable,
} from '@ant-design/pro-components';
import { useAccess } from '@umijs/max';
import {
  Alert,
  App,
  Badge,
  Button,
  Input,
  Modal,
  Popconfirm,
  Space,
  Tag,
  Typography,
} from 'antd';
import React, { useCallback, useState } from 'react';
import {
  createVerificationChannel,
  listVerificationChannels,
  resetVerificationChannelCircuit,
  updateVerificationChannel,
  type ChannelView,
  type CreateChannelRequest,
  type UpdateChannelRequest,
} from '@/services/pms/platform';
import { describeProblem, toProblem } from '@/services/pms/errors';

type ChannelFormValues = CreateChannelRequest;

type EditFormValues = UpdateChannelRequest;

const CircuitStateBadge: React.FC<{ channel: ChannelView }> = ({
  channel,
}) => {
  switch (channel.circuitState) {
    case 'OPEN':
      return <Badge status="error" text="熔断" />;
    case 'HALF_OPEN':
      return <Badge status="warning" text="半开" />;
    default:
      return <Badge status="success" text="闭合" />;
  }
};

const Channels: React.FC = () => {
  const { message } = App.useApp();
  const access = useAccess();
  const [channels, setChannels] = useState<ChannelView[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);
  const [createOpen, setCreateOpen] = useState(false);
  const [editing, setEditing] = useState<ChannelView | undefined>(undefined);
  const [resetting, setResetting] = useState<ChannelView | undefined>(
    undefined,
  );
  const [resetReason, setResetReason] = useState('');

  const canWrite = access?.canWriteChannels ?? false;

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      setChannels(await listVerificationChannels());
    } catch (e) {
      setError(describeProblem(toProblem(e)));
      setChannels([]);
    } finally {
      setLoading(false);
    }
  }, []);

  React.useEffect(() => {
    void load();
  }, [load]);

  const handleCreate = async (values: ChannelFormValues) => {
    try {
      await createVerificationChannel(values);
      message.success(`渠道 ${values.code} 已创建`);
      await load();
      return true;
    } catch (e) {
      message.error(describeProblem(toProblem(e)));
      return false;
    }
  };

  const handleUpdate = async (values: EditFormValues) => {
    if (!editing) return false;
    try {
      await updateVerificationChannel(editing.code ?? '', values);
      message.success(`渠道 ${editing.code} 已更新`);
      setEditing(undefined);
      await load();
      return true;
    } catch (e) {
      message.error(describeProblem(toProblem(e)));
      return false;
    }
  };

  const toggleStatus = async (channel: ChannelView) => {
    const next: UpdateChannelRequest = {
      name: channel.name ?? '',
      status: channel.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
      endpoint: channel.endpoint ?? undefined,
      credentialRef: channel.credentialRef ?? undefined,
      priority: channel.priority ?? undefined,
      timeoutMs: channel.timeoutMs ?? undefined,
    };
    try {
      await updateVerificationChannel(channel.code ?? '', next);
      message.success(
        `${channel.code} 已${next.status === 'ACTIVE' ? '启用' : '停用'}`,
      );
      await load();
    } catch (e) {
      message.error(describeProblem(toProblem(e)));
    }
  };

  const handleResetCircuit = async (
    channel: ChannelView,
    reason: string,
  ) => {
    try {
      await resetVerificationChannelCircuit(channel.code ?? '', { reason });
      message.success(`${channel.code} 熔断已复位（原因已留痕）`);
      setResetting(undefined);
      setResetReason('');
      await load();
    } catch (e) {
      message.error(describeProblem(toProblem(e)));
    }
  };

  const columns: ProColumns<ChannelView>[] = [
    { title: '编码', dataIndex: 'code', key: 'code', width: 140 },
    {
      title: '名称',
      dataIndex: 'name',
      key: 'name',
      width: 180,
    },
    {
      title: '类型',
      dataIndex: 'kind',
      key: 'kind',
      width: 100,
      render: (_, record) =>
        record.kind === 'MANUAL' ? (
          <Tag color="purple" title="人工渠道：权威渠道熔断时的兜底，不可停用">
            人工
          </Tag>
        ) : (
          <Tag color="geekblue" title="权威接口渠道">
            权威
          </Tag>
        ),
    },
    {
      title: '实现',
      key: 'implementationAvailable',
      width: 90,
      render: (_, record) =>
        record.implementationAvailable ? (
          <Tag color="blue">可用</Tag>
        ) : (
          <Tag color="default" title="该类型的代码实现尚未接入">
            未接通
          </Tag>
        ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (_, record) =>
        record.status === 'ACTIVE' ? (
          <Tag color="green">启用</Tag>
        ) : (
          <Tag color="orange">停用</Tag>
        ),
    },
    {
      title: '熔断',
      key: 'circuit',
      width: 130,
      render: (_, record) => (
        <Space direction="vertical" size={0}>
          <CircuitStateBadge channel={record} />
          {record.circuitState === 'OPEN' && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              连续失败 {record.consecutiveFailures ?? 0} 次
              {record.lastFailureReason ? `：${record.lastFailureReason}` : ''}
            </Typography.Text>
          )}
        </Space>
      ),
    },
    {
      title: '地址',
      dataIndex: 'endpoint',
      key: 'endpoint',
      ellipsis: true,
      render: (_, record) => record.endpoint ?? '—',
    },
    {
      title: '优先级',
      dataIndex: 'priority',
      key: 'priority',
      width: 80,
      render: (_, record) => record.priority ?? '—',
    },
    {
      title: '超时(ms)',
      dataIndex: 'timeoutMs',
      key: 'timeoutMs',
      width: 90,
      render: (_, record) => record.timeoutMs ?? '—',
    },
  ];

  if (canWrite) {
    columns.push({
      title: '操作',
      key: 'actions',
      width: 220,
      render: (_, record) => (
        <Space size={4}>
          <a
            onClick={() => {
              setEditing(record);
            }}
          >
            编辑
          </a>
          {/* 人工渠道不给停用入口：它是熔断兜底（ADR-019 §2.5 的结构保证）。 */}
          {record.kind !== 'MANUAL' && (
            <Popconfirm
              title={`${record.status === 'ACTIVE' ? '停用' : '启用'}渠道 ${record.code}？`}
              description="停用后该渠道不再参与实名决定；人工渠道不受影响。"
              okText="确认"
              cancelText="取消"
              onConfirm={() => toggleStatus(record)}
            >
              <a>{record.status === 'ACTIVE' ? '停用' : '启用'}</a>
            </Popconfirm>
          )}
          {record.circuitState !== 'CLOSED' && (
            <a
              onClick={() => {
                setResetting(record);
              }}
            >
              复位熔断
            </a>
          )}
        </Space>
      ),
    });
  }

  return (
    <PageContainer
      header={{
        title: '核验渠道',
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
          title="当前账号无 guest.channel.write 动作码，仅可查看"
          style={{ marginBottom: 16 }}
        />
      )}
      <ProTable<ChannelView>
        headerTitle="渠道配置与熔断状态"
        rowKey={(record) => record.code ?? ''}
        columns={columns}
        dataSource={channels}
        loading={loading}
        search={false}
        pagination={false}
        toolBarRender={() => [
          <Button
            key="create"
            type="primary"
            disabled={!canWrite}
            onClick={() => {
              setCreateOpen(true);
            }}
          >
            新建渠道
          </Button>,
        ]}
        options={false}
      />
      <Typography.Paragraph type="secondary" style={{ marginTop: 16 }}>
        权威渠道熔断时人工审核照常进行（结构保证）。复位熔断必须填写原因并留痕；
        人工渠道不可停用。
      </Typography.Paragraph>

      {/* 新建渠道 */}
      <ModalForm<ChannelFormValues>
        title="新建核验渠道"
        width={480}
        open={createOpen}
        onOpenChange={setCreateOpen}
        modalProps={{ destroyOnHidden: true }}
        onFinish={handleCreate}
      >
        <ProFormText
          name="code"
          label="渠道编码"
          placeholder="如 AUTHORITY:xyz-channel"
          rules={[{ required: true, message: '请输入渠道编码' }]}
        />
        <ProFormText
          name="name"
          label="渠道名称"
          rules={[{ required: true, message: '请输入渠道名称' }]}
        />
        <ProFormSelect
          name="kind"
          label="类型"
          options={[
            { value: 'AUTHORITY', label: '权威接口' },
            { value: 'MANUAL', label: '人工' },
          ]}
          rules={[{ required: true, message: '请选择类型' }]}
        />
        <ProFormText name="endpoint" label="接口地址" placeholder="https://…" />
        <ProFormText
          name="credentialRef"
          label="凭据引用"
          placeholder="密钥在服务端凭据库中的引用名，不填明文"
        />
        <ProFormDigit
          name="priority"
          label="优先级"
          min={0}
          fieldProps={{ precision: 0 }}
        />
        <ProFormDigit
          name="timeoutMs"
          label="超时（毫秒）"
          min={100}
          fieldProps={{ precision: 0 }}
        />
      </ModalForm>

      {/* 编辑渠道（不含 code/kind：标识与类型不可改，改了等于换渠道） */}
      {editing && (
        <ModalForm<EditFormValues>
          title={`编辑渠道 ${editing.code}`}
          width={480}
          open
          modalProps={{ destroyOnHidden: true }}
          initialValues={{
            name: editing.name,
            status: editing.status,
            endpoint: editing.endpoint ?? undefined,
            credentialRef: editing.credentialRef ?? undefined,
            priority: editing.priority ?? undefined,
            timeoutMs: editing.timeoutMs ?? undefined,
          }}
          onFinish={handleUpdate}
          onOpenChange={(open) => {
            if (!open) setEditing(undefined);
          }}
        >
          <ProFormText
            name="name"
            label="渠道名称"
            rules={[{ required: true, message: '请输入渠道名称' }]}
          />
          <ProFormSelect
            name="status"
            label="状态"
            disabled={editing.kind === 'MANUAL'}
            options={[
              { value: 'ACTIVE', label: '启用' },
              { value: 'INACTIVE', label: '停用' },
            ]}
            rules={[{ required: true }]}
          />
          <ProFormText name="endpoint" label="接口地址" />
          <ProFormText name="credentialRef" label="凭据引用" />
          <ProFormDigit name="priority" label="优先级" min={0} />
          <ProFormDigit name="timeoutMs" label="超时（毫秒）" min={100} />
        </ModalForm>
      )}

      {/* 熔断复位：必填原因（留痕） */}
      <Modal
        title={`复位熔断：${resetting?.code ?? ''}`}
        open={Boolean(resetting)}
        onCancel={() => {
          setResetting(undefined);
          setResetReason('');
        }}
        okText="复位"
        cancelText="取消"
        okButtonProps={{ danger: true, disabled: !resetReason.trim() }}
        onOk={() => {
          const reason = resetReason.trim();
          if (!reason) {
            message.warning('必须填写复位原因');
            return Promise.reject(new Error('no reason'));
          }
          if (resetting) {
            void handleResetCircuit(resetting, reason);
          }
          return Promise.resolve();
        }}
      >
        <Typography.Paragraph>
          当前连续失败 {resetting?.consecutiveFailures ?? 0} 次
          {resetting?.lastFailureReason
            ? `，最后原因：${resetting.lastFailureReason}`
            : ''}
          。
        </Typography.Paragraph>
        <Input
          value={resetReason}
          onChange={(e) => {
            setResetReason(e.target.value);
          }}
          placeholder="复位原因（必填，写入留痕）"
        />
      </Modal>
    </PageContainer>
  );
};

export default Channels;
