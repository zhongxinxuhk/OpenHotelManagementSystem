import { describe, expect, it } from 'vitest';
import type { UserView } from '@/services/pms/auth';
import access, { hasPermission } from './access';

/**
 * 可达性判断测试（S3-01）。
 *
 * 模型从模板的 `access: 'admin' | 'user'` 换成了后端权限点字典
 * （「资源:动作」）。这里钉死两条规则：
 * 1. 有权限点 → 对应能力为 true；
 * 2. 没有 / 未登录 → 一律 false（**默认拒绝**）。
 */
function user(permissions: string[]): UserView {
  return {
    userId: 'u-1',
    tenantId: 't-1',
    username: 'tester',
    displayName: '测试账号',
    permissions,
    hotelIds: [],
  };
}

describe('access', () => {
  it('grants capabilities matching the backend permission points', () => {
    const result = access({
      currentUser: user(['room:read', 'room:write', 'reservation:read']),
    });

    expect(result.canReadRoom).toBe(true);
    expect(result.canWriteRoom).toBe(true);
    expect(result.canReadReservation).toBe(true);
    expect(result.canWriteReservation).toBe(false);
    expect(result.isAuthenticated).toBe(true);
  });

  it('denies everything when the user has no permission points', () => {
    const result = access({ currentUser: user([]) });

    expect(result.canReadRoom).toBe(false);
    expect(result.canWriteRoom).toBe(false);
    expect(result.canReadReservation).toBe(false);
    expect(result.canOperateOps).toBe(false);
    expect(result.canReadReconcile).toBe(false);
  });

  it('denies everything when currentUser is undefined', () => {
    const result = access({ currentUser: undefined });

    expect(result.canReadRoom).toBe(false);
    expect(result.isAuthenticated).toBe(false);
  });

  it('denies everything when initialState is undefined', () => {
    const result = access(undefined);

    expect(result.canReadRoom).toBe(false);
    expect(result.isAuthenticated).toBe(false);
  });

  it('separates read and write capabilities', () => {
    // 只读账号不能拿到写能力：读与写是两个不同的权限点。
    const result = access({ currentUser: user(['ops:read']) });

    expect(result.canReadOps).toBe(true);
    expect(result.canOperateOps).toBe(false);
  });
});

describe('hasPermission', () => {
  it('returns false for undefined user', () => {
    expect(hasPermission(undefined, 'room:read')).toBe(false);
  });

  it('returns false when the permission is absent', () => {
    expect(hasPermission(user(['room:read']), 'room:write')).toBe(false);
  });

  it('returns true when the permission is present', () => {
    expect(hasPermission(user(['room:write']), 'room:write')).toBe(true);
  });
});
