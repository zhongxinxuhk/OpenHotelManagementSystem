/**
 * 酒店/民宿创建（R-20261102-04，表单骨架）。
 *
 * **后端无门店创建接口**（`POST /hotels` 未开放，归 A-01 组织域扩展；
 * 当前门店由租户种子初始化）。本页的定位：
 * 1. 把创建表单的**字段定义**立起来（名称/编码/类型/时区），即需求占位；
 * 2. 提交不做任何网络请求——按钮触发的提示明确说明接口状态。
 *
 * 不假装创建成功（前端规范 §7 同源：呈现真实状态，不演）。
 * 接口就绪后：提交改为调用创建 API + 跳转门店选择器刷新。
 */
import {
  PageContainer,
  ProForm,
  ProFormRadio,
  ProFormText,
} from '@ant-design/pro-components';
import { Alert, App, Typography } from 'antd';
import React from 'react';

type HotelCreateValues = {
  name: string;
  code: string;
  kind: 'HOTEL' | 'GUESTHOUSE';
  timezone: string;
};

const HotelCreate: React.FC = () => {
  const { modal } = App.useApp();

  const onFinish = async (_values: HotelCreateValues) => {
    // 不发请求：接口未开放。用模态框把状态说清楚，而不是静默或假成功。
    modal.info({
      title: '创建接口未开放',
      content:
        '门店创建 API 尚未实现（归 A-01 组织域扩展，规划中）。本表单定义了创建字段；接口就绪后提交将直接建店。',
    });
    return true;
  };

  return (
    <PageContainer
      header={{
        title: '创建酒店/民宿',
      }}
    >
      <Alert
        type="info"
        showIcon
        title="门店创建接口未开放"
        description="当前门店由租户初始化建立。本表单定义将来的创建字段；填写后提交不会发出请求。"
        style={{ marginBottom: 16 }}
      />
      <ProForm<HotelCreateValues>
        layout="horizontal"
        style={{ maxWidth: 560 }}
        initialValues={{ kind: 'HOTEL', timezone: 'Australia/Brisbane' }}
        onFinish={onFinish}
      >
        <ProFormText
          name="name"
          label="门店名称"
          placeholder="如：海滨度假酒店"
          rules={[{ required: true, message: '请输入门店名称' }]}
        />
        <ProFormText
          name="code"
          label="门店编码"
          placeholder="小写字母与数字，如 beach-hotel"
          rules={[
            { required: true, message: '请输入门店编码' },
            {
              pattern: /^[a-z0-9][a-z0-9-]{1,31}$/,
              message: '编码须为 2-32 位小写字母/数字，可含 -',
            },
          ]}
        />
        <ProFormRadio.Group
          name="kind"
          label="类型"
          options={[
            { value: 'HOTEL', label: '酒店' },
            { value: 'GUESTHOUSE', label: '民宿' },
          ]}
          rules={[{ required: true }]}
        />
        <ProFormText
          name="timezone"
          label="时区"
          placeholder="IANA 时区，如 Australia/Brisbane"
          extra="营业日与夜审按此时区计算"
          rules={[{ required: true, message: '请输入时区' }]}
        />
      </ProForm>
      <Typography.Paragraph type="secondary" style={{ marginTop: 16 }}>
        创建后的门店即进入当前租户的门店清单；员工可按门店范围授权。
      </Typography.Paragraph>
    </PageContainer>
  );
};

export default HotelCreate;
