/**
 * 平台业务服务（R-20261102-02）。
 *
 * 对应后端 `PlatformAuthPolicyController` / `PlatformChannelController` /
 * `PlatformVerificationController`，函数名与 OpenAPI operationId 一致。
 *
 * 覆盖三块真实接口：
 * · 登录方式策略（guest_login_policy 表）：住客每种登录方式的可用性 = 实现 ∧ 开关；
 * · 核验渠道（guest_verification_channel 表）：权威/人工渠道配置与熔断状态；
 * · 实名审核（guest_verification_review 表）：队列、决定与留痕——
 *   管理员控制台只做**只读统计**（概览卡片用），完整审核工作台在 platform-ops。
 *
 * 授权：后端按动作码校验（guest.authpolicy.read/write、guest.channel.read/write、
 * guest.verification.read/review），前端 access 只控制入口可见性。
 */
import { request } from '@umijs/max';
import type {
  CreateChannelRequest,
  PageViewPendingVerification,
  PendingVerification,
  PolicyUpdateRequest,
  PolicyView,
  ResetCircuitRequest,
  UpdateChannelRequest,
  ChannelView,
} from './types';

export type {
  ChannelView,
  CreateChannelRequest,
  PageViewPendingVerification,
  PendingVerification,
  PolicyUpdateRequest,
  PolicyView,
  ResetCircuitRequest,
  UpdateChannelRequest,
};

// ==================== 登录方式策略 ====================

/** 住客登录方式清单：provider × 可用性（available = implemented ∧ enabled）。 */
export async function listGuestLoginPolicy() {
  return request<PolicyView[]>('/api/v1/platform/guest-login-policy', {
    method: 'GET',
    skipErrorHandler: true,
  });
}

/**
 * 设置某个登录方式的开关。
 *
 * 只改 `enabled`：`implemented` 由代码版本决定（接通新通道 = 一次发布），
 * `available` 是两者的与——三者关系见 ADR-019 §2.9。
 */
export async function setGuestLoginPolicy(
  provider: string,
  body: PolicyUpdateRequest,
) {
  return request<PolicyView>(
    `/api/v1/platform/guest-login-policy/${provider}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      skipErrorHandler: true,
    },
  );
}

// ==================== 核验渠道 ====================

/** 核验渠道清单：配置 + 熔断状态（权威渠道熔断时人工审核照常，ADR-019 §2.5）。 */
export async function listVerificationChannels() {
  return request<ChannelView[]>('/api/v1/platform/verification-channels', {
    method: 'GET',
    skipErrorHandler: true,
  });
}

/** 新增核验渠道。kind=MANUAL 不可停用（人工是熔断兜底，结构保证）。 */
export async function createVerificationChannel(
  body: CreateChannelRequest,
) {
  return request<ChannelView>('/api/v1/platform/verification-channels', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}

/** 修改渠道配置（名称、地址、优先级、超时、启停）。 */
export async function updateVerificationChannel(
  code: string,
  body: UpdateChannelRequest,
) {
  return request<ChannelView>(
    `/api/v1/platform/verification-channels/${code}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      skipErrorHandler: true,
    },
  );
}

/** 手动复位熔断：必须填原因（留痕，防止"悄悄重置掩盖真实故障"）。 */
export async function resetVerificationChannelCircuit(
  code: string,
  body: ResetCircuitRequest,
) {
  return request<ChannelView>(
    `/api/v1/platform/verification-channels/${code}/reset-circuit`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      skipErrorHandler: true,
    },
  );
}

// ==================== 实名审核（只读统计） ====================

/**
 * 实名待审核队列（分页）。管理员控制台的概览卡片只用 total；
 * 完整的审核工作台（决定 + 历史）在 platform-ops。
 */
export async function listGuestVerifications(params?: {
  page?: number;
  size?: number;
}) {
  return request<PageViewPendingVerification>(
    '/api/v1/platform/guest-verifications',
    {
      method: 'GET',
      params,
      skipErrorHandler: true,
    },
  );
}
