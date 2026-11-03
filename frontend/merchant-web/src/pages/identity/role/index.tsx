/**
 * 角色与权限点管理（S3-02）。
 *
 * 内容：角色列表（含每个角色登记的权限点）、新建角色（勾选权限点）、启停角色。
 *
 * 两条与业务强相关的设计：
 * 1. **权限点清单来自后端字典**，不在前端硬编码。硬编码的清单会随后端新增
 *    权限点而静默过期，管理员会以为"系统里没有这个权限"，实际是没有渲染出来。
 * 2. **停用角色要说明后果**：停用后该角色的权限点在持该角色的人**下一次请求**即失效，
 *    不需要重新登录。这条必须写进确认框，否则运维会以为"停了但还在生效"。
 */

import type { ProColumns } from '@ant-design/pro-components';
import {
  ModalForm,
  PageContainer,
  ProFormCheckbox,
  ProFormText,
  ProTable,
} from '@ant-design/pro-components';
import { Alert, App, Button, Popconfirm, Space, Tag, Typography } from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { OrgStatusTag } from '@/components/StatusTag';
import { describeProblem, toProblem } from '@/services/pms/errors';
import {
  changeRoleStatus,
  createRole,
  listPermissions,
  listRoles,
  type Permission,
  type RoleView,
} from '@/services/pms/identity';

/**
 * 把权限点按资源分组。
 *
 * 权限点命名是「资源:动作」，平铺展示二十来个复选框没人看得下去；
 * 按冒号前的前缀分组后，"房型与房间"这一组里有哪些动作一目了然。
 */
function groupByResource(
  permissions: Permission[],
): { resource: string; items: Permission[] }[] {
  const groups = new Map<string, Permission[]>();
  for (const permission of permissions) {
    const resource = permission.code?.split(':')[0] ?? 'other';
    const list = groups.get(resource) ?? [];
    list.push(permission);
    groups.set(resource, list);
  }
  return [...groups.entries()]
    .map(([resource, items]) => ({ resource, items }))
    .sort((a, b) => a.resource.localeCompare(b.resource));
}

/** 资源名 → 展示名。没收录的用原 resource 名，不猜。 */
const RESOURCE_LABEL: Record<string, string> = {
  org: '组织与门店',
  identity: '员工与角色',
  room: '房型与房间',
  reservation: '订单',
  inventory: '库存与房态',
  operation: '操作结果',
  ops: '运维处置',
  reconcile: '对账',
  payment: '支付',
  billing: '账务',
  report: '报表',
};

