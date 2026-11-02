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
import { Alert, App, Radio, Space } from 'antd';
import { createStyles } from 'antd-style';
import React, { startTransition, useEffect, useState } from 'react';
import { Footer } from '@/components';
import {
  guestCodeLogin,
  guestCodeLoginChallenge,
  guestLogin,
} from '@/services/pms/auth';
import { listGuestLoginMethods } from '@/services/pms/guest';
import { describeProblem, toProblem } from '@/services/pms/errors';
import { clearSession, saveSession } from '@/services/pms/session';
import Settings from '../../../../config/defaultSettings';

/**
 * 住客登录页（R-20261102-05 建，R-20261102-07 增邮箱验证码登录）。
 *
 * 两种登录方式：
 * 1. **用户名 + 口令**（常驻）；
 * 2. **邮箱 + 验证码**（R-20261102-07）：仅当后端方式清单 `available=true`
 *    （实现 ∧ 控制台开关）时渲染入口——未开放时不出现"点了才告诉你不行"
 *    的按钮。两步：输入邮箱 → 获取验证码（真实 SMTP 投递）→ 输入 6 位码登录。
 *
 * 枚举防护（后端口径，前端配合呈现）：未注册邮箱也照发验证码；
 * 提交码失败统一文案"验证码或邮箱不正确"，前端原样透传不二次解释。
 *
 * 文案遵守前端规范 §7：面向消费者不写欢迎辞、不写营销语。
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

const EMAIL_PATTERN = /^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$/;

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
    methodSwitch: {
      marginBottom: 16,
      justifyContent: 'center',
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

type UsernameFormValues = {
  username: string;
  password: string;
};

type EmailFormValues = {
  email: string;
  code: string;
};

const Login: React.FC = () => {
  const [errorMessage, setErrorMessage] = useState<string>('');
  const [submitting, setSubmitting] = useState(false);
  const { initialState, setInitialState } = useModel('@@initialState');
  const { styles } = useStyles();
  const { message } = App.useApp();
  const intl = useIntl();

  // 登录方式：username（常驻）/ email（按后端清单可用性渲染）
  const [method, setMethod] = useState<'username' | 'email'>('username');
  const [emailLoginAvailable, setEmailLoginAvailable] = useState(false);
  const [emailInput, setEmailInput] = useState('');
  // 邮箱登录两步态：未发码（填邮箱）→ 已发码（填验证码）
  const [emailChallenge, setEmailChallenge] = useState<
    string | undefined
  >(undefined);
  const [emailMasked, setEmailMasked] = useState<string>('');
  const [sending, setSending] = useState(false);

  useEffect(() => {
    // 方式清单：available = 实现 ∧ 控制台开关（ADR-019 §2.9）。
    // 拉取失败时只保留用户名登录（不影响既有路径）。
    void listGuestLoginMethods()
      .then((methods) => {
        const email = methods.find((m) => m.provider === 'EMAIL');
        setEmailLoginAvailable(email?.available ?? false);
      })
      .catch(() => {
        setEmailLoginAvailable(false);
      });
  }, []);

  const fetchUserInfo = async () => {
    const userInfo = await initialState?.fetchUserInfo?.();
    if (userInfo) {
      startTransition(() => {
        setInitialState((s) => ({ ...s, currentUser: userInfo }));
      });
    }
    return userInfo;
  };

  const redirectAfterLogin = () => {
    const urlParams = new URL(window.location.href).searchParams;
    return getSafeRedirectUrl(urlParams.get('redirect'));
  };

  // ==================== 用户名 + 口令 ====================

  const handleUsernameSubmit = async (values: UsernameFormValues) => {
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
      window.location.href = redirectAfterLogin();
    } catch (error) {
      // 后端统一文案（防账号枚举），前端原样透传。
      const problem = toProblem(error);
      setErrorMessage(describeProblem(problem));
      clearSession();
    } finally {
      setSubmitting(false);
    }
  };

  // ==================== 邮箱 + 验证码 ====================

  /** 第一步：向邮箱发验证码。未注册邮箱也照发（后端枚举防护口径）。 */
  const sendCode = async () => {
    const email = emailInput.trim().toLowerCase();
    if (!EMAIL_PATTERN.test(email)) {
      setErrorMessage('请先输入正确的邮箱地址');
      return;
    }
    setSending(true);
    setErrorMessage('');
    try {
      const challenge = await guestCodeLoginChallenge({ email });
      setEmailChallenge(challenge.challengeId);
      setEmailMasked(challenge.subjectMasked ?? email);
      message.success(`验证码已发送至 ${challenge.subjectMasked ?? email}`);
    } catch (error) {
      const problem = toProblem(error);
      setErrorMessage(describeProblem(problem));
    } finally {
      setSending(false);
    }
  };

  /** 第二步：提交验证码完成登录。 */
  const handleEmailSubmit = async (values: EmailFormValues) => {
    if (!emailChallenge) {
      // 未发码：把当前邮箱当作发码请求处理（表单提交的备选路径）
      setEmailInput(values.email ?? '');
      await sendCode();
      return;
    }
    setSubmitting(true);
    setErrorMessage('');
    try {
      const response = await guestCodeLogin({
        challengeId: emailChallenge,
        code: values.code.trim(),
      });

      if (!response.token) {
        setErrorMessage('登录响应缺少会话令牌，请重试');
        return;
      }

      saveSession(response.token, response.expiresAt);
      const userInfo = await fetchUserInfo();
      if (!userInfo) {
        clearSession();
        setErrorMessage('会话建立失败，请重试');
        return;
      }

      message.success('登录成功');
      window.location.href = redirectAfterLogin();
    } catch (error) {
      const problem = toProblem(error);
      // 409：挑战已失效（过期/超次/已消费）——退回第一步重新发码
      if (problem?.status === 409) {
        setEmailChallenge(undefined);
        setErrorMessage('验证码已失效，请重新获取');
        return;
      }
      // 401 统一文案（防邮箱枚举），原样透传。
      setErrorMessage(describeProblem(problem));
    } finally {
      setSubmitting(false);
    }
  };

  const switchMethod = (next: 'username' | 'email') => {
    setMethod(next);
    setErrorMessage('');
    setEmailChallenge(undefined);
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
              submitText:
                method === 'email' && !emailChallenge
                  ? '获取验证码'
                  : intl.formatMessage({
                      id: 'pages.login.submit',
                      defaultMessage: '登录',
                    }),
            },
            submitButtonProps: { loading: submitting || sending },
          }}
          onFinish={async (values) => {
            if (method === 'email') {
              await handleEmailSubmit(values as EmailFormValues);
              return;
            }
            await handleUsernameSubmit(values as UsernameFormValues);
          }}
        >
          {errorMessage && <LoginMessage content={errorMessage} />}

          {/* 方式切换：邮箱验证码登录只在后端开放（available）时出现 */}
          {emailLoginAvailable && (
            <Space className={styles.methodSwitch} style={{ width: '100%' }}>
              <Radio.Group
                value={method}
                optionType="button"
                buttonStyle="solid"
                onChange={(e) => {
                  switchMethod(e.target.value);
                }}
                options={[
                  { value: 'username', label: '用户名登录' },
                  { value: 'email', label: '邮箱验证码登录' },
                ]}
              />
            </Space>
          )}

          {method === 'username' && (
            <>
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
            </>
          )}

          {method === 'email' && !emailChallenge && (
            <ProFormText
              name="email"
              fieldProps={{
                size: 'large',
                prefix: <UserOutlined />,
                onChange: (e: { target: { value: string } }) => {
                  setEmailInput(e.target.value);
                },
              }}
              placeholder="邮箱地址"
              rules={[
                { required: true, message: '请输入邮箱地址' },
                { pattern: EMAIL_PATTERN, message: '邮箱格式不合法' },
              ]}
            />
          )}

          {method === 'email' && emailChallenge && (
            <>
              <Alert
                type="success"
                showIcon
                style={{ marginBottom: 16 }}
                title={`验证码已发送至 ${emailMasked}`}
                description="10 分钟内有效，5 次错误尝试后作废。"
              />
              <ProFormText
                name="code"
                fieldProps={{ size: 'large', maxLength: 6 }}
                placeholder="6 位验证码"
                rules={[
                  { required: true, message: '请输入验证码' },
                  { pattern: /^\d{6}$/, message: '验证码为 6 位数字' },
                ]}
                extra={
                  <a
                    onClick={() => {
                      setEmailChallenge(undefined);
                    }}
                  >
                    换一个邮箱
                  </a>
                }
              />
            </>
          )}

          {method === 'username' && (
            <div style={{ marginBottom: 8, textAlign: 'right' }}>
              <span style={{ marginRight: 8 }}>没有账号？</span>
              <Link to="/user/register">注册</Link>
            </div>
          )}
        </LoginForm>
      </div>
      <Footer />
    </div>
  );
};

export default Login;
