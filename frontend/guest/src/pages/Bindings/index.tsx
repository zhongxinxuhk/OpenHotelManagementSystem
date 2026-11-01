/**
 * 登录方式管理（R-20261102-05，真实接口——凭证绑定闭环）。
 *
 * ADR-019 §2.10 的住客侧工作台，三种操作：
 * 1. **绑定用户名**：密码即归属证明，无需验证码；
 * 2. **绑定邮箱**：挑战-响应——向目标邮箱发 6 位验证码（EMAIL 通道已接通，
 *    R-20261102-01），输入正确的码完成绑定。通道未开启时后端 503，
 *    页面如实提示「验证通道未接通」，不静默、不假装已发送；
 * 3. **解绑**：不能解绑最后一个凭证（后端 409：解绑完无法登录，
 *    住客没有平台帮恢复的路）。
 *
 * 防爆破三件套（码哈希存储、5 次尝试上限、10 分钟过期）由后端保证；
 * 页面对「验证码不正确」如实计数提示。
 */
import { PageContainer, ProCard } from '@ant-design/pro-components';
import { ModalForm, ProFormText } from '@ant-design/pro-components';
import {
  Alert,
  App,
  Button,
  Empty,
  List,
  Popconfirm,
  Space,
  Tag,
  Typography,
} from 'antd';
import React, { useCallback, useEffect, useState } from 'react';
import {
  guestBindingChallenge,
  guestBindingConfirm,
  guestBindUsername,
  guestUnbind,
  listGuestBindings,
  type ChallengeView,
  type GuestCredentialView,
} from '@/services/pms/guest';
import { describeProblem, toProblem } from '@/services/pms/errors';

