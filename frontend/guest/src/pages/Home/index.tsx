/**
 * 首页：房型浏览（R-20261102-05，骨架页）。
 *
 * 目标形态：选门店 → 选日期 → 看房型与可售（匿名公开面 API）→ 下单。
 * 当前的真实状态：
 * · 公开面 `pms.public-api.enabled=false`（默认关闭，J-05 档位已落地但
 *   开启还需按门店配额 + WAF checklist）；
 * · 门店发现（匿名可查的门店列表）未做——公开 API 需要先知道
 *   tenantCode/hotelCode 才能查房型。
 *
 * 骨架页不调用注定 404 的接口，只呈现真实空态与开启条件。
 */
import { PageContainer } from '@ant-design/pro-components';
import { Alert, Empty, Typography } from 'antd';
import React from 'react';

const Home: React.FC = () => {
  return (
    <PageContainer
      header={{
        title: '房型浏览',
      }}
    >
      <Alert
        type="info"
        showIcon
        title="公开查询未开放"
        description="房型目录接口默认关闭（匿名面开启前需完成按门店配额与 WAF 流程）；门店发现接口未建。开放后本页提供：选门店 → 选日期 → 房型与可售。"
        style={{ marginBottom: 16 }}
      />
      <Empty
        description={
          <Typography.Text type="secondary">
            暂无数据：将来呈现 门店 / 日期区间 / 房型（名称·容量·价格）/ 可售状态
          </Typography.Text>
        }
      />
    </PageContainer>
  );
};

export default Home;
