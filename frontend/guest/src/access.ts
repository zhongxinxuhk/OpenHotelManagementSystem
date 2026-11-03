/**
 * 前端可达性判断 —— 住客版（R-20261102-05）。
 *
 * **这不是授权。** 服务端才是唯一授权关口（guest 面按会话校验，
 * 未实名只能用实名相关接口——由后端 @PreAuthorize 拦）。
 *
 * 住客没有动作码/权限点模型（ADR-019 §2.4）：授权完全由
 * 「会话有效性 + 实名状态」决定，这里只做页面可见性。
 */
import type { GuestMeView } from '@/services/pms/auth';

export default function access(
  initialState: { currentUser?: GuestMeView } | undefined,
) {
  const user = initialState?.currentUser;
  return {
    /** 是否已登录（所有页面的基础可见性）。 */
    isAuthenticated: Boolean(user),
    /** 未通过实名：下单等业务接口会被服务端拒绝，页面提示实名。 */
    needsRealName: Boolean(
      user && !['VERIFIED'].includes(user.verificationStatus ?? ''),
    ),
  };
}
