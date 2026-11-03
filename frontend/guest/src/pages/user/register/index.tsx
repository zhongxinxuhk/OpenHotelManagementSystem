import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { LoginForm, ProFormText } from '@ant-design/pro-components';
import { Helmet, useModel } from '@umijs/max';
import { Link } from '@umijs/max';
import { Alert, App } from 'antd';
import { createStyles } from 'antd-style';
import React, { startTransition, useState } from 'react';
import { Footer } from '@/components';
import { guestRegister } from '@/services/pms/auth';
import { describeProblem, toProblem } from '@/services/pms/errors';
import { clearSession, saveSession } from '@/services/pms/session';
import Settings from '../../../../config/defaultSettings';

/**
 * 住客注册页（R-20261102-05）。
 *
 * 注册即登录（ADR-019 §2.9）：用户名+口令注册后服务端直接发会话——
 * "知道口令"就是归属证明，不需要再走邮箱验证。
 * 用户名规则与后端一致：3-64 位小写字母/数字，可含 . _ -（提交时小写化）。
 *
 * 手机号/邮箱注册（验证码通道）未开放——EMAIL 通道已接通（R-20261102-01），
 * 消费侧入口下一轮建；这里不画不可用的按钮。
 */
const useStyles = createStyles(({ token }) => {
  return {
    lang: {
      width: 42,
      height: 42,
      lineHeight: '42px',
      position: 'fixed',
      right: 16,
      borderRadius: token.borderRadius,
      ':hover': {
        backgroundColor: token.colorBgTextHover,
      },
    },
    container: {
      display: 'flex',
      flexDirection: 'column',
      height: '100vh',
      overflow: 'auto',
      backgroundColor: token.colorBgLayout,
    },
  };
});

type RegisterFormValues = {
  username: string;
  password: string;
  displayName?: string;
};

const Register: React.FC = () => {
  const [errorMessage, setErrorMessage] = useState<string>('');
  const [submitting, setSubmitting] = useState(false);
  const { initialState, setInitialState } = useModel('@@initialState');
  const { styles } = useStyles();
  const { message } = App.useApp();

  const fetchUserInfo = async () => {
    const userInfo = await initialState?.fetchUserInfo?.();
    if (userInfo) {
      startTransition(() => {
        setInitialState((s) => ({ ...s, currentUser: userInfo }));
      });
    }
    return userInfo;
  };

  const handleSubmit = async (values: RegisterFormValues) => {
    setSubmitting(true);
    setErrorMessage('');
    try {
      const response = await guestRegister({
        username: values.username.trim().toLowerCase(),
        password: values.password,
        displayName: values.displayName?.trim() || undefined,
      });

      if (!response.token) {
        setErrorMessage('注册成功但未返回会话令牌，请用登录入口进入');
        return;
      }

      // 注册即登录：令牌落本地，直接进入。
      saveSession(response.token, response.expiresAt);
      await fetchUserInfo();
      message.success('注册成功');
      window.location.href = '/home';
    } catch (error) {
      // 用户名已占用（409）等由后端给出具体文案；这里原样透传。
      const problem = toProblem(error);
      setErrorMessage(describeProblem(problem));
      clearSession();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className={styles.container}>
      <Helmet>
        <title>注册{Settings.title && ` - ${Settings.title}`}</title>
      </Helmet>
      <div
        style={{
          flex: '1',
          padding: '32px 0',
          overflow: 'auto',
        }}
      >
        <LoginForm
          contentStyle={{ minWidth: 280, maxWidth: '75vw' }}
          logo={<img alt="logo" src="/logo.svg" />}
          title={Settings.title}
          submitter={{
            searchConfig: { submitText: '注册' },
            submitButtonProps: { loading: submitting },
          }}
          onFinish={async (values) => {
            await handleSubmit(values as RegisterFormValues);
          }}
        >
          {errorMessage && (
            <Alert
              style={{ marginBottom: 24 }}
              title={errorMessage}
              type="error"
              showIcon
            />
          )}

          <ProFormText
            name="username"
            fieldProps={{ size: 'large', prefix: <UserOutlined /> }}
            placeholder="用户名（3-64 位小写字母/数字，可含 . _ -）"
            rules={[
              { required: true, message: '请输入用户名' },
              {
                pattern: /^[a-z0-9][a-z0-9._-]{2,63}$/,
                message:
                  '用户名须为 3-64 位小写字母/数字，可含 . _ -，以字母或数字开头',
              },
            ]}
          />

          <ProFormText
            name="displayName"
            fieldProps={{ size: 'large' }}
            placeholder="显示名（可选）"
          />

          <ProFormText.Password
            name="password"
            fieldProps={{ size: 'large', prefix: <LockOutlined /> }}
            placeholder="口令（至少 12 位）"
            rules={[
              { required: true, message: '请输入口令' },
              { min: 12, message: '口令至少 12 位' },
            ]}
          />

          <div style={{ marginBottom: 8, textAlign: 'right' }}>
            <span style={{ marginRight: 8 }}>已有账号？</span>
            <Link to="/user/login">登录</Link>
          </div>
        </LoginForm>
      </div>
      <Footer />
    </div>
  );
};

export default Register;
