/**
 * 住客认证服务（R-20261102-05）。
 *
 * 对应后端 `GuestAuthController`，函数名与 OpenAPI operationId 一致。
 * GUEST 平面（ADR-019 §2.3）：与员工/平台**不同表、不同会话、不同授权模型**。
 *
 * 当前只有用户名+密码一种登录方式（`isImplemented()` 只标 USERNAME）；
 * EMAIL 验证码登录是下一轮（通道已在 R-20261102-01 接通，消费侧入口待建）。
 */
import { request } from '@umijs/max';
import type {
  CodeLoginChallengeRequest,
  CodeLoginChallengeView,
  CodeLoginRequest,
  GuestAuthResponse,
  GuestLoginRequest,
  GuestMeView,
  GuestRegisterRequest,
  PasswordChangeRequest,
  PasswordResetRequest,
  ResetChallengeRequest,
  ResetChallengeView,
} from './types';

export type {
  GuestAuthResponse,
  GuestLoginRequest,
  GuestMeView,
  GuestRegisterRequest,
};

/** 登录（用户名 + 口令）。失败文案由后端统一（防账号枚举），前端原样透传。 */
export async function guestLogin(body: GuestLoginRequest) {
  return request<GuestAuthResponse>('/api/v1/guest/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}

/** 注册（用户名 + 口令，可选显示名）。注册即登录：响应直接带会话令牌。 */
export async function guestRegister(body: GuestRegisterRequest) {
  return request<GuestAuthResponse>('/api/v1/guest/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}

/** 登出：撤销当前会话。 */
export async function guestLogout() {
  return request<void>('/api/v1/guest/auth/logout', {
    method: 'POST',
    skipErrorHandler: true,
  });
}

/**
 * 当前身份：显示名、实名状态（UNVERIFIED/PENDING/VERIFIED/REJECTED）、
 * 会话过期时间。`realNameRequired=true` 表示未实名前只能用实名相关接口。
 */
export async function getGuestMe() {
  return request<GuestMeView>('/api/v1/guest/me', {
    method: 'GET',
    skipErrorHandler: true,
  });
}

// ==================== 邮箱验证码登录（R-20261102-07） ====================

export type {
  CodeLoginChallengeRequest,
  CodeLoginChallengeView,
  CodeLoginRequest,
};

/**
 * 发起邮箱验证码登录挑战（匿名）。
 *
 * 枚举防护（后端口径）：未注册邮箱也照发验证码——响应形态与已注册完全一致，
 * 提交码时"未注册/码错/账户关闭"统一文案。本函数不做任何邮箱存在性判断。
 */
export async function guestCodeLoginChallenge(
  body: CodeLoginChallengeRequest,
) {
  return request<CodeLoginChallengeView>(
    '/api/v1/guest/auth/code-login/challenge',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      skipErrorHandler: true,
    },
  );
}

/** 用验证码完成登录（两步之二）。挑战失效（过期/超次/已消费）返回 409。 */
export async function guestCodeLogin(body: CodeLoginRequest) {
  return request<GuestAuthResponse>('/api/v1/guest/auth/code-login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}


// ==================== 改密与忘记密码（R-20261103-01） ====================

export type {
  PasswordChangeRequest,
  PasswordResetRequest,
  ResetChallengeRequest,
  ResetChallengeView,
};

/**
 * 已登录改密：校验当前口令（会话可能是被盗的 cookie，口令是第二因子）；
 * 成功后撤销**其它**会话、保留当前。
 */
export async function guestChangePassword(body: PasswordChangeRequest) {
  return request<void>('/api/v1/guest/auth/password', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}

/**
 * 忘记密码：发起重置挑战（匿名）。
 *
 * 枚举防护（后端口径）：未注册邮箱也照发码，响应形态与已注册一致。
 * 不受 EMAIL 登录开关约束（账号恢复 ≠ 登录方式）。
 */
export async function guestPasswordResetChallenge(
  body: ResetChallengeRequest,
) {
  return request<ResetChallengeView>(
    '/api/v1/guest/auth/password-reset/challenge',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      skipErrorHandler: true,
    },
  );
}

/**
 * 忘记密码：用验证码完成重置（匿名）。
 * 成功撤销全部会话、不自动登录——需用新口令重新登录。
 */
export async function guestPasswordReset(body: PasswordResetRequest) {
  return request<void>('/api/v1/guest/auth/password-reset', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}
