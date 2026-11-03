/**
 * 前端可达性判断（S3-01）。
 *
 * **这不是授权。** 服务端才是唯一授权关口（后端每个接口都有 @PreAuthorize），
 * 这里的判断只用于"菜单要不要显示""按钮要不要给"。
 * 隐藏入口不等于有权限——真正的边界在服务端。
 *
 * 权限点来自后端 `permission` 字典，命名规则「资源:动作」，
 * 与 `/api/v1/auth/me` 返回的 `permissions` 数组一一对应。
 */
import type { UserView } from '@/services/pms/auth';

/** 前端路由/按钮用的权限点，与后端字典保持一致。 */
export const PERMISSIONS = {
  orgRead: 'org:read',
  orgWrite: 'org:write',
  identityRead: 'identity:read',
  identityWrite: 'identity:write',
  roomRead: 'room:read',
  roomWrite: 'room:write',
  reservationRead: 'reservation:read',
  reservationWrite: 'reservation:write',
  inventoryRead: 'inventory:read',
  inventoryWrite: 'inventory:write',
  operationRead: 'operation:read',
  opsRead: 'ops:read',
  opsOperate: 'ops:operate',
  reconcileRead: 'reconcile:read',
  reconcileOperate: 'reconcile:operate',
} as const;

/** 当前身份是否持有某个权限点。 */
export function hasPermission(
  user: UserView | undefined,
  permission: string,
): boolean {
  return Boolean(user?.permissions?.includes(permission));
}

export default function access(
  initialState: { currentUser?: UserView } | undefined,
) {
  const user = initialState?.currentUser;
  const can = (permission: string) => hasPermission(user, permission);

  return {
    canReadOrg: can(PERMISSIONS.orgRead),
    canWriteOrg: can(PERMISSIONS.orgWrite),
    canReadIdentity: can(PERMISSIONS.identityRead),
    canWriteIdentity: can(PERMISSIONS.identityWrite),
    canReadRoom: can(PERMISSIONS.roomRead),
    canWriteRoom: can(PERMISSIONS.roomWrite),
    canReadReservation: can(PERMISSIONS.reservationRead),
    canWriteReservation: can(PERMISSIONS.reservationWrite),
    canReadInventory: can(PERMISSIONS.inventoryRead),
    canWriteInventory: can(PERMISSIONS.inventoryWrite),
    canReadOperation: can(PERMISSIONS.operationRead),
    canReadOps: can(PERMISSIONS.opsRead),
    canOperateOps: can(PERMISSIONS.opsOperate),
    canReadReconcile: can(PERMISSIONS.reconcileRead),
    canOperateReconcile: can(PERMISSIONS.reconcileOperate),
    /** 是否已登录（用于所有页面的基础可见性）。 */
    isAuthenticated: Boolean(user),
  };
}
