/**
 * 员工管理（S3-02）。
 *
 * 内容：员工分页列表、开通账号、详情（角色 + 门店范围）、启停账号、
 * 编辑角色、编辑门店范围。
 *
 * 与安全直接相关的交互，都必须在界面上说清后果：
 * 1. **初始口令只显示一次**——服务端只存哈希，事后取不回；丢了只能重设。
 * 2. **新账号必须先改密**——员工首次登录只能改口令，管理员手上那份副本随之作废。
 * 3. **不能停用自己**——后端会拒，界面直接把按钮禁掉并给原因，
 *    而不是让人点了才看到报错。
 * 4. **停用会立即踢下线**——已登录设备在下一次请求就失效。
 * 5. **角色与门店范围都是全量替换**——勾掉的会被收回，不是"只增不减"。
 */

import type { ActionType, ProColumns } from '@ant-design/pro-components';
import {
  ModalForm,
  PageContainer,
  ProDescriptions,
  ProFormCheckbox,
  ProFormText,
  ProTable,
} from '@ant-design/pro-components';
import { useModel } from '@umijs/max';
import {
  Alert,
  App,
  Button,
  Checkbox,
  Drawer,
  Empty,
  Modal,
  Popconfirm,
  Space,
  Tag,
  Typography,
} from 'antd';
import React, { useCallback, useRef, useState } from 'react';
import { useHotelContext } from '@/components/HotelSelector/context';
import { describeProblem, toProblem } from '@/services/pms/errors';
import {
  changeUserStatus,
  type CreatedUserView,
  createUser,
  getUserDetail,
  type IdentityUserView,
  listRoles,
  listUsers,
  replaceUserHotelScopes,
  replaceUserRoles,
  type RoleView,
  type UserDetailView,
} from '@/services/pms/identity';

/** 账号状态色板（角色用 OrgStatusTag，账号有自己的两态）。 */
const UserStatusTag: React.FC<{ status?: string }> = ({ status }) => {
  if (!status) return <Tag>-</Tag>;
  return status === 'ACTIVE' ? (
    <Tag color="green">启用</Tag>
  ) : (
    <Tag color="default">已停用</Tag>
  );
};

/** 登录名规则与后端 `ck_app_user_username_fmt` 一致，前端先挡一次省得白跑请求。 */
const USERNAME_PATTERN = /^[a-z0-9][a-z0-9._-]{2,63}$/;

