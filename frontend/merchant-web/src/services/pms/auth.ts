/**
 * 认证服务（S3-01）。
 *
 * 对应后端 `AuthController`，函数名与 OpenAPI operationId 一致：
 * authLogin / authLogout / authMe / authChangePassword。
 */
import { request } from '@umijs/max';
import type {
  ChangePasswordRequest,
  LoginRequest,
  LoginResponse,
  UserView,
} from './types';

export type {
  ChangePasswordRequest,
  LoginRequest,
  LoginResponse,
  UserView,
};


/**
 * 登录。
 *
 * 后端要求 `tenantCode`：登录是唯一允许从请求体取租户的入口（此时还没有身份），
 * 登录之后所有请求的租户都来自服务端会话，前端不再传。
 */
export async function authLogin(body: LoginRequest) {
  return request<LoginResponse>('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}

/** 登出：撤销**当前**会话，其他设备上的会话不受影响。 */
export async function authLogout() {
  return request<void>('/api/v1/auth/logout', {
    method: 'POST',
    skipErrorHandler: true,
  });
}

/** 当前身份：用户名、权限点、可访问门店、���话过期时间。 */
export async function authMe() {
  return request<UserView>('/api/v1/auth/me', {
    method: 'GET',
    skipErrorHandler: true,
  });
}

/**
 * 改密。
 *
 * 后端会回收该账号的**其他**会话，保留当前会话；响应带 `X-Reauthenticate: true`，
 * 提示客户端重新拉取 /me——因为权限与门店范围可能已被调整。
 */
export async function authChangePassword(body: ChangePasswordRequest) {
  return request<void>('/api/v1/auth/password', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}
