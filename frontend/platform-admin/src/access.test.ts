import { describe, expect, it } from 'vitest';
import type { PlatformPrincipalView } from '@/services/pms/auth';
import access, { hasAction } from './access';

/**
 * 可达性判断测试 —— 平台版（R-20261102-02）。
 *
 * 模型是**动作码**（platform_action 字典，命名「域.资源.动作」），
 * 不是租户权限点。这里钉死两条规则：
 * 1. 有动作码 → 对应能力为 true；
 * 2. 没有 / 未登录 → 一律 false（**默认拒绝**）。
 */
function user(actions: string[]): PlatformPrincipalView {
  return {
    username: 'tester',
    displayName: '测试账号',
    mustChangePassword: false,
    actions,
    sessionExpiresAt: '2026-12-01T00:00:00Z',
  };
}

describe('access', () => {
  it('grants capabilities matching the platform action codes', () => {
    const result = access({
      currentUser: user([
        'guest.authpolicy.read',
        'guest.channel.read',
        'guest.channel.write',
      ]),
    });

    expect(result.canReadAuthPolicy).toBe(true);
    expect(result.canWriteAuthPolicy).toBe(false);
    expect(result.canReadChannels).toBe(true);
    expect(result.canWriteChannels).toBe(true);
    expect(result.canReadVerifications).toBe(false);
    expect(result.canReviewVerifications).toBe(false);
    expect(result.isAuthenticated).toBe(true);
  });

  it('denies everything when the user has no action codes', () => {
    const result = access({ currentUser: user([]) });

    expect(result.canReadAuthPolicy).toBe(false);
    expect(result.canWriteChannels).toBe(false);
    expect(result.isAuthenticated).toBe(true);
  });

  it('denies everything when not logged in', () => {
    const result = access({ currentUser: undefined });

    expect(result.canReadAuthPolicy).toBe(false);
    expect(result.canWriteAuthPolicy).toBe(false);
    expect(result.isAuthenticated).toBe(false);
  });

  it('hasAction does not match by prefix (must be exact)', () => {
    // guest.authpolicy.read 不应放行 guest.authpolicy.write：
    // 动作码是完整字符串匹配，没有通配语义。
    expect(
      hasAction(user(['guest.authpolicy.read']), 'guest.authpolicy.write'),
    ).toBe(false);
    expect(hasAction(user(['guest.authpolicy.read']), 'guest.authpolicy.read')).toBe(true);
  });
});