const UserManagement: React.FC = () => {
  const { message } = App.useApp();
  const actionRef = useRef<ActionType | undefined>(undefined);
  const { initialState } = useModel('@@initialState');
  const { hotels, currentHotelId } = useHotelContext();

  /** 当前登录者的 id：用来禁掉"停用自己"。 */
  const currentUserId = (
    initialState?.currentUser as { userId?: string } | undefined
  )?.userId;

  const [detail, setDetail] = useState<UserDetailView | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);

  const [scopeEditorOpen, setScopeEditorOpen] = useState(false);
  const [pendingScopes, setPendingScopes] = useState<string[]>([]);
  const [scopeSaving, setScopeSaving] = useState(false);

  const [roleEditorOpen, setRoleEditorOpen] = useState(false);
  const [pendingRoleIds, setPendingRoleIds] = useState<string[]>([]);
  const [roleSaving, setRoleSaving] = useState(false);

  const [roles, setRoles] = useState<RoleView[]>([]);
  const [createOpen, setCreateOpen] = useState(false);
  const [createSaving, setCreateSaving] = useState(false);
  /** 开通结果：初始口令只在这里存一次，关闭弹窗即丢弃。 */
  const [created, setCreated] = useState<CreatedUserView | null>(null);

  /** 角色清单按需加载：没打开过任何与角色相关的界面就不必请求。 */
  const ensureRoles = useCallback(async () => {
    try {
      setRoles(await listRoles());
    } catch (e) {
      message.error(describeProblem(toProblem(e)));
    }
  }, [message]);

  const openDetail = useCallback(
    async (userId: string) => {
      try {
        const view = await getUserDetail(userId);
        setDetail(view);
        setDetailOpen(true);
      } catch (e) {
        message.error(describeProblem(toProblem(e)));
      }
    },
    [message],
  );

  const openScopeEditor = useCallback((detailView: UserDetailView) => {
    setPendingScopes(detailView.hotelIds ?? []);
    setScopeEditorOpen(true);
  }, []);

  const openRoleEditor = useCallback(
    (detailView: UserDetailView) => {
      setPendingRoleIds((detailView.roles ?? []).map((role) => role.id ?? ''));
      setRoleEditorOpen(true);
      void ensureRoles();
    },
    [ensureRoles],
  );

  /** 角色勾选项：停用角色不允许分配——分配了等于什么都没给，界面上却像已经授权完成。 */
  const roleOptions = roles.map((role) => ({
    value: role.id ?? '',
    label: `${role.name}（${role.code}）${role.status === 'ACTIVE' ? '' : ' — 已停用'}`,
    disabled: role.status !== 'ACTIVE',
  }));

  const columns: ProColumns<IdentityUserView>[] = [
    { title: '登录名', dataIndex: 'username', copyable: true, width: 160 },
    { title: '显示名', dataIndex: 'displayName', width: 160 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      valueType: 'select',
      valueEnum: {
        ACTIVE: { text: '启用' },
        DISABLED: { text: '已停用' },
      },
      render: (_, row) => <UserStatusTag status={row.status} />,
    },
    {
      title: '最后登录',
      dataIndex: 'lastLoginAt',
      width: 180,
      search: false,
      render: (_, row) =>
        row.lastLoginAt ? (
          new Date(row.lastLoginAt).toLocaleString('zh-CN')
        ) : (
          <Typography.Text type="secondary">从未登录</Typography.Text>
        ),
    },
    {
      title: '操作',
      valueType: 'option',
      width: 170,
      render: (_, row) => {
        const isSelf = row.id === currentUserId;
        const isActive = row.status === 'ACTIVE';
        return [
          <Button
            key="detail"
            type="link"
            size="small"
            onClick={() => {
              if (!row.id) return;
              void openDetail(row.id);
            }}
          >
            详情
          </Button>,
          isSelf ? (
            // 停用自己没有恢复入口，界面直接不给这个操作。
            // 后端也会拒（409），但让人点了才看到报错是糟糕的交互。
            <Button key="toggle" type="link" size="small" disabled>
              当前账号
            </Button>
          ) : (
            <Popconfirm
              key="toggle"
              title={isActive ? '停用该账号' : '启用该账号'}
              description={
                isActive
                  ? `停用后「${row.displayName}」的全部会话立即失效，已登录的设备在下一次请求就会被拒，且无法再登录。`
                  : `启用后「${row.displayName}」可以重新登录。`
              }
              onConfirm={async () => {
                if (!row.id) return;
                try {
                  await changeUserStatus(
                    row.id,
                    isActive ? 'DISABLED' : 'ACTIVE',
                  );
                  message.success('账号状态已更新');
                  actionRef.current?.reload();
                } catch (e) {
                  message.error(describeProblem(toProblem(e)));
                }
              }}
            >
              <Button type="link" size="small" danger={isActive}>
                {isActive ? '停用' : '启用'}
              </Button>
            </Popconfirm>
          ),
        ];
      },
    },
  ];

  return (
    <PageContainer title="员工管理" subTitle="账号、角色与门店数据范围">
      <ProTable<IdentityUserView>
        actionRef={actionRef}
        rowKey="id"
        headerTitle="员工"
        columns={columns}
        toolBarRender={() => [
          <Button
            key="create"
            type="primary"
            onClick={() => {
              setCreateOpen(true);
              void ensureRoles();
            }}
          >
            新建员工
          </Button>,
        ]}
        request={async (params) => {
          try {
            const page = await listUsers({
              status: params.status ? String(params.status) : undefined,
              keyword: params.username ? String(params.username) : undefined,
              page: params.current ?? 1,
              size: params.pageSize ?? 20,
            });
            return {
              data: page.items ?? [],
              total: page.total ?? 0,
              success: true,
            };
          } catch (e) {
            message.error(describeProblem(toProblem(e)));
            return { data: [], total: 0, success: false };
          }
        }}
        search={{ labelWidth: 'auto', defaultCollapsed: false }}
        pagination={{ defaultPageSize: 20, showSizeChanger: true }}
        locale={{ emptyText: '该租户还没有员工账号' }}
      />

      {/* ---------- 开通员工 ---------- */}
      <ModalForm
        title="新建员工"
        open={createOpen}
        onOpenChange={setCreateOpen}
        modalProps={{ destroyOnClose: true, maskClosable: false }}
        initialValues={{
          hotelIds: currentHotelId ? [currentHotelId] : [],
        }}
        submitter={{
          searchConfig: { submitText: '开通账号' },
          submitButtonProps: { loading: createSaving },
        }}
        onFinish={async (values) => {
          setCreateSaving(true);
          try {
            const result = await createUser({
              username: String(values.username ?? '')
                .trim()
                .toLowerCase(),
              displayName: String(values.displayName ?? '').trim(),
              roleIds: (values.roleIds ?? []) as string[],
              hotelIds: (values.hotelIds ?? []) as string[],
            });
            setCreateOpen(false);
            // 初始口令只在此刻存在：立刻展示，关闭即丢弃。
            setCreated(result);
            actionRef.current?.reload();
            return true;
          } catch (e) {
            message.error(describeProblem(toProblem(e)));
            return false;
          } finally {
            setCreateSaving(false);
          }
        }}
      >
        <ProFormText
          name="username"
          label="登录名"
          placeholder="小写字母或数字开头，3–64 位，可含 . _ -"
          rules={[
            { required: true, message: '请输入登录名' },
            {
              pattern: USERNAME_PATTERN,
              message:
                '登录名需小写字母或数字开头，3–64 位，可含 . _ -（不支持空格与中文）',
            },
          ]}
        />
        <ProFormText
          name="displayName"
          label="显示名"
          placeholder="员工姓名，用于列表与操作留痕"
          rules={[{ required: true, message: '请输入显示名' }]}
        />
        <ProFormCheckbox.Group
          name="roleIds"
          label="角色"
          layout="vertical"
          options={roleOptions}
          rules={[{ required: true, message: '至少分配一个角色' }]}
          extra="无角色的账号能登录却什么都做不了，因此开通时必须指定。停用的角色不可选。"
        />
        <ProFormCheckbox.Group
          name="hotelIds"
          label="门店数据范围"
          layout="vertical"
          options={hotels.map((hotel) => ({
            value: hotel.id ?? '',
            label: `${hotel.name ?? ''}（${hotel.code ?? ''}）`,
          }))}
          extra="只勾选该员工实际需要操作的门店。一个都不勾表示不做门店业务，之后可以再调整。"
        />
      </ModalForm>

      {/* ---------- 初始口令（只显示一次） ---------- */}
      <Modal
        title="初始口令"
        open={Boolean(created)}
        maskClosable={false}
        okText="我已记下，关闭"
        cancelButtonProps={{ style: { display: 'none' } }}
        onOk={() => setCreated(null)}
        onCancel={() => setCreated(null)}
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          title="关闭后无法再次查看"
          description="服务端只保存口令的哈希，这个口令不会再出现在任何接口里。丢失只能重设口令。"
        />
        <ProDescriptions
          column={1}
          dataSource={{
            username: created?.user?.username,
            initialPassword: created?.initialPassword,
          }}
          columns={[
            {
              title: '登录名',
              dataIndex: 'username',
              render: (_, row) => (
                <Typography.Text copyable>{row.username}</Typography.Text>
              ),
            },
            {
              title: '初始口令',
              dataIndex: 'initialPassword',
              render: (_, row) => (
                <Typography.Text code copyable>
                  {row.initialPassword}
                </Typography.Text>
              ),
            },
          ]}
        />
        <Typography.Paragraph type="secondary" style={{ marginTop: 16, marginBottom: 0 }}>
          请通过可靠渠道转交本人。该员工首次登录会被要求先设置自己的口令，
          改完之后这份初始口令立即失效。
        </Typography.Paragraph>
      </Modal>

      {/* ---------- 详情 ---------- */}
      <Drawer
        open={detailOpen}
        onClose={() => setDetailOpen(false)}
        title="员工详情"
        width={620}
        destroyOnClose
        extra={
          detail ? (
            <Space>
              <Button onClick={() => openRoleEditor(detail)}>编辑角色</Button>
              <Button onClick={() => openScopeEditor(detail)}>编辑门店范围</Button>
            </Space>
          ) : undefined
        }
      >
        {detail?.user ? (
          <>
            <ProDescriptions<IdentityUserView>
              dataSource={detail.user}
              columns={[
                { title: '登录名', dataIndex: 'username' },
                { title: '显示名', dataIndex: 'displayName' },
                {
                  title: '状态',
                  dataIndex: 'status',
                  render: (_, row) => <UserStatusTag status={row.status} />,
                },
                {
                  title: '最后登录',
                  dataIndex: 'lastLoginAt',
                  render: (_, row) =>
                    row.lastLoginAt
                      ? new Date(row.lastLoginAt).toLocaleString('zh-CN')
                      : '从未登录',
                },
              ]}
            />

            <ProDescriptions
              style={{ marginTop: 16 }}
              title="已分配角色"
              column={1}
              dataSource={{ roles: detail.roles ?? [] }}
              columns={[
                {
                  title: '角色',
                  dataIndex: 'roles',
                  render: (_, row) => {
                    const assigned = (row.roles ?? []) as UserDetailView['roles'];
                    if (!assigned || assigned.length === 0) {
                      return (
                        <Typography.Text type="warning">
                          未分配任何角色，该账号登录后看不到任何功能
                        </Typography.Text>
                      );
                    }
                    return (
                      <Space size={[4, 4]} wrap>
                        {assigned.map((role) => (
                          <Tag
                            key={role.id}
                            color={role.status === 'ACTIVE' ? 'blue' : 'default'}
                          >
                            {role.name}（{role.code}）
                            {role.status === 'ACTIVE' ? '' : ' · 已停用'}
                          </Tag>
                        ))}
                      </Space>
                    );
                  },
                },
              ]}
            />

            <ProDescriptions
              style={{ marginTop: 16 }}
              title="门店数据范围"
              column={1}
              dataSource={{ hotelIds: detail.hotelIds ?? [] }}
              columns={[
                {
                  title: '可访问门店',
                  dataIndex: 'hotelIds',
                  render: (_, row) => {
                    const ids = (row.hotelIds ?? []) as string[];
                    if (ids.length === 0) {
                      // 空范围是合法状态，但要说明后果：
                      // 该账号能看到菜单，却打不开任何一家门店的业务数据。
                      return (
                        <Typography.Text type="warning">
                          未分配任何门店，该账号无法访问任何门店的业务数据
                        </Typography.Text>
                      );
                    }
                    return (
                      <Space size={[4, 4]} wrap>
                        {ids.map((id) => {
                          const hotel = hotels.find((item) => item.id === id);
                          return <Tag key={id}>{hotel?.name ?? id}</Tag>;
                        })}
                      </Space>
                    );
                  },
                },
              ]}
            />
          </>
        ) : (
          <Empty description="未选择员工" />
        )}
      </Drawer>

      {/* ---------- 编辑角色 ---------- */}
      <ModalForm
        title={`编辑角色${detail?.user?.displayName ? ` — ${detail.user.displayName}` : ''}`}
        open={roleEditorOpen}
        onOpenChange={setRoleEditorOpen}
        modalProps={{ destroyOnClose: true }}
        submitter={{
          searchConfig: { submitText: '保存' },
          submitButtonProps: { loading: roleSaving },
        }}
        onFinish={async () => {
          if (!detail?.user?.id) return false;
          setRoleSaving(true);
          try {
            const result = await replaceUserRoles(
              detail.user.id,
              pendingRoleIds,
            );
            message.success(`角色已更新，当前 ${result.roles?.length ?? 0} 个角色`);
            setRoleEditorOpen(false);
            await openDetail(detail.user.id);
            return true;
          } catch (e) {
            message.error(describeProblem(toProblem(e)));
            return false;
          } finally {
            setRoleSaving(false);
          }
        }}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          title="全量替换"
          description="这里勾选的就是最终结果：取消勾选的角色会被收回。若这是你自己的账号、改完后不再拥有员工与角色管理权限，且租户里没有其他管理员，后端会拒绝——撤销后没人能恢复。"
        />
        <Checkbox.Group
          value={pendingRoleIds}
          onChange={(values) => setPendingRoleIds(values as string[])}
          style={{ display: 'flex', flexDirection: 'column', gap: 8 }}
          options={roleOptions}
        />
        {roles.length === 0 && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            该租户还没有角色，请先到「角色与权限」创建
          </Typography.Text>
        )}
      </ModalForm>

      {/* ---------- 编辑门店范围 ---------- */}
      <ModalForm
        title={`编辑门店范围${detail?.user?.displayName ? ` — ${detail.user.displayName}` : ''}`}
        open={scopeEditorOpen}
        onOpenChange={setScopeEditorOpen}
        modalProps={{ destroyOnClose: true }}
        submitter={{
          searchConfig: { submitText: '保存' },
          submitButtonProps: { loading: scopeSaving },
        }}
        onFinish={async () => {
          if (!detail?.user?.id) return false;
          setScopeSaving(true);
          try {
            const result = await replaceUserHotelScopes(
              detail.user.id,
              pendingScopes,
            );
            message.success(
              `门店范围已更新，当前 ${result.hotelIds?.length ?? 0} 家门店`,
            );
            setScopeEditorOpen(false);
            // 详情里的范围要跟着变；列表不展示范围，不必刷新。
            await openDetail(detail.user.id);
            return true;
          } catch (e) {
            message.error(describeProblem(toProblem(e)));
            return false;
          } finally {
            setScopeSaving(false);
          }
        }}
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="全量替换"
          description="这里勾选的就是最终结果：取消勾选的门店会被收回，不是只增不减。全部取消表示该账号无法访问任何门店的业务数据。"
        />
        <Checkbox.Group
          value={pendingScopes}
          onChange={(values) => setPendingScopes(values as string[])}
          style={{ display: 'flex', flexDirection: 'column', gap: 8 }}
          options={hotels.map((hotel) => ({
            value: hotel.id ?? '',
            label: `${hotel.name ?? ''}（${hotel.code ?? ''}）`,
          }))}
        />
        {hotels.length === 0 && (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            当前登录账号没有可见门店，无法为他人分配范围
          </Typography.Text>
        )}
      </ModalForm>
    </PageContainer>
  );
};

export default UserManagement;
