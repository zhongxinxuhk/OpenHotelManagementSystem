/**
 * 前端可达性判断 —— 平台版（R-20261102-02）。
 *
 * **这不是授权。** 服务端才是唯一授权关口（后端平台接口按动作码校验），
 * 这里的判断只用于"菜单要不要显示""按钮要不要给"。
 * 隐藏入口不等于有权限——真正的边界在服务端。
 *
 * 平台动作码来自 `platform_action` 字典（V15/V16 种子，命名「域.资源.动作」），
 * 与 `GET /api/v1/platform/auth/me` 返回的 `actions` 数组一一对应。
 * 刻意**不**与租户权限点（permission 字典）共用常量——两套字典语义不同
 * （ADR-019 §2.4），混在一个文件里迟早有人拿 `room:read` 去配平台账号。
 */
import type { PlatformPrincipalView } from '@/services/pms/auth';

/** 平台动作码，与后端 platform_action 字典保持一致。 */
export const PLATFORM_ACTIONS = {
  authPolicyRead: 'guest.authpolicy.read',
  authPolicyWrite: 'guest.authpolicy.write',
  channelRead: 'guest.channel.read',
  channelWrite: 'guest.channel.write',
  verificationRead: 'guest.verification.read',
  verificationReview: 'guest.verification.review',
} as const;

/** 当前身份是否持有某个动作码。 */
export function hasAction(
  user: PlatformPrincipalView | undefined,
  action: string,
): boolean {
  return Boolean(user?.actions?.includes(action));
}

export default function access(
  initialState: { currentUser?: PlatformPrincipalView } | undefined,
) {
  const user = initialState?.currentUser;
  const can = (action: string) => hasAction(user, action);

  return {
    canReadAuthPolicy: can(PLATFORM_ACTIONS.authPolicyRead),
    canWriteAuthPolicy: can(PLATFORM_ACTIONS.authPolicyWrite),
    canReadChannels: can(PLATFORM_ACTIONS.channelRead),
    canWriteChannels: can(PLATFORM_ACTIONS.channelWrite),
    canReadVerifications: can(PLATFORM_ACTIONS.verificationRead),
    canReviewVerifications: can(PLATFORM_ACTIONS.verificationReview),
    /** 是否已登录（用于所有页面的基础可见性）。 */
    isAuthenticated: Boolean(user),
  };
}
