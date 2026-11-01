import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { LoginForm, ProFormText } from '@ant-design/pro-components';
import {
  FormattedMessage,
  Helmet,
  SelectLang,
  useIntl,
  useModel,
} from '@umijs/max';
import { Alert, App } from 'antd';
import { createStyles } from 'antd-style';
import React, { startTransition, useState } from 'react';
import { Footer } from '@/components';
import { platformLogin } from '@/services/pms/auth';
import { describeProblem, toProblem } from '@/services/pms/errors';
import { clearSession, saveSession } from '@/services/pms/session';
import Settings from '../../../../config/defaultSettings';

/**
 * 平台登录页（R-20261102-02）。
 *
 * 与 merchant-web 登录页的唯一结构性差异：**没有租户编码字段**。
 * 平台账号（platform_user）不在任何租户之下（ADR-019 §2.4），
 * 登录只需要「登录名 + 口令」，登录后身份由服务端会话携带。
 *
 * 登录失败文案与租户侧同一原则：后端统一话术，前端原样透传，
 * 不做二次解释——那等于帮别人做账号枚举。
 *
 * 文案遵守前端规范 §7：不写欢迎辞、不写产品自述、不写装饰性标语，
 * 只写字段标签与真实约束。
 */

/**
 * 校验回跳地址，防止开放重定向。
 * 只允许同源的相对路径。
 */
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
      style={{
        marginBottom: 24,
      }}
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
        setInitialState((s) => ({
          ...s,
          currentUser: userInfo,
        }));
      });
    }
    return userInfo;
  };

  const handleSubmit = async (values: LoginFormValues) => {
    setSubmitting(true);
    setErrorMessage('');
    try {
      const response = await platformLogin({
        username: values.username.trim(),
        password: values.password,
      });

      if (!response.token) {
        // 契约里 token 是可选的，但登录成功没有令牌意味着会话没建起来。
        setErrorMessage('登录响应缺少会话令牌，请重试');
        return;
      }

      // 会话令牌只在登录响应里出现一次，落本地后随请求携带。
      saveSession(response.token, response.expiresAt);

      const userInfo = await fetchUserInfo();
      if (!userInfo) {
        // 登录成功但拉不到身份：清掉刚存的令牌，别留下"半登录"状态。
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
      const redirectUrl = getSafeRedirectUrl(urlParams.get('redirect'));

      /*
       * 用种子口令登录的平台账号必须先改密（must_change_password，V15）。
       *
       * 服务端已经拦住所有业务接口，这里直接送到改密页，并把原目标路径带过去——
       * 否则人会登录成功后立刻看到一个空白且有报错的界面。
       */
      if (userInfo.mustChangePassword) {
        window.location.href = `/user/password?redirect=${encodeURIComponent(redirectUrl)}`;
        return;
      }

      window.location.href = redirectUrl;
    } catch (error) {
      // 后端对账号不存在、密码错、账号停用一律返回同一句提示，
      // 这里原样透传，不做二次解释——那等于帮别人做账号枚举。
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
          contentStyle={{
            minWidth: 280,
            maxWidth: '75vw',
          }}
          logo={<img alt="logo" src="/logo.svg" />}
          title={Settings.title}
          submitter={{
            searchConfig: {
              submitText: intl.formatMessage({
                id: 'pages.login.submit',
                defaultMessage: '登录',
              }),
            },
            submitButtonProps: {
              loading: submitting,
            },
          }}
          onFinish={async (values) => {
            await handleSubmit(values as LoginFormValues);
          }}
        >
          {errorMessage && <LoginMessage content={errorMessage} />}

          <ProFormText
            name="username"
            fieldProps={{
              size: 'large',
              prefix: <UserOutlined />,
            }}
            placeholder={intl.formatMessage({
              id: 'pages.login.username.placeholder',
              defaultMessage: '登录名',
            })}
            rules={[
              {
                required: true,
                message: (
                  <FormattedMessage
                    id="pages.login.username.required"
                    defaultMessage="请输入登录名"
                  />
                ),
              },
            ]}
          />

          <ProFormText.Password
            name="password"
            fieldProps={{
              size: 'large',
              prefix: <LockOutlined />,
            }}
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
        </LoginForm>
      </div>
      <Footer />
    </div>
  );
};

export default Login;
