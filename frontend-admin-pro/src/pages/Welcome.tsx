import type { ProColumns } from '@ant-design/pro-components';
import {
  PageContainer,
  ProCard,
  ProTable,
  StatisticCard,
} from '@ant-design/pro-components';
import { Tag, Typography } from 'antd';
import dayjs from 'dayjs';
import React from 'react';

/**
 * 今日工作台（首页，取代模板的营销式欢迎页）。
 *
 * 为什么是「骨架 + 明确的未接入状态」而不是填假数据：
 * 后端目前只有房间维护与订单命令（建单 / 确认 / 取消 / No-show），**还没有订单查询与房态投影接口**
 * （S1-11 订单列表与详情、S1-12 可售投影、S3-03 房态日历）。此时任何数字都只能来自 mock，
 * 而 mock 数据不能作为验收证据（前端规范 §1）。所以指标位留 "—" 并标注来源任务，
 * 等接口交付后在此接真实数据，页面结构不需要重做。
 *
 * 文案遵守「禁止自述式营销文本」：标题与列名都是业务名词，不写"本页面可以帮助您"、
 * 不写产品理念，不放装饰性英文标语；副标题只写营业日。
 */
type TodoRow = {
  id: string;
  reservationNo: string;
  kind: string;
  roomCode: string;
  stayRange: string;
  status: string;
};

const columns: ProColumns<TodoRow>[] = [
  {
    title: '订单号',
    dataIndex: 'reservationNo',
    copyable: true,
  },
  {
    title: '事项',
    dataIndex: 'kind',
    render: (_, row) => <Tag>{row.kind}</Tag>,
  },
  {
    title: '房号',
    dataIndex: 'roomCode',
  },
  {
    title: '日期区间',
    dataIndex: 'stayRange',
  },
  {
    title: '状态',
    dataIndex: 'status',
  },
];

/** 指标位与它的数据来源：来源写清楚，避免以后有人以为这些数字只是没接上而已。 */
const METRICS: { key: string; title: string; source: string }[] = [
  { key: 'inHouse', title: '在住房间', source: '入住事实（S3-04）' },
  { key: 'pending', title: '待确认订单', source: '订单查询（S1-11）' },
  { key: 'arrivals', title: '今日到店', source: '订单查询（S1-11）' },
  { key: 'departures', title: '今日离店', source: '订单查询（S1-11）' },
];

const Welcome: React.FC = () => (
  <PageContainer
    title="今日工作台"
    subTitle={`营业日 ${dayjs().format('YYYY-MM-DD')}`}
  >
    <ProCard gutter={16} ghost wrap>
      {METRICS.map((metric) => (
        <ProCard key={metric.key} colSpan={{ xs: 24, sm: 12, lg: 6 }}>
          <StatisticCard
            statistic={{
              title: metric.title,
              value: '—',
              description: (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  待接入：{metric.source}
                </Typography.Text>
              ),
            }}
          />
        </ProCard>
      ))}
    </ProCard>

    <ProCard title="待办" style={{ marginTop: 16 }}>
      <ProTable<TodoRow>
        rowKey="id"
        columns={columns}
        dataSource={[]}
        search={false}
        options={false}
        pagination={false}
        locale={{
          emptyText: '订单查询接口交付后接入（S1-11）',
        }}
      />
    </ProCard>
  </PageContainer>
);

export default Welcome;
