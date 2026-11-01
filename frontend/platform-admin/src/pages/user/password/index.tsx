/**
 * 修改口令（S3-02 续）。
 *
 * 两个入口共用这一页：
 * 1. **强制改密**：账号还在用管理员发放的初始口令。登录后直接被送到这里，
 *    改完才能使用其它功能（服务端也会拒绝其它接口，不只是前端跳转）；
 * 2. **主动改密**：从右上角菜单进来，随时可以换。
 *
 * 为什么这一页不进主框架（路由 `layout: false`）：主框架顶栏的门店选择器会拉
 * `/api/v1/hotels`，而待改密状态下所有业务接口都被拒——人一进来先看到一串报错，
 * 却不知道原因就是"口令还没改"。
 *
 * 文案遵守前端规范 §7：不写产品自述与欢迎辞，只写字段与真实后果。
 */

import { LockOutlined } from '@ant-design/icons';
import { ProForm, ProFormText } from '@ant-design/pro-components';
import { Helmet, history, useModel } from '@umijs/max';
import { Alert, App, Typography } from 'antd';
import { createStyles } from 'antd-style';
import React, { useState } from 'react';
import { Footer } from '@/components';
import { platformChangePassword } from '@/services/pms/auth';
import { describeProblem, toProblem } from '@/services/pms/errors';
import Settings from '../../../../config/defaultSettings';

/** 与后端 PasswordPolicy 保持一致：12 位下限。改这里必须同步改后端，否则规则会分叉。 */
const MIN_LENGTH = 12;

const useStyles = createStyles(({ token }) => ({
  container: {
    display: 'flex',
    flexDirection: 'column',
    minHeight: '100vh',
    backgroundColor: token.colorBgLayout,
  },
  body: {
    flex: '1',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    padding: '32px 16px',
  },
  card: {
    width: '100%',
    maxWidth: 460,
    padding: 32,
    backgroundColor: token.colorBgContainer,
    borderRadius: token.borderRadiusLG,
    boxShadow: token.boxShadowTertiary,
  },
}));

/** 只允许同源相对路径，防止开放重定向。 */
function safeRedirect(raw: string | null): string {
  if (!raw?.startsWith('/') || raw.startsWith('//')) return '/';
  try {
    const parsed = new URL(raw, window.location.origin);
    if (parsed.origin !== window.location.origin) return '/';
    return `${parsed.pathname}${parsed.search}${parsed.hash}`;
  } catch {
    return '/';
  }
}

type PasswordFormValues = {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
};

const ChangePassword: React.FC = () => {
  const { styles } = useStyles();
  const { message } = App.useApp();
  const { initialState } = useModel('@@initialState');
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');

  /** 账号是否处于"必须改密"状态——决定提示的语气与是否给"取消"入口。 */
  const forced = Boolean(initialState?.currentUser?.mustChangePassword);
  const redirectUrl = safeRedirect(
    new URL(window.location.href).searchParams.get('redirect'),
  );

  const handleFinish = async (values: PasswordFormValues) => {
    setSubmitting(true);
    setErrorMessage('');
    try {
      await platformChangePassword({
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      });

      // 后端会回收该账号的**其他**会话，当前会话保留。
      // 重新拉一次身份，让界面上的待改密标记消失（服务端是按请求实时读的）。
      const userInfo = await initialState?.fetchUserInfo?.();
      message.success('口令已更新');

      // 用整页跳转而不是 history.push：待改密期间主框架可能带着旧状态渲染过，
      // 整页重载能确保后续界面全部按新的身份状态重建。
      if (userInfo) {
        window.location.href = forced ? redirectUrl : '/';
        return;
      }
      window.location.href = redirectUrl;
    } catch (error) {
      // 后端对"原口令错"统一返回同一句话，不区分具体原因。
      setErrorMessage(describeProblem(toProblem(error)));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className={styles.container}>
      <Helmet>
        <title>
          修改口令{Settings.title && ` - ${Settings.title}`}
        </title>
      </Helmet>
      <div className={styles.body}>
        <div className={styles.card}>
          <Typography.Title level={4} style={{ marginTop: 0 }}>
            修改口令
          </Typography.Title>

          {forced ? (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 20 }}
              title="请先设置自己的口令"
              description="当前账号仍在使用管理员发放的初始口令。改完之后，管理员手上的那份立即作废，账号操作才真正记在你名下。"
            />
          ) : (
            <Typography.Paragraph type="secondary" style={{ marginBottom: 20 }}>
              改完之后，你在其他设备上的登录会话会被清除，需要重新登录。
            </Typography.Paragraph>
          )}

          {errorMessage && (
            <Alert
              type="error"
              showIcon
              style={{ marginBottom: 20 }}
              title={errorMessage}
            />
          )}

          <ProForm<PasswordFormValues>
            layout="vertical"
            submitter={{
              searchConfig: { submitText: '保存新口令' },
              submitButtonProps: { loading: submitting, block: true },
              render: (_props, dom) =>
                forced
                  ? dom
                  : [
                      ...(Array.isArray(dom) ? dom : [dom]),
                      <Typography.Text
                        key="back"
                        type="secondary"
                        style={{ display: 'block', textAlign: 'center', marginTop: 12 }}
                      >
                        <a
                          onClick={() => {
                            history.push('/');
                          }}
                        >
                          返回
                        </a>
                      </Typography.Text>,
                    ],
            }}
            onFinish={handleFinish}
          >
            <ProFormText.Password
              name="currentPassword"
              label="当前口令"
              fieldProps={{ size: 'large', prefix: <LockOutlined /> }}
              rules={[{ required: true, message: '请输入当前口令' }]}
            />

            <ProFormText.Password
              name="newPassword"
              label="新口令"
              placeholder={`至少 ${MIN_LENGTH} 位，且不要包含登录名`}
              fieldProps={{ size: 'large', prefix: <LockOutlined /> }}
              rules={[
                { required: true, message: '请输入新口令' },
                { min: MIN_LENGTH, message: `新口令长度不足 ${MIN_LENGTH} 位` },
                {
                  validator: (_, value) => {
                    const username = initialState?.currentUser?.username;
                    if (
                      value &&
                      username &&
                      String(value).toLowerCase().includes(username.toLowerCase())
                    ) {
                      return Promise.reject(new Error('新口令不能包含登录名'));
                    }
                    return Promise.resolve();
                  },
                },
              ]}
            />

            <ProFormText.Password
              name="confirmPassword"
              label="确认新口令"
              fieldProps={{ size: 'large', prefix: <LockOutlined /> }}
              dependencies={['newPassword']}
              rules={[
                { required: true, message: '请再次输入新口令' },
                ({ getFieldValue }) => ({
                  validator: (_, value) =>
                    !value || getFieldValue('newPassword') === value
                      ? Promise.resolve()
                      : Promise.reject(new Error('两次输入的新口令不一致')),
                }),
              ]}
            />
          </ProForm>
        </div>
      </div>
      <Footer />
    </div>
  );
};

export default ChangePassword;
