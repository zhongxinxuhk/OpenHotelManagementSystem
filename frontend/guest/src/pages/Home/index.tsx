/**
 * 房型浏览（R-20261103-02 起接真实公开接口）。
 *
 * 匿名可查：登录与否都能看——住客在注册前就要能选房。
 *
 * 门店定位：输入（或 URL 参数携带）租户编码 + 门店编码。
 * 这是"商家分享链接/二维码"的形态——每家门店把带参数的链接发给自己的客人。
 * 门店发现（匿名门店清单）尚未提供（见 backend checklist 已知边界），
 * 不假装存在一个"浏览所有门店"的入口。
 *
 * 呈现纪律：只显示后端给的公开字段（房型名/容量/床位/可售布尔）；
 * 404（门店不存在或未开放预订）与 400（日期区间）如实呈现原文案。
 */
import {
  PageContainer,
  ProCard,
  ProForm,
  ProFormDateRangePicker,
  ProFormText,
} from '@ant-design/pro-components';
import { history } from '@umijs/max';
import { Alert, App, Button, Empty, List, Space, Tag, Typography } from 'antd';
import dayjs from 'dayjs';
import React, { useCallback, useEffect, useState } from 'react';
import {
  listPublicRoomTypes,
  type PublicCatalogView,
} from '@/services/pms/public';
import { describeProblem, toProblem } from '@/services/pms/errors';

type BrowseValues = {
  tenantCode: string;
  hotelCode: string;
  range: [string, string];
};

/** URL 参数 ↔ 表单：分享链接形态（?tenant=pms-admin&hotel=hq&from=…&to=…）。 */
function readQuery(): Partial<BrowseValues> {
  const params = new URL(window.location.href).searchParams;
  const tenant = params.get('tenant');
  const hotel = params.get('hotel');
  const from = params.get('from');
  const to = params.get('to');
  return {
    ...(tenant ? { tenantCode: tenant } : {}),
    ...(hotel ? { hotelCode: hotel } : {}),
    ...(from && to ? { range: [from, to] as [string, string] } : {}),
  };
}

const Browse: React.FC = () => {
  const { message } = App.useApp();
  const [catalog, setCatalog] = useState<PublicCatalogView | undefined>(undefined);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  const search = useCallback(async (values: BrowseValues) => {
    const tenant = values.tenantCode.trim().toLowerCase();
    const hotel = values.hotelCode.trim().toLowerCase();
    const [from, to] = values.range;
    setLoading(true);
    setError(undefined);
    setCatalog(undefined);
    try {
      const result = await listPublicRoomTypes(tenant, hotel, from, to);
      setCatalog(result);
      // 成功后把参数写回 URL：这一页就是可分享的门店链接
      history.replace(`?tenant=${tenant}&hotel=${hotel}&from=${from}&to=${to}`);
    } catch (e) {
      // 404/400 的文案由后端给出（统一话术防探测），原样呈现
      setError(describeProblem(toProblem(e)));
    } finally {
      setLoading(false);
    }
  }, []);

  // URL 带参数进入时自动查询一次（分享链接直达结果）
  useEffect(() => {
    const query = readQuery();
    if (query.tenantCode && query.hotelCode && query.range) {
      void search(query as BrowseValues);
    }
    // 仅挂载时读一次 URL；后续查询由表单触发
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const today = dayjs();

  return (
    <PageContainer
      header={{
        title: '房型浏览',
      }}
    >
      <ProCard direction="column" ghost gutter={[0, 16]}>
        <ProCard title="选择门店与日期">
          <ProForm<BrowseValues>
            layout="inline"
            style={{ maxWidth: 720 }}
            initialValues={{
              ...readQuery(),
              ...(readQuery().range
                ? {}
                : {
                    range: [
                      today.format('YYYY-MM-DD'),
                      today.add(1, 'day').format('YYYY-MM-DD'),
                    ] as [string, string],
                  }),
            }}
            submitter={{
              searchConfig: { submitText: '查询房型' },
              submitButtonProps: { loading },
              resetButtonProps: false,
            }}
            onFinish={async (values) => {
              await search(values as BrowseValues);
              return true;
            }}
          >
            <ProFormText
              name="tenantCode"
              label="租户编码"
              placeholder="如 pms-admin"
              rules={[{ required: true, message: '请输入租户编码' }]}
            />
            <ProFormText
              name="hotelCode"
              label="门店编码"
              placeholder="如 hq"
              rules={[{ required: true, message: '请输入门店编码' }]}
            />
            <ProFormDateRangePicker
              name="range"
              label="入住日期"
              rules={[{ required: true, message: '请选择入住与离店日期' }]}
              fieldProps={{
                disabledDate: (current: dayjs.Dayjs) =>
                  current.isBefore(today, 'day'),
              }}
            />
          </ProForm>
          <Typography.Paragraph
            type="secondary"
            style={{ marginTop: 8, marginBottom: 0 }}
          >
            编码来自门店分享的链接（二维码/网址参数）。窗口最多 62 天。
          </Typography.Paragraph>
        </ProCard>

        {error && (
          <ProCard>
            <Alert type="error" showIcon title={error} />
          </ProCard>
        )}

        {catalog && (
          <ProCard
            title={`${catalog.hotel?.name ?? '门店'} · ${catalog.nights ?? 0} 晚`}
            extra={
              <Button
                onClick={() => {
                  void navigator.clipboard
                    ?.writeText(window.location.href)
                    .then(() => message.success('本页链接已复制'))
                    .catch(() => message.info('请从地址栏复制链接'));
                }}
              >
                复制本页链接
              </Button>
            }
          >
            {(catalog.roomTypes ?? []).length === 0 ? (
              <Empty description="该门店暂无在售房型" />
            ) : (
              <List
                dataSource={catalog.roomTypes ?? []}
                rowKey={(item) => item.code ?? item.name ?? ''}
                renderItem={(item) => (
                  <List.Item
                    actions={[
                      item.bookable ? (
                        <Tag key="ok" color="green">
                          可预订
                        </Tag>
                      ) : (
                        <Tag key="no" color="default">
                          满房或不可售
                        </Tag>
                      ),
                    ]}
                  >
                    <List.Item.Meta
                      title={item.name}
                      description={
                        <Space size="small" wrap>
                          {item.bedType && <span>{item.bedType}</span>}
                          <span>
                            {item.capacityAdults ?? 0} 成人
                            {item.capacityChildren
                              ? ` / ${item.capacityChildren} 儿童`
                              : ''}
                          </span>
                        </Space>
                      }
                    />
                  </List.Item>
                )}
              />
            )}
          </ProCard>
        )}

        {!catalog && !error && !loading && (
          <ProCard>
            <Empty description="输入门店编码与日期后查询" />
          </ProCard>
        )}
      </ProCard>
    </PageContainer>
  );
};

export default Browse;