const RoleManagement: React.FC = () => {
  const { message } = App.useApp();
  const [roles, setRoles] = useState<RoleView[]>([]);
  const [permissions, setPermissions] = useState<Permission[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      const [roleList, permissionList] = await Promise.all([
        listRoles(),
        listPermissions(),
      ]);
      setRoles(roleList);
      setPermissions(permissionList);
    } catch (e) {
      setError(describeProblem(toProblem(e)));
      setRoles([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const groups = useMemo(() => groupByResource(permissions), [permissions]);

  /** 权限点编码 → 展示名，用于列表里回显。 */
  const permissionName = useMemo(() => {
    const map = new Map<string, string>();
    for (const permission of permissions) {
      if (permission.code)
        map.set(permission.code, permission.name ?? permission.code);
    }
    return map;
  }, [permissions]);

  const columns: ProColumns<RoleView>[] = [
    { title: '编码', dataIndex: 'code', copyable: true, width: 180 },
    { title: '名称', dataIndex: 'name', width: 160 },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (_, row) => <OrgStatusTag status={row.status} />,
    },
    {
      title: '已授予权限点',
      dataIndex: 'permissionCodes',
      render: (_, row) => {
        const codes = row.permissionCodes ?? [];
        if (codes.length === 0) {
          return (
            <Typography.Text type="secondary">未授予任何权限点</Typography.Text>
          );
        }
        return (
          <Space size={[4, 4]} wrap>
            {codes.map((code) => (
              <Tag key={code}>{permissionName.get(code) ?? code}</Tag>
            ))}
          </Space>
        );
      },
    },
    {
      title: '操作',
      valueType: 'option',
      width: 120,
      render: (_, row) => [
        <Popconfirm
          key="toggle"
          title={row.status === 'ACTIVE' ? '停用该角色' : '启用该角色'}
          description={
            row.status === 'ACTIVE'
              ? `停用后，持有「${row.name}」的员工在下一次请求即失去该角色的全部权限点，不需要重新登录。`
              : `启用后，持有「${row.name}」的员工立即恢复该角色的权限点。`
          }
          onConfirm={async () => {
            try {
              await changeRoleStatus(
                row.id ?? '',
                row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
              );
              message.success('角色状态已更新');
              void load();
            } catch (e) {
              message.error(describeProblem(toProblem(e)));
            }
          }}
        >
          <Button type="link" size="small" danger={row.status === 'ACTIVE'}>
            {row.status === 'ACTIVE' ? '停用' : '启用'}
          </Button>
        </Popconfirm>,
      ],
    },
  ];

  return (
    <PageContainer
      title="角色与权限"
      subTitle="权限点按「资源:动作」划分，读写分开授予"
    >
      {error && (
        <Alert
          type="error"
          showIcon
          message="加载失败"
          description={error}
          style={{ marginBottom: 16 }}
        />
      )}

      <ProTable<RoleView>
        rowKey="id"
        headerTitle="角色"
        columns={columns}
        dataSource={roles}
        loading={loading}
        search={false}
        options={false}
        pagination={false}
        locale={{ emptyText: '该租户还没有创建任何角色' }}
        toolBarRender={() => [
          <ModalForm
            key="create"
            title="新建角色"
            width={640}
            trigger={<Button type="primary">新建角色</Button>}
            modalProps={{ destroyOnClose: true }}
            onFinish={async (values) => {
              try {
                await createRole({
                  code: values.code as string,
                  name: values.name as string,
                  permissionCodes: (values.permissionCodes as string[]) ?? [],
                });
                message.success('角色已创建');
                void load();
                return true;
              } catch (e) {
                message.error(describeProblem(toProblem(e)));
                return false;
              }
            }}
          >
            <ProFormText
              name="code"
              label="角色编码"
              placeholder="大写字母开头，可含数字与下划线，如 FRONT_OFFICE"
              rules={[
                { required: true, message: '请填写角色编码' },
                {
                  pattern: /^[A-Za-z][A-Za-z0-9_]{1,31}$/,
                  message: '大写字母开头，2–32 位，可含数字与下划线',
                },
              ]}
            />
            <ProFormText
              name="name"
              label="角色名称"
              rules={[{ required: true, message: '请填写角色名称' }]}
            />
            <ProFormCheckbox.Group
              name="permissionCodes"
              label="权限点"
              layout="vertical"
              // 每个权限点单独一个选项（value 是 code）。
              // 按资源分组只影响**展示标签**，不影响取值——
              // 若把整个资源当成一个选项（value 是资源名），提交上去的就不是权限点编码了。
              options={groups.flatMap((group) =>
                group.items.map((permission) => ({
                  label: `【${RESOURCE_LABEL[group.resource] ?? group.resource}】${permission.name}（${permission.code}）`,
                  value: permission.code ?? '',
                })),
              )}
            />
          </ModalForm>,
        ]}
      />

      <Typography.Paragraph
        type="secondary"
        style={{ marginTop: 16, fontSize: 12 }}
      >
        权限点是全局字典，租户之间只差「谁拿到哪些」。停用角色不会删除授权关系，恢复启用后权限点原样回来。
      </Typography.Paragraph>
    </PageContainer>
  );
};

export default RoleManagement;
