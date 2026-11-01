/**
 * 运维处置（S3-01）。
 *
 * 覆盖后端 S2-04 的死信/外部任务/处置留痕，以及 S2-05 的对账工单。
 *
 * 危险动作（重放 = 再发一次外部请求）必须二次确认并要求填写原因 ——
 * 原因会写进 `ops_intervention` 留痕，是事后追责的唯一线索。
 */

import type { ProColumns } from '@ant-design/pro-components';
import {
  ModalForm,
  PageContainer,
  ProCard,
  ProFormTextArea,
  ProTable,
  StatisticCard,
} from '@ant-design/pro-components';
import { App, Button, Popconfirm } from 'antd';
import React, { useCallback, useEffect, useState } from 'react';
import { useHotelContext } from '@/components/HotelSelector/context';
import { OpsStatusTag, SeverityTag } from '@/components/StatusTag';
import { describeProblem, toProblem } from '@/services/pms/errors';
import {
  type DeadLetter,
  dismissDeadLetter,
  type ExternalTask,
  getOpsPending,
  listDeadLetters,
  listExternalTasks,
  listOpsInterventions,
  type OpsIntervention,
  type PendingSummary,
  replayDeadLetter,
  replayExternalTask,
  resolveExternalTask,
} from '@/services/pms/ops';
import {
  type FindingView,
  getReconcileSummary,
  listReconcileFindings,
  repairReconcileFinding,
  type SummaryView,
} from '@/services/pms/reconcile';

/**
 * 处置原因弹窗：重放/忽略/结案都必须给原因。
 *
 * 用 ModalForm + trigger 承载：原因必填，且内容会写进 `ops_intervention` 留痕，
 * 是事后追责的唯一线索——所以不给"跳过"的路径。
 */
const ReasonForm: React.FC<{
  title: string;
  trigger: React.ReactElement;
  onSubmit: (reason: string) => Promise<void>;
}> = ({ title, trigger, onSubmit }) => (
  <ModalForm
    title={title}
    trigger={trigger}
    modalProps={{ destroyOnClose: true }}
    onFinish={async (values) => {
      await onSubmit(values.reason as string);
      return true;
    }}
  >
    <ProFormTextArea
      name="reason"
      label="处置原因"
      placeholder="写明判断依据；该内容会写入处置留痕，事后据此复核"
      rules={[{ required: true, message: '请填写处置原因' }]}
      fieldProps={{ maxLength: 200 }}
    />
  </ModalForm>
);

