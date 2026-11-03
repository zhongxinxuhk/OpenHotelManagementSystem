/**
 * 员工与角色管理服务（S3-02）。
 *
 * 对应后端 `IdentityAdminController`，函数名与 operationId 一致：
 * listPermissions / listRoles / createRole / changeRoleStatus /
 * listUsers / createUser / getUserDetail / changeUserStatus /
 * replaceUserRoles / replaceUserHotelScopes。
 */
import { request } from '@umijs/max';
import type {
  CreatedUserView,
  CreateRoleRequest,
  CreateUserRequest,
  HotelScopesView,
  IdentityUserView,
  PageViewIdentityUserView,
  Permission,
  RoleBriefView,
  RoleView,
  StatusRequest,
  UserDetailView,
  UserRolesRequest,
  UserRolesView,
} from './types';

export type {
  CreatedUserView,
  CreateRoleRequest,
  CreateUserRequest,
  HotelScopesView,
  IdentityUserView,
  PageViewIdentityUserView,
  Permission,
  RoleBriefView,
  RoleView,
  StatusRequest,
  UserDetailView,
  UserRolesRequest,
  UserRolesView,
};

/**
 * 权限点字典。
 *
 * 全局共享：租户之间只差"谁拿到哪些"，不各自定义权限名。
 * 管理界面渲染勾选项时用它，而不是在前端硬编码一份清单——
 * 硬编码的清单会随着后端新增权限点而静默过期。
 */
export async function listPermissions() {
  return request<Permission[]>('/api/v1/identity/permissions', {
    method: 'GET',
    skipErrorHandler: true,
  });
}

/** 角色列表，含各自登记的权限点。 */
export async function listRoles() {
  return request<RoleView[]>('/api/v1/identity/roles', {
    method: 'GET',
    skipErrorHandler: true,
  });
}

/** 创建角色。编码会被后端统一转成大写（编码要进日志与审计，风格必须统一）。 */
export async function createRole(body: CreateRoleRequest) {
  return request<RoleView>('/api/v1/identity/roles', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}

/**
 * 启停角色。
 *
 * 停用后该角色的权限点在**下一次请求**即失效——权限是每请求实时查库的，
 * 不需要等会话过期，也不需要用户重新登录。
 */
export async function changeRoleStatus(roleId: string, status: string) {
  return request<RoleView>(`/api/v1/identity/roles/${roleId}/status`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: { status } satisfies StatusRequest,
    skipErrorHandler: true,
  });
}

export interface ListUsersParams {
  status?: string;
  /** 登录名或显示名的模糊匹配。 */
  keyword?: string;
  page?: number;
  size?: number;
}

export async function listUsers(params?: ListUsersParams) {
  return request<PageViewIdentityUserView>('/api/v1/identity/users', {
    method: 'GET',
    params,
    skipErrorHandler: true,
  });
}

/**
 * 开通员工账号。
 *
 * **初始口令只在这个响应里出现一次**：服务端只存 BCrypt 哈希，事后取不回。
 * 调用方必须当场展示给操作者，并把"只显示这一次"讲清楚——
 * 丢了只能重设口令，没有"再查一次"这种操作。
 *
 * 新账号自带"待改密"标记：员工首次登录只能先改口令，
 * 管理员手上这份副本从那一刻起作废。
 */
export async function createUser(body: CreateUserRequest) {
  return request<CreatedUserView>('/api/v1/identity/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}

/**
 * 全量替换员工角色。
 *
 * **全量语义**：传进来的数组就是最终结果，取消勾选的角色会被收回。
 * 改自己时如果改完就不再拥有 `identity:write`、且租户里没有别的管理员，
 * 后端会拒绝（409）——撤销后没人能恢复。
 */
export async function replaceUserRoles(userId: string, roleIds: string[]) {
  return request<UserRolesView>(`/api/v1/identity/users/${userId}/roles`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    data: { roleIds } satisfies UserRolesRequest,
    skipErrorHandler: true,
  });
}

/** 员工详情：账号 + 已分配角色 + 门店范围。 */
export async function getUserDetail(userId: string) {
  return request<UserDetailView>(`/api/v1/identity/users/${userId}`, {
    method: 'GET',
    skipErrorHandler: true,
  });
}

/**
 * 启停账号。
 *
 * 停用会**撤销该账号的全部会话**（已登录的设备立即失效），
 * 且**不能停用自己**——把自己锁在门外后没有恢复入口。两条都由后端强制。
 */
export async function changeUserStatus(userId: string, status: string) {
  return request<IdentityUserView>(`/api/v1/identity/users/${userId}/status`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: { status } satisfies StatusRequest,
    skipErrorHandler: true,
  });
}

/**
 * 全量替换员工的门店范围。
 *
 * **全量语义**：传进来的数组就是最终结果。传空数组表示收回全部门店权限
 * （该账号将无法访问任何门店的业务接口）——这是合法操作，不是参数错误。
 */
export async function replaceUserHotelScopes(userId: string, hotelIds: string[]) {
  return request<HotelScopesView>(`/api/v1/identity/users/${userId}/hotel-scopes`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    data: { hotelIds },
    skipErrorHandler: true,
  });
}
