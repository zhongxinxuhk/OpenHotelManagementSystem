/**
 * 平台认证服务（R-20261102-02）。
 *
 * 对应后端 `PlatformAuthController`，函数名与 OpenAPI operationId 一致：
 * platformLogin / platformLogout / getPlatformMe / platformChangePassword。
 *
 * 与租户登录（merchant-web 的 authLogin）的差异：
 * 1. **没有 tenantCode**：平台账号不在任何租户下（platform_user 全库无 tenant_id），
 *    登录不需要、也不接受租户参数；
 * 2. 身份字段是**动作码**（actions）而不是权限点（permissions）——
 *    两套字典刻意分离（ADR-019 §2.4：平台动作不复用租户权限字典）。
 */
import { request } from '@umijs/max';
import type {
  PlatformChangePasswordRequest,
  PlatformLoginRequest,
  PlatformLoginResponse,
  PlatformPrincipalView,
} from './types';

export type {
  PlatformChangePasswordRequest,
  PlatformLoginRequest,
  PlatformLoginResponse,
  PlatformPrincipalView,
};

/** 登录：平台账号（用户名 + 口令，无租户）。 */
export async function platformLogin(body: PlatformLoginRequest) {
  return request<PlatformLoginResponse>('/api/v1/platform/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}

/** 登出：撤销**当前**会话，其他设备上的会话不受影响。 */
export async function platformLogout() {
  return request<void>('/api/v1/platform/auth/logout', {
    method: 'POST',
    skipErrorHandler: true,
  });
}

/** 当前身份：登录名、显示名、动作码列表、会话过期时间（不含口令哈希与账号 id）。 */
export async function getPlatformMe() {
  return request<PlatformPrincipalView>('/api/v1/platform/auth/me', {
    method: 'GET',
    skipErrorHandler: true,
  });
}

/**
 * 改密。口令规则与租户侧同一套 PasswordPolicy（12 位下限）。
 *
 * 后端会回收该账号的**其他**会话，保留当前会话；改完重新拉 /me。
 */
export async function platformChangePassword(
  body: PlatformChangePasswordRequest,
) {
  return request<void>('/api/v1/platform/auth/password', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}
