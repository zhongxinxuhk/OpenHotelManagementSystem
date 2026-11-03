/**
 * 实名审核队列（R-20261102-03，真实接口——platform-ops 的核心工作台）。
 *
 * ADR-019 §2.5 的人工审核闭环前端。三条铁律与后端语义对齐：
 * 1. **队列只见掩码**：idNumberMask / realNameMask 是服务端裁剪后的字段，
 *    完整证件号不进队列——本页不提供也不暗示"查看完整证件"的入口；
 * 2. **决定必填理由**：通过与驳回都写 guest_verification_review 留痕
 *    （谁、何时、看到什么、为什么）；驳回不写 verified_at；
 * 3. **留痕可回溯**：每行可查历史（历次决定的掩码快照与理由）。
 *
 * 无 guest.verification.review 动作码的账号看到只读队列（无审核按钮）；
 * 并发提交（他人已先决定）后端返回 409，前端提示后重拉队列。
 */
import type { ProColumns } from '@ant-design/pro-components';
import {
  ModalForm,
  PageContainer,
  ProFormRadio,
  ProFormTextArea,
  ProTable,
} from '@ant-design/pro-components';
import { useAccess } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  Drawer,
  Empty,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import React, { useCallback, useEffect, useState } from 'react';
import {
  listGuestVerificationHistory,
  listGuestVerifications,
  reviewGuestVerification,
  type PendingVerification,
  type ReviewView,
} from '@/services/pms/platform';
import { describeProblem, toProblem } from '@/services/pms/errors';

/** 队列行状态标签。 */
function StatusTag({ status }: { status?: string }) {
  switch (status) {
    case 'PENDING':
      return <Tag color="processing">待审核</Tag>;
    case 'VERIFIED':
      return <Tag color="green">已通过</Tag>;
    case 'REJECTED':
      return <Tag color="red">已驳回</Tag>;
    default:
      return <Tag color="default">未提交</Tag>;
  }
}

/** 历史记录里的决定标签。 */
function DecisionTag({ decision }: { decision?: string }) {
  switch (decision) {
    case 'VERIFIED':
      return <Tag color="green">通过</Tag>;
    case 'REJECTED':
      return <Tag color="red">驳回</Tag>;
    case 'PENDING':
      return <Tag color="processing">转待审</Tag>;
    default:
      return <Tag color="default">{decision ?? '—'}</Tag>;
  }
}

