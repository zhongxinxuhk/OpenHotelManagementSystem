import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { LoginForm, ProFormText } from '@ant-design/pro-components';
import {
  FormattedMessage,
  Helmet,
  SelectLang,
  useIntl,
  useModel,
} from '@umijs/max';
import { Link } from '@umijs/max';
import { Alert, App } from 'antd';
import { createStyles } from 'antd-style';
import React, { startTransition, useState } from 'react';
import { Footer } from '@/components';
import { guestLogin } from '@/services/pms/auth';
import { describeProblem, toProblem } from '@/services/pms/errors';
import { clearSession, saveSession } from '@/services/pms/session';
import Settings from '../../../../config/defaultSettings';

/**
 * 住客登录页（R-20261102-05）。
 *
 * 当前只有**用户名 + 口令**一种方式（后端 `isImplemented()` 只标 USERNAME）。
 * EMAIL 验证码登录是下一轮：通道已接通（R-20261102-01），消费侧入口待建——
 * 不提前画一个"发验证码"按钮：点了只会得到 503，那是把未完成当功能卖。
 *
 * 登录失败文案由后端统一（防账号枚举），前端原样透传。
 * 文案遵守前端规范 §7：面向消费者尤其容易写跑——不写欢迎辞、不写营销语。
 */

/** 校验回跳地址，防止开放重定向。只允许同源的相对路径。 */
const getSafeRedirectUrl = (redirect: string | null): string => {
  if (!redirect?.startsWith('/')) return '/';
  if (redirect.startsWith('//')) return '/';
  try {
    const parsed = new URL(redirect, window.location.origin);
    if (parsed.origin !== window.location.origin) return '/';
    return `${parsed.pathname}${parsed.search}${parsed.hash}`;
  } catch {
    return '/';
  }
};

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

const Lang = () => {
  const { styles } = useStyles();
  return (
    <div className={styles.lang} data-lang>
      {SelectLang && <SelectLang />}
    </div>
  );
};

const LoginMessage: React.FC<{ content: string }> = ({ content }) => {
  return (
    <Alert
      style={{ marginBottom: 24 }}
      title={content}
      type="error"
      showIcon
    />
  );
};

type LoginFormValues = {
  username: string;
  password: string;
};

const Login: React.FC = () => {
  const [errorMessage, setErrorMessage] = useState<string>('');
  const [submitting, setSubmitting] = useState(false);
  const { initialState, setInitialState } = useModel('@@initialState');
  const { styles } = useStyles();
  const { message } = App.useApp();
  const intl = useIntl();

  const fetchUserInfo = async () => {
    const userInfo = await initialState?.fetchUserInfo?.();
    if (userInfo) {
      startTransition(() => {
        setInitialState((s) => ({ ...s, currentUser: userInfo }));
      });
    }
    return userInfo;
  };

  const handleSubmit = async (values: LoginFormValues) => {
    setSubmitting(true);
    setErrorMessage('');
    try {
      const response = await guestLogin({
        username: values.username.trim(),
        password: values.password,
      });

      if (!response.token) {
        setErrorMessage('登录响应缺少会话令牌，请重试');
        return;
      }

      // 会话令牌只在登录响应里出现一次，落本地后随请求携带。
      saveSession(response.token, response.expiresAt);

      const userInfo = await fetchUserInfo();
      if (!userInfo) {
        clearSession();
        setErrorMessage('会话建立失败，请重试');
        return;
      }

      message.success(
        intl.formatMessage({
          id: 'pages.login.success',
          defaultMessage: '登录成功',
        }),
      );

      const urlParams = new URL(window.location.href).searchParams;
      window.location.href = getSafeRedirectUrl(urlParams.get('redirect'));
    } catch (error) {
      // 后端对用户名不存在、密码错、账户关闭统一返回同一句提示，
      // 前端原样透传，不做二次解释——那等于帮别人做账号枚举。
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
        <title>
          {intl.formatMessage({
            id: 'menu.login',
            defaultMessage: '登录',
          })}
          {Settings.title && ` - ${Settings.title}`}
        </title>
      </Helmet>
      <Lang />
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
            searchConfig: {
              submitText: intl.formatMessage({
                id: 'pages.login.submit',
                defaultMessage: '登录',
              }),
            },
            submitButtonProps: { loading: submitting },
          }}
          onFinish={async (values) => {
            await handleSubmit(values as LoginFormValues);
          }}
        >
          {errorMessage && <LoginMessage content={errorMessage} />}

          <ProFormText
            name="username"
            fieldProps={{ size: 'large', prefix: <UserOutlined /> }}
            placeholder={intl.formatMessage({
              id: 'pages.login.username.placeholder',
              defaultMessage: '用户名',
            })}
            rules={[
              {
                required: true,
                message: (
                  <FormattedMessage
                    id="pages.login.username.required"
                    defaultMessage="请输入用户名"
                  />
                ),
              },
            ]}
          />

          <ProFormText.Password
            name="password"
            fieldProps={{ size: 'large', prefix: <LockOutlined /> }}
            placeholder={intl.formatMessage({
              id: 'pages.login.password.placeholder',
              defaultMessage: '口令',
            })}
            rules={[
              {
                required: true,
                message: (
                  <FormattedMessage
                    id="pages.login.password.required"
                    defaultMessage="请输入口令"
                  />
                ),
              },
            ]}
          />

          <div style={{ marginBottom: 8, textAlign: 'right' }}>
            <span style={{ marginRight: 8 }}>没有账号？</span>
            <Link to="/user/register">注册</Link>
          </div>
        </LoginForm>
      </div>
      <Footer />
    </div>
  );
};

export default Login;