const OpsPage: React.FC = () => {
  const { currentHotelId } = useHotelContext();
  const { message } = App.useApp();

  const [pending, setPending] = useState<PendingSummary | null>(null);
  const [deadLetters, setDeadLetters] = useState<DeadLetter[]>([]);
  const [tasks, setTasks] = useState<ExternalTask[]>([]);
  const [interventions, setInterventions] = useState<OpsIntervention[]>([]);
  const [summary, setSummary] = useState<SummaryView | null>(null);
  const [findings, setFindings] = useState<FindingView[]>([]);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [pendingView, dead, external, records] = await Promise.all([
        getOpsPending(),
        listDeadLetters({ limit: 50 }),
        listExternalTasks({ limit: 50 }),
        listOpsInterventions({ limit: 50 }),
      ]);
      setPending(pendingView);
      setDeadLetters(dead);
      setTasks(external);
      setInterventions(records);

      if (currentHotelId) {
        const [summaryView, findingList] = await Promise.all([
          getReconcileSummary(currentHotelId),
          listReconcileFindings(currentHotelId, { status: 'OPEN', limit: 100 }),
        ]);
        setSummary(summaryView);
        setFindings(findingList);
      }
    } catch (error) {
      message.error(describeProblem(toProblem(error)));
    } finally {
      setLoading(false);
    }
  }, [currentHotelId, message]);

  useEffect(() => {
    void load();
  }, [load]);

  const deadLetterColumns: ProColumns<DeadLetter>[] = [
    { title: '死信 ID', dataIndex: 'deadLetterId', copyable: true, width: 240 },
    { title: '来源', dataIndex: 'source', width: 120 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (_, row) => <OpsStatusTag status={row.status} />,
    },
    { title: '错误', dataIndex: 'error', ellipsis: true },
    { title: '接收时间', dataIndex: 'receivedAt', width: 200 },
    {
      title: '操作',
      valueType: 'option',
      width: 180,
      render: (_, row) => [
        <ReasonForm
          key="replay"
          title="重放死信"
          trigger={
            <Button type="link" size="small">
              重放
            </Button>
          }
          onSubmit={async (reason) => {
            try {
              if (!row.deadLetterId) return;
              const result = await replayDeadLetter(row.deadLetterId, reason);
              message.success(
                result.applied ? '已重放' : `未重放：${result.detail ?? ''}`,
              );
              void load();
            } catch (error) {
              message.error(describeProblem(toProblem(error)));
            }
          }}
        />,
        <Popconfirm
          key="dismiss"
          title="忽略该死信"
          description="确认不再重放该消息"
          onConfirm={async () => {
            try {
              if (!row.deadLetterId) return;
              await dismissDeadLetter(row.deadLetterId, '人工确认无需重放');
              message.success('已忽略');
              void load();
            } catch (error) {
              message.error(describeProblem(toProblem(error)));
            }
          }}
        >
          <Button type="link" size="small" danger>
            忽略
          </Button>
        </Popconfirm>,
      ],
    },
  ];

  const taskColumns: ProColumns<ExternalTask>[] = [
    { title: '任务 ID', dataIndex: 'taskId', copyable: true, width: 240 },
    { title: '类型', dataIndex: 'taskType', width: 140 },
    { title: '渠道', dataIndex: 'channel', width: 100 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (_, row) => <OpsStatusTag status={row.status} />,
    },
    { title: '尝试', dataIndex: 'attempts', width: 80 },
    { title: '上次错误', dataIndex: 'lastError', ellipsis: true },
    {
      title: '操作',
      valueType: 'option',
      width: 200,
      render: (_, row) => [
        <ReasonForm
          key="replay"
          title="重放外部任务"
          trigger={
            <Button type="link" size="small">
              重放
            </Button>
          }
          onSubmit={async (reason) => {
            try {
              if (!row.taskId) return;
              const result = await replayExternalTask(row.taskId, reason);
              message.success(
                result.applied ? '已重放' : `未重放：${result.detail ?? ''}`,
              );
              void load();
            } catch (error) {
              message.error(describeProblem(toProblem(error)));
            }
          }}
        />,
        <Popconfirm
          key="resolve"
          title="人工结案"
          description="标记该任务已成功完成，不再重试"
          onConfirm={async () => {
            try {
              if (!row.taskId) return;
              await resolveExternalTask(row.taskId, {
                succeeded: true,
                reason: '人工确认已完成',
              });
              message.success('已结案');
              void load();
            } catch (error) {
              message.error(describeProblem(toProblem(error)));
            }
          }}
        >
          <Button type="link" size="small">
            结案
          </Button>
        </Popconfirm>,
      ],
    },
  ];

  const findingColumns: ProColumns<FindingView>[] = [
    { title: '差异 ID', dataIndex: 'findingId', copyable: true, width: 240 },
    { title: '类型', dataIndex: 'kind', width: 140 },
    {
      title: '严重度',
      dataIndex: 'severity',
      width: 110,
      render: (_, row) => <SeverityTag status={row.severity} />,
    },
    { title: '住宿日', dataIndex: 'stayDate', width: 120 },
    { title: '次数', dataIndex: 'occurrences', width: 80 },
    { title: '详情', dataIndex: 'detail', ellipsis: true },
    {
      title: '操作',
      valueType: 'option',
      width: 120,
      render: (_, row) => [
        <Button
          key="repair"
          type="link"
          size="small"
          disabled={row.severity !== 'DERIVED'}
          onClick={async () => {
            if (!currentHotelId) return;
            try {
              if (!row.findingId) return;
              const result = await repairReconcileFinding(
                currentHotelId,
                row.findingId,
              );
              message.success(
                result.detail ?? (result.applied ? '已修复' : '未修复'),
              );
              void load();
            } catch (error) {
              const problem = toProblem(error);
              message.error(describeProblem(problem));
              // 事实类差异不能自动修复：后端会返回 409。
              if (problem.outcome === 'CONFLICT') {
                message.warning('事实类差异需要人工核对，不能自动修复');
              }
            }
          }}
        >
          修复
        </Button>,
      ],
    },
  ];

  const interventionColumns: ProColumns<OpsIntervention>[] = [
    {
      title: '处置 ID',
      dataIndex: 'interventionId',
      copyable: true,
      width: 240,
    },
    { title: '对象', dataIndex: 'targetType', width: 140 },
    { title: '动作', dataIndex: 'action', width: 140 },
    { title: '操作者', dataIndex: 'operatorName', width: 140 },
    { title: '原因', dataIndex: 'reason', ellipsis: true },
    { title: '时间', dataIndex: 'occurredAt', width: 200 },
  ];

  return (
    <PageContainer
      title="运维处置"
      subTitle="死信、外部任务、对账差异与处置留痕"
    >
      <ProCard gutter={16} ghost wrap>
        <ProCard colSpan={{ xs: 24, sm: 8, lg: 6 }}>
          <StatisticCard
            statistic={{
              title: '待处理死信',
              value: pending?.deadLetters ?? 0,
            }}
          />
        </ProCard>
        <ProCard colSpan={{ xs: 24, sm: 8, lg: 6 }}>
          <StatisticCard
            statistic={{
              title: '卡住的外部任务',
              value: pending?.stuckTasks ?? 0,
            }}
          />
        </ProCard>
        <ProCard colSpan={{ xs: 24, sm: 8, lg: 6 }}>
          <StatisticCard
            statistic={{
              title: '挂起的事件',
              value: pending?.parkedInbox ?? 0,
            }}
          />
        </ProCard>
        <ProCard colSpan={{ xs: 24, sm: 8, lg: 6 }}>
          <StatisticCard
            statistic={{ title: '未结差异', value: summary?.openTotal ?? 0 }}
          />
        </ProCard>
      </ProCard>

      {summary && !summary.lastRun && (
        <div style={{ color: '#8a8f99', fontSize: 12, marginTop: 12 }}>
          该门店尚未跑过对账，差异数为 0 不代表一切正常。
        </div>
      )}

      <ProTable<DeadLetter>
        style={{ marginTop: 16 }}
        headerTitle="死信"
        rowKey="deadLetterId"
        columns={deadLetterColumns}
        dataSource={deadLetters}
        loading={loading}
        search={false}
        options={false}
        pagination={{ pageSize: 10 }}
        locale={{ emptyText: '没有待处理的死信' }}
      />

      <ProTable<ExternalTask>
        style={{ marginTop: 24 }}
        headerTitle="外部任务"
        rowKey="taskId"
        columns={taskColumns}
        dataSource={tasks}
        loading={loading}
        search={false}
        options={false}
        pagination={{ pageSize: 10 }}
        locale={{ emptyText: '没有卡住的外部任务' }}
      />

      <ProTable<FindingView>
        style={{ marginTop: 24 }}
        headerTitle="对账差异"
        rowKey="findingId"
        columns={findingColumns}
        dataSource={findings}
        loading={loading}
        search={false}
        options={false}
        pagination={{ pageSize: 10 }}
        locale={{ emptyText: '没有未结的对账差异' }}
      />

      <ProTable<OpsIntervention>
        style={{ marginTop: 24 }}
        headerTitle="处置留痕"
        rowKey="interventionId"
        columns={interventionColumns}
        dataSource={interventions}
        loading={loading}
        search={false}
        options={false}
        pagination={{ pageSize: 10 }}
        locale={{ emptyText: '还没有人工处置记录' }}
      />
    </PageContainer>
  );
};

export default OpsPage;