const Queue: React.FC = () => {
  const { message } = App.useApp();
  const access = useAccess();
  const [items, setItems] = useState<PendingVerification[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size] = useState(20);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);
  const [reviewing, setReviewing] = useState<PendingVerification | undefined>(
    undefined,
  );
  const [historyOf, setHistoryOf] = useState<PendingVerification | undefined>(
    undefined,
  );
  const [history, setHistory] = useState<ReviewView[] | undefined>(undefined);
  const [historyLoading, setHistoryLoading] = useState(false);

  const canReview = access?.canReviewVerifications ?? false;

  const load = useCallback(
    async (targetPage = page) => {
      setLoading(true);
      setError(undefined);
      try {
        const result = await listGuestVerifications({
          page: targetPage,
          size,
        });
        setItems(result.items ?? []);
        setTotal(result.total ?? 0);
        setPage(targetPage);
      } catch (e) {
        setError(describeProblem(toProblem(e)));
        setItems([]);
        setTotal(0);
      } finally {
        setLoading(false);
      }
    },
    [page, size],
  );

  useEffect(() => {
    void load(1);
    // 仅挂载时拉第一页；翻页由表格 onChange 触发。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const submitDecision = async (values: {
    decision: 'VERIFIED' | 'REJECTED';
    reason: string;
  }) => {
    if (!reviewing) return false;
    try {
      await reviewGuestVerification(reviewing.accountId ?? '', {
        decision: values.decision,
        reason: values.reason,
      });
      message.success(
        values.decision === 'VERIFIED'
          ? '已通过（留痕已写入）'
          : '已驳回（留痕已写入）',
      );
      setReviewing(undefined);
      await load(page);
      return true;
    } catch (e) {
      const problem = toProblem(e);
      // 并发：他人已先做出决定（后端 409）
      if (problem?.status === 409) {
        message.error('该账户已被其他审核员处理，队列已刷新');
        setReviewing(undefined);
        await load(page);
        return false;
      }
      message.error(describeProblem(problem));
      return false;
    }
  };

  const openHistory = async (record: PendingVerification) => {
    setHistoryOf(record);
    setHistory(undefined);
    setHistoryLoading(true);
    try {
      setHistory(await listGuestVerificationHistory(record.accountId ?? ''));
    } catch (e) {
      message.error(describeProblem(toProblem(e)));
    } finally {
      setHistoryLoading(false);
    }
  };

  const columns: ProColumns<PendingVerification>[] = [
    {
      title: '账户',
      dataIndex: 'accountId',
      key: 'accountId',
      width: 300,
      copyable: true,
      ellipsis: true,
    },
    {
      title: '姓名（掩码）',
      dataIndex: 'realNameMask',
      key: 'realNameMask',
      width: 140,
      render: (_, record) => record.realNameMask ?? '—',
    },
    {
      title: '证件号（掩码）',
      dataIndex: 'idNumberMask',
      key: 'idNumberMask',
      width: 200,
      render: (_, record) => record.idNumberMask ?? '—',
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (_, record) => <StatusTag status={record.status} />,
    },
    {
      title: '提交时间',
      dataIndex: 'submittedAt',
      key: 'submittedAt',
      width: 180,
      render: (_, record) =>
        record.submittedAt
          ? new Date(record.submittedAt).toLocaleString()
          : '—',
    },
    {
      title: '操作',
      key: 'actions',
      width: 160,
      render: (_, record) => (
        <Space size={4}>
          {canReview && record.status === 'PENDING' && (
            <a
              onClick={() => {
                setReviewing(record);
              }}
            >
              审核
            </a>
          )}
          <a
            onClick={() => {
              void openHistory(record);
            }}
          >
            留痕
          </a>
        </Space>
      ),
    },
  ];

  return (
    <PageContainer
      header={{
        title: '实名审核队列',
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
                void load(1);
              }}
            >
              重试
            </a>
          }
        />
      )}
      {!access?.canReadVerifications && (
        <Alert
          type="info"
          showIcon
          title="当前账号无 guest.verification.read 动作码，仅可查看"
          style={{ marginBottom: 16 }}
        />
      )}
      <ProTable<PendingVerification>
        headerTitle={`队列（共 ${total} 条）`}
        rowKey={(record) => record.accountId ?? ''}
        columns={columns}
        dataSource={items}
        loading={loading}
        search={false}
        pagination={{
          current: page,
          pageSize: size,
          total,
          onChange: (next) => {
            void load(next);
          },
        }}
        toolBarRender={() => [
          <Button
            key="refresh"
            onClick={() => {
              void load(page);
            }}
          >
            刷新
          </Button>,
        ]}
        options={false}
      />
      <Typography.Paragraph type="secondary" style={{ marginTop: 16 }}>
        队列只显示姓名与证件号的掩码。通过/驳回都必须填写理由并写入留痕
        （谁、何时、看到什么、为什么）；驳回不会给账户写实名通过标记。
      </Typography.Paragraph>

      {/* 审核决定：通过/驳回 + 必填理由 */}
      {reviewing && (
        <ModalForm<{ decision: 'VERIFIED' | 'REJECTED'; reason: string }>
          title="实名审核决定"
          width={520}
          open
          modalProps={{ destroyOnHidden: true }}
          initialValues={{ decision: 'VERIFIED' }}
          onFinish={submitDecision}
          onOpenChange={(open) => {
            if (!open) setReviewing(undefined);
          }}
        >
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            title={`将审核 ${reviewing.realNameMask ?? '该账户'}（证件 ${reviewing.idNumberMask ?? '—'}）`}
            description="决定与理由会写入留痕；驳回后住客可重新提交。"
          />
          <ProFormRadio.Group
            name="decision"
            label="决定"
            options={[
              { value: 'VERIFIED', label: '通过（实名成立）' },
              { value: 'REJECTED', label: '驳回（材料不符）' },
            ]}
            rules={[{ required: true, message: '请选择决定' }]}
          />
          <ProFormTextArea
            name="reason"
            label="理由（必填，写入留痕）"
            placeholder="例如：证件照与提交姓名一致 / 证件号位数不符"
            rules={[
              { required: true, message: '必须填写理由' },
              { min: 5, message: '理由至少 5 个字符' },
            ]}
          />
        </ModalForm>
      )}

      {/* 留痕历史 */}
      <Drawer
        title={`审核留痕：${historyOf?.realNameMask ?? '—'}`}
        width={640}
        open={Boolean(historyOf)}
        onClose={() => {
          setHistoryOf(undefined);
          setHistory(undefined);
        }}
      >
        {historyLoading && <Table loading />}
        {!historyLoading && history && history.length === 0 && (
          <Empty description="暂无留痕记录（该账户尚未被审核过）" />
        )}
        {!historyLoading && history && history.length > 0 && (
          <Table<ReviewView>
            rowKey={(record) => `${record.accountId}-${record.decidedAt}`}
            dataSource={history}
            pagination={false}
            size="small"
            columns={[
              {
                title: '时间',
                dataIndex: 'decidedAt',
                key: 'decidedAt',
                width: 170,
                render: (_, record) =>
                  record.decidedAt
                    ? new Date(record.decidedAt).toLocaleString()
                    : '—',
              },
              {
                title: '决定',
                dataIndex: 'decision',
                key: 'decision',
                width: 90,
                render: (_, record) => (
                  <DecisionTag decision={record.decision} />
                ),
              },
              {
                title: '来源',
                dataIndex: 'source',
                key: 'source',
                width: 110,
                render: (_, record) => record.source ?? '—',
              },
              {
                title: '审核人',
                dataIndex: 'reviewerName',
                key: 'reviewerName',
                width: 120,
                render: (_, record) => record.reviewerName ?? '—',
              },
              {
                title: '理由',
                dataIndex: 'reason',
                key: 'reason',
                ellipsis: true,
              },
            ]}
          />
        )}
      </Drawer>
    </PageContainer>
  );
};

export default Queue;
