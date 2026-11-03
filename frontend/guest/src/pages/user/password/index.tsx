import { LockOutlined } from '@ant-design/icons';
import { ProForm, ProFormText } from '@ant-design/pro-components';
import { Helmet, history, useModel } from '@umijs/max';
import { Alert, App, Typography } from 'antd';
import { createStyles } from 'antd-style';
import React, { useState } from 'react';
import { Footer } from '@/components';
import { guestChangePassword } from '@/services/pms/auth';
import { describeProblem, toProblem } from '@/services/pms/errors';
import Settings from '../../../../config/defaultSettings';

/**
 * 住客修改口令（R-20261103-01 恢复此页并接真实接口）。
 *
 * R-05 删掉了旧页（当时 GUEST 面没有改密接口）；本轮接口落地，页面恢复——
 * 同时修复了头像菜单「修改口令」指向不存在路由的潜伏 404。
 *
 * 语义与员工/平台一致：校验当前口令（会话可能是被盗的 cookie，口令是第二因子）；
 * 成功后撤销**其它**会话、保留当前。GUEST 无强制首改机制（无 must_change_password），
 * 所以这里没有"forced"分支。
 *
 * `layout: false`：不进主框架——改密是低频动作，独立页足够且更聚焦。
 */

/** 与后端 PasswordPolicy 一致：12 位下限。改这里必须同步改后端。 */
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

type PasswordFormValues = {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
};

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

const ChangePassword: React.FC = () => {
  const { styles } = useStyles();
  const { message } = App.useApp();
  const { initialState } = useModel('@@initialState');
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string>('');

  const redirectUrl = safeRedirect(
    new URL(window.location.href).searchParams.get('redirect'),
  );

  const handleFinish = async (values: PasswordFormValues) => {
    setSubmitting(true);
    setErrorMessage('');
    try {
      await guestChangePassword({
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      });

      message.success('口令已更新');
      // 后端会撤销该账号的**其他**会话，当前会话保留；
      // 重新拉一次身份，让界面状态与新事实一致。
      await initialState?.fetchUserInfo?.();
      window.location.href = redirectUrl;
    } catch (error) {
      // 后端对"当前口令不正确"统一返回同一句话，前端原样透传。
      setErrorMessage(describeProblem(toProblem(error)));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className={styles.container}>
      <Helmet>
        <title>修改口令{Settings.title && ` - ${Settings.title}`}</title>
      </Helmet>
      <div className={styles.body}>
        <div className={styles.card}>
          <Typography.Title level={4} style={{ marginTop: 0 }}>
            修改口令
          </Typography.Title>

          <Typography.Paragraph type="secondary" style={{ marginBottom: 20 }}>
            改完之后，你在其他设备上的登录会话会被清除，需要重新登录；当前设备不受影响。
          </Typography.Paragraph>

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
              render: (_props, dom) => [
                ...(Array.isArray(dom) ? dom : [dom]),
                <Typography.Text
                  key="back"
                  type="secondary"
                  style={{ display: 'block', textAlign: 'center', marginTop: 12 }}
                >
                  <a
                    onClick={() => {
                      history.push('/me');
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
              placeholder={`至少 ${MIN_LENGTH} 位，且不要包含用户名`}
              fieldProps={{ size: 'large', prefix: <LockOutlined /> }}
              rules={[
                { required: true, message: '请输入新口令' },
                { min: MIN_LENGTH, message: `新口令至少 ${MIN_LENGTH} 位` },
              ]}
            />

            <ProFormText.Password
              name="confirmPassword"
              label="确认新口令"
              fieldProps={{ size: 'large', prefix: <LockOutlined /> }}
              rules={[
                { required: true, message: '请再次输入新口令' },
                ({ getFieldValue }) => ({
                  validator: (_rule, value) => {
                    if (!value || getFieldValue('newPassword') === value) {
                      return Promise.resolve();
                    }
                    return Promise.reject(new Error('两次输入的新口令不一致'));
                  },
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
