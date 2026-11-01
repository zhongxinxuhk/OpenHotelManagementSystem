/**
 * 住客凭证绑定服务（R-20261102-05）。
 *
 * 对应后端 `GuestBindingController`，operationId 一致。
 * ADR-019 §2.10：绑定 PHONE/EMAIL 走**挑战-响应**（向目标发验证码，
 * 提交正确的码才允许绑定）；绑定用户名不需要挑战（密码即归属证明）。
 *
 * EMAIL 通道已接通（R-20261102-01：SMTP + MailPit，默认关显式开）；
 * 未开启时发起 EMAIL 挑战得到 503「验证通道未接通」——本服务层透传，
 * 由页面如实呈现（不静默、不假装发送成功）。
 */
import { request } from '@umijs/max';
import type {
  BindUsernameRequest,
  ChallengeRequest,
  ChallengeView,
  ConfirmRequest,
  GuestCredentialView,
  GuestMethodView,
} from './types';

export type {
  BindUsernameRequest,
  ChallengeRequest,
  ChallengeView,
  ConfirmRequest,
  GuestCredentialView,
  GuestMethodView,
};

/** 匿名可读：当前开放哪些登录方式（登录页渲染按钮用）。 */
export async function listGuestLoginMethods() {
  return request<GuestMethodView[]>('/api/v1/guest/auth/methods', {
    method: 'GET',
    skipErrorHandler: true,
  });
}

/** 我的凭证列表（本人可见完整标识；管理端看到的是掩码，另一条路径）。 */
export async function listGuestBindings() {
  return request<GuestCredentialView[]>('/api/v1/guest/auth/bindings', {
    method: 'GET',
    skipErrorHandler: true,
  });
}

/**
 * 向目标发起归属验证（EMAIL/PHONE）。
 *
 * @throws 503 发送通道未接通（mail.enabled=false 或短信未定）——如实呈现
 * @throws 409 目标已被其它账户绑定
 */
export async function guestBindingChallenge(body: ChallengeRequest) {
  return request<ChallengeView>('/api/v1/guest/auth/bindings/challenge', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}

/** 提交验证码完成绑定。码错会计入尝试次数（5 次上限，后端控制）。 */
export async function guestBindingConfirm(body: ConfirmRequest) {
  return request<GuestCredentialView[]>('/api/v1/guest/auth/bindings/confirm', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}

/** 绑定用户名（密码即归属证明，无需验证码）。 */
export async function guestBindUsername(body: BindUsernameRequest) {
  return request<GuestCredentialView[]>('/api/v1/guest/auth/bindings/username', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    data: body,
    skipErrorHandler: true,
  });
}

/**
 * 解绑凭证。后端拒绝解绑最后一个凭证（409）：解绑完无法登录，
 * 住客没有"平台帮恢复"的路（A-05 未建）。
 */
export async function guestUnbind(params: {
  provider: string;
  subject: string;
}) {
  return request<GuestCredentialView[]>('/api/v1/guest/auth/bindings', {
    method: 'DELETE',
    params,
    skipErrorHandler: true,
  });
}
