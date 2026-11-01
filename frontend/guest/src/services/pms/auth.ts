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
  GuestAuthResponse,
  GuestLoginRequest,
  GuestMeView,
  GuestRegisterRequest,
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