const Bindings: React.FC = () => {
  const { message } = App.useApp();
  const [bindings, setBindings] = useState<GuestCredentialView[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);
  const [usernameOpen, setUsernameOpen] = useState(false);
  const [emailOpen, setEmailOpen] = useState(false);
  /** 进行中的邮箱挑战（challenge 二段式：先发起后确认）。 */
  const [emailChallenge, setEmailChallenge] = useState<
    ChallengeView | undefined
  >(undefined);

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      setBindings(await listGuestBindings());
    } catch (e) {
      setError(describeProblem(toProblem(e)));
      setBindings([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  /** 绑定用户名（密码即归属证明）。 */
  const submitUsername = async (values: {
    username: string;
    password: string;
  }) => {
    try {
      setBindings(
        await guestBindUsername({
          username: values.username.trim().toLowerCase(),
          password: values.password,
        }),
      );
      message.success(`用户名 ${values.username.trim().toLowerCase()} 已绑定`);
      setUsernameOpen(false);
      return true;
    } catch (e) {
      message.error(describeProblem(toProblem(e)));
      return false;
    }
  };

  /** 第一步：向目标邮箱发起验证码挑战。 */
  const startEmailChallenge = async (values: { email: string }) => {
    const email = values.email.trim().toLowerCase();
    try {
      const challenge = await guestBindingChallenge({
        provider: 'EMAIL',
        subject: email,
      });
      setEmailChallenge(challenge);
      message.success(`验证码已发送至 ${challenge.subjectMasked ?? email}`);
      return false; // 不关弹窗：进入第二步（输入验证码）
    } catch (e) {
      // 503：验证通道未接通（mail.enabled=false）；409：邮箱已被其它账户绑定。
      message.error(describeProblem(toProblem(e)));
      return false;
    }
  };

  /** 第二步：提交验证码完成绑定。 */
  const submitEmailCode = async (values: { code: string }) => {
    if (!emailChallenge?.challengeId) return false;
    try {
      setBindings(
        await guestBindingConfirm({
          challengeId: emailChallenge.challengeId,
          code: values.code.trim(),
        }),
      );
      message.success('邮箱已绑定');
      setEmailOpen(false);
      setEmailChallenge(undefined);
      return true;
    } catch (e) {
      message.error(describeProblem(toProblem(e)));
      return false;
    }
  };

  /** 解绑（最后一个会被后端 409 拒绝）。 */
  const unbind = async (record: GuestCredentialView) => {
    try {
      setBindings(
        await guestUnbind({
          provider: record.provider ?? '',
          subject: record.subject ?? '',
        }),
      );
      message.success(`${record.provider} 凭证已解绑`);
    } catch (e) {
      message.error(describeProblem(toProblem(e)));
    }
  };

  const onlyOne = bindings.length <= 1;

  return (
    <PageContainer
      header={{
        title: '登录方式',
      }}
    >
      {error && (
        <Alert
          type="error"
          showIcon
          title={error}
          style={{ marginBottom: 16 }}
          action={
            <a
              onClick={() => {
                void load();
              }}
            >
              重试
            </a>
          }
        />
      )}
      <ProCard
        title="已绑定的登录方式"
        extra={
          <Space>
            <Button
              onClick={() => {
                setEmailChallenge(undefined);
                setEmailOpen(true);
              }}
            >
              绑定邮箱
            </Button>
            <Button
              onClick={() => {
                setUsernameOpen(true);
              }}
            >
              绑定用户名
            </Button>
          </Space>
        }
      >
        {loading ? (
          <Empty description="加载中…" />
        ) : bindings.length === 0 ? (
          <Empty description="暂无绑定凭证" />
        ) : (
          <List
            dataSource={bindings}
            rowKey={(item) => `${item.provider}:${item.subject}`}
            renderItem={(item) => (
              <List.Item
                actions={
                  onlyOne ? (
                    [
                      <Typography.Text
                        key="last"
                        type="secondary"
                        title="解绑最后一个凭证会导致无法登录（住客无平台恢复通道）"
                      >
                        须保留至少一个
                      </Typography.Text>,
                    ]
                  ) : (
                    [
                      <Popconfirm
                        key="unbind"
                        title={`解绑 ${item.provider}？`}
                        description={`解绑后将无法用 ${item.subject} 登录。`}
                        okText="解绑"
                        cancelText="取消"
                        okButtonProps={{ danger: true }}
                        onConfirm={() => unbind(item)}
                      >
                        <a>解绑</a>
                      </Popconfirm>,
                    ]
                  )
                }
              >
                <List.Item.Meta
                  title={
                    <Space>
                      <Tag color="blue">{item.provider}</Tag>
                      <Typography.Text copyable>
                        {item.subject}
                      </Typography.Text>
                    </Space>
                  }
                  description={
                    item.verifiedAt
                      ? `验证于 ${new Date(item.verifiedAt).toLocaleString()}`
                      : undefined
                  }
                />
              </List.Item>
            )}
          />
        )}
      </ProCard>
      <Typography.Paragraph type="secondary" style={{ marginTop: 16 }}>
        绑定邮箱需要接收 6 位验证码（10 分钟内有效，5 次尝试上限）；
        不能解绑最后一个登录方式。
      </Typography.Paragraph>

      {/* 绑定用户名 */}
      <ModalForm<{ username: string; password: string }>
        title="绑定用户名"
        width={440}
        open={usernameOpen}
        modalProps={{ destroyOnHidden: true }}
        onFinish={submitUsername}
        onOpenChange={setUsernameOpen}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          title="输入当前口令完成归属验证"
          description="用户名+口令是一对凭证：绑定新用户名需要证明你是账户主人。"
        />
        <ProFormText
          name="username"
          label="新用户名"
          placeholder="3-64 位小写字母/数字，可含 . _ -"
          rules={[
            { required: true, message: '请输入用户名' },
            {
              pattern: /^[a-z0-9][a-z0-9._-]{2,63}$/,
              message: '用户名格式不合法',
            },
          ]}
        />
        <ProFormText.Password
          name="password"
          label="当前口令"
          rules={[{ required: true, message: '请输入当前口令' }]}
        />
      </ModalForm>

      {/* 绑定邮箱：挑战-确认两步 */}
      <ModalForm<{ email: string; code: string }>
        title={emailChallenge ? '输入验证码' : '绑定邮箱'}
        width={440}
        open={emailOpen}
        modalProps={{ destroyOnHidden: true }}
        onFinish={emailChallenge ? submitEmailCode : startEmailChallenge}
        onOpenChange={(open) => {
          if (!open) {
            setEmailOpen(false);
            setEmailChallenge(undefined);
          } else {
            setEmailOpen(true);
          }
        }}
      >
        {!emailChallenge && (
          <ProFormText
            name="email"
            label="邮箱地址"
            placeholder="someone@example.com"
            rules={[
              { required: true, message: '请输入邮箱地址' },
              {
                pattern: /^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$/,
                message: '邮箱格式不合法',
              },
            ]}
          />
        )}
        {emailChallenge && (
          <>
            <Alert
              type="success"
              showIcon
              style={{ marginBottom: 16 }}
              title={`验证码已发送至 ${emailChallenge.subjectMasked ?? ''}`}
              description={`10 分钟内有效（至 ${new Date(
                emailChallenge.expiresAt ?? '',
              ).toLocaleTimeString()}）。`}
            />
            <ProFormText
              name="code"
              label="验证码"
              placeholder="6 位数字"
              fieldProps={{ maxLength: 6, size: 'large' }}
              rules={[
                { required: true, message: '请输入验证码' },
                {
                  pattern: /^\d{6}$/,
                  message: '验证码为 6 位数字',
                },
              ]}
            />
          </>
        )}
      </ModalForm>
    </PageContainer>
  );
};

export default Bindings;
