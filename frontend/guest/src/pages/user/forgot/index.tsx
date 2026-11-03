import { LockOutlined, MailOutlined } from '@ant-design/icons';
import { ProForm, ProFormText } from '@ant-design/pro-components';
import { Helmet } from '@umijs/max';
import { Alert, App, Typography } from 'antd';
import { createStyles } from 'antd-style';
import React, { useState } from 'react';
import { Footer } from '@/components';
import {
  guestPasswordReset,
  guestPasswordResetChallenge,
} from '@/services/pms/auth';
import { describeProblem, toProblem } from '@/services/pms/errors';
import Settings from '../../../../config/defaultSettings';

/**
 * 忘记密码（R-20261103-01，匿名）。
 *
 * 两步式：输入邮箱 → 发送验证码（邮件正文会写明"重置登录口令"——
 * 若你收到的码对应的操作不是你发起的，不要告诉任何人）→
 * 输入验证码 + 新口令 → 重置成功 → 用新口令重新登录。
 *
 * 后端语义（页面如实呈现，不隐藏后果）：
 * · 重置成功会**清除全部已登录设备**的会话（包括正在发起重置的这台——
 *   匿名流程没有"当前会话"），之后需用新口令登录；
 * · 未注册邮箱也会收到验证码，但无法完成重置（统一文案，防探测）。
 */

/** 与后端 PasswordPolicy 一致：12 位下限。 */
const MIN_LENGTH = 12;

const EMAIL_PATTERN = /^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$/;

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

type ForgotValues = {
  email: string;
  code: string;
  newPassword: string;
  confirmPassword: string;
};

const Forgot: React.FC = () => {
  const { styles } = useStyles();
  const { message } = App.useApp();
  const [submitting, setSubmitting] = useState(false);
  const [sending, setSending] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string>('');
  // 两步态：未发码（填邮箱）→ 已发码（填验证码与新口令）
  const [challenge, setChallenge] = useState<
    { id: string; masked: string } | undefined
  >(undefined);
  const [emailInput, setEmailInput] = useState('');
  const [done, setDone] = useState(false);

  /** 第一步：向邮箱发重置验证码。 */
  const sendCode = async () => {
    const email = emailInput.trim().toLowerCase();
    if (!EMAIL_PATTERN.test(email)) {
      setErrorMessage('请先输入正确的邮箱地址');
      return false;
    }
    setSending(true);
    setErrorMessage('');
    try {
      const issued = await guestPasswordResetChallenge({ email });
      setChallenge({ id: issued.challengeId ?? '', masked: issued.subjectMasked ?? email });
      message.success(`验证码已发送至 ${issued.subjectMasked ?? email}`);
    } catch (error) {
      const problem = toProblem(error);
      setErrorMessage(describeProblem(problem));
    } finally {
      setSending(false);
    }
    return false;
  };

  /** 第二步：验证码 + 新口令完成重置。 */
  const handleFinish = async (values: ForgotValues) => {
    if (!challenge) {
      setEmailInput(values.email ?? '');
      await sendCode();
      return false;
    }
    setSubmitting(true);
    setErrorMessage('');
    try {
      await guestPasswordReset({
        challengeId: challenge.id,
        code: values.code.trim(),
        newPassword: values.newPassword,
      });
      setDone(true);
      message.success('口令已重置，请用新口令登录');
    } catch (error) {
      const problem = toProblem(error);
      // 409：挑战失效（过期/超次/已消费）——退回第一步重新发码
      if (problem?.status === 409) {
        setChallenge(undefined);
        setErrorMessage('验证码已失效，请重新获取');
        return false;
      }
      setErrorMessage(describeProblem(problem));
    } finally {
      setSubmitting(false);
    }
    return true;
  };

  return (
    <div className={styles.container}>
      <Helmet>
        <title>忘记密码{Settings.title && ` - ${Settings.title}`}</title>
      </Helmet>
      <div className={styles.body}>
        <div className={styles.card}>
          <Typography.Title level={4} style={{ marginTop: 0 }}>
            忘记密码
          </Typography.Title>

          {done ? (
            <>
              <Alert
                type="success"
                showIcon
                style={{ marginBottom: 20 }}
                title="口令已重置"
                description="所有已登录设备的会话已被清除。请用新口令重新登录。"
              />
              <Typography.Paragraph style={{ textAlign: 'center' }}>
                <a
                  onClick={() => {
                    window.location.href = '/user/login';
                  }}
                >
                  去登录
                </a>
              </Typography.Paragraph>
            </>
          ) : (
            <>
              <Typography.Paragraph
                type="secondary"
                style={{ marginBottom: 20 }}
              >
                输入账户绑定的邮箱，我们将发送验证码用于重置口令。
              </Typography.Paragraph>

              {errorMessage && (
                <Alert
                  type="error"
                  showIcon
                  style={{ marginBottom: 20 }}
                  title={errorMessage}
                />
              )}

              <ProForm<ForgotValues>
                layout="vertical"
                submitter={{
                  searchConfig: {
                    submitText: challenge ? '重置口令' : '获取验证码',
                  },
                  submitButtonProps: { loading: submitting || sending, block: true },
                }}
                onFinish={handleFinish}
              >
                {!challenge && (
                  <ProFormText
                    name="email"
                    label="邮箱地址"
                    placeholder="someone@example.com"
                    fieldProps={{
                      size: 'large',
                      prefix: <MailOutlined />,
                      onChange: (e: { target: { value: string } }) => {
                        setEmailInput(e.target.value);
                      },
                    }}
                    rules={[
                      { required: true, message: '请输入邮箱地址' },
                      { pattern: EMAIL_PATTERN, message: '邮箱格式不合法' },
                    ]}
                  />
                )}

                {challenge && (
                  <>
                    <Alert
                      type="success"
                      showIcon
                      style={{ marginBottom: 16 }}
                      title={`验证码已发送至 ${challenge.masked}`}
                      description="邮件会写明操作类型为「重置登录口令」。10 分钟内有效，5 次错误尝试后作废。"
                    />
                    <ProFormText
                      name="code"
                      label="验证码"
                      placeholder="6 位数字"
                      fieldProps={{ size: 'large', maxLength: 6 }}
                      rules={[
                        { required: true, message: '请输入验证码' },
                        { pattern: /^\d{6}$/, message: '验证码为 6 位数字' },
                      ]}
                      extra={
                        <a
                          onClick={() => {
                            setChallenge(undefined);
                          }}
                        >
                          换一个邮箱
                        </a>
                      }
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
                            return Promise.reject(
                              new Error('两次输入的新口令不一致'),
                            );
                          },
                        }),
                      ]}
                    />
                  </>
                )}
              </ProForm>

              <Typography.Paragraph
                type="secondary"
                style={{ marginTop: 16, marginBottom: 0, textAlign: 'center' }}
              >
                想起密码了？{' '}
                <a
                  onClick={() => {
                    window.location.href = '/user/login';
                  }}
                >
                  返回登录
                </a>
              </Typography.Paragraph>
            </>
          )}
        </div>
      </div>
      <Footer />
    </div>
  );
};

export default Forgot;
