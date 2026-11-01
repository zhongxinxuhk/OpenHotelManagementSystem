/**
 * 平台业务服务（R-20261102-02 建立于 platform-admin，R-20261102-03 随 platform-ops 扩展）。
 *
 * 对应后端 `PlatformAuthPolicyController` / `PlatformChannelController` /
 * `PlatformVerificationController`，函数名与 OpenAPI operationId 一致。
 *
 * 覆盖四块真实接口：
 * · 登录方式策略（guest_login_policy 表）：住客每种登录方式的可用性 = 实现 ∧ 开关；
 * · 核验渠道（guest_verification_channel 表）：权威/人工渠道配置与熔断状态；
 * · 实名审核（guest_verification_review 表）：队列、决定与留痕——
 *   platform-ops 的**核心工作台**，platform-admin 只做只读统计。
 *
 * 授权：后端按动作码校验（guest.authpolicy.read/write、guest.channel.read/write、
 * guest.verification.read/review），前端 access 只控制入口可见性。
 */
import { request } from '@umijs/max';
import type {
  ChannelView,
  CreateChannelRequest,
  PageViewPendingVerification,
  PendingVerification,
  PolicyUpdateRequest,
  PolicyView,
  ResetCircuitRequest,
  ReviewDecisionRequest,
  ReviewView,
  UpdateChannelRequest,
} from './types';

export type {
  ChannelView,
  CreateChannelRequest,
  PageViewPendingVerification,
  PendingVerification,
  PolicyUpdateRequest,
  PolicyView,
  ResetCircuitRequest,
  ReviewDecisionRequest,
  ReviewView,
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

// ==================== 实名审核 ====================

/**
 * 实名待审核队列（分页）。掩码字段（idNumberMask/realNameMask）是服务端刻意裁剪的：
 * 完整证件号只存在于加密存储，解密走独立受审计流程，不进审核队列。
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

/**
 * 提交人工审核决定（VERIFIED / REJECTED，必填理由）。
 *
 * 决定写入 guest_verification_review 留痕：谁、何时、看到什么（掩码快照）、
 * 为什么。驳回不写 verified_at（ADR-019 §2.5）。并发提交后端返回 409。
 */
export async function reviewGuestVerification(
  accountId: string,
  body: ReviewDecisionRequest,
) {
  return request<void>(
    `/api/v1/platform/guest-verifications/${accountId}/decision`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      data: body,
      skipErrorHandler: true,
    },
  );
}

/** 某账户的审核留痕历史（含每一次决定的掩码快照与理由）。 */
export async function listGuestVerificationHistory(accountId: string) {
  return request<ReviewView[]>(
    `/api/v1/platform/guest-verifications/${accountId}/history`,
    {
      method: 'GET',
      skipErrorHandler: true,
    },
  );
}
