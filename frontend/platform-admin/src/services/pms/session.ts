/**
 * 会话令牌存取 —— 平台平面（R-20261102-02）。
 *
 * 结构与 merchant-web 的 session.ts 相同（克隆而来），两点差异：
 * 1. **存储键不同**（`pms.platform.*`）：三条身份平面的令牌哈希域在后端是分离的
 *    （ADR-019 §2.2），前端也不共用存储键——万一两个控制台被部署到同一域名下，
 *    平台令牌不会覆盖商家令牌；
 * 2. 注释指向平台账号。
 *
 * 为什么 localStorage 而不是 Cookie：后端是不透明 Bearer 令牌（服务端会话表），
 * 而不是 JWT。令牌不携带任何身份信息，撤销立即生效。
 * 代价是令牌必须由前端主动携带，因此这里集中管理读写与清理。
 *
 * 为什么不放内存（模块级变量）：刷新页面就丢，用户会被莫名其妙踢回登录页。
 *
 * 为什么不放 Cookie：前端不读 Cookie，令牌会被 CSRF 利用；
 * 且 XSS 下 Cookie 会被自动带上，而 localStorage 至少要显式取用。
 */
const TOKEN_KEY = 'pms.platform.session.token';
const EXPIRES_KEY = 'pms.platform.session.expiresAt';

/** 读取当前令牌；不存在返回 null（表示未登录）。 */
export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    // 隐私模式或存储被禁用：当作未登录，不让页面崩在读取令牌这一步。
    return null;
  }
}

/** 会话过期时间（ISO 字符串）；没有会话时返回 null。 */
export function getExpiresAt(): string | null {
  try {
    return localStorage.getItem(EXPIRES_KEY);
  } catch {
    return null;
  }
}

/**
 * 保存会话。
 *
 * @param token 明文令牌。只在登录响应里出现一次，库里只存 SHA-256（域分离后平台令牌在
 * 租户/住客会话表里命不中——跨平面令牌互用天然被挡）。
 * @param expiresAt 会话过期时间；到期后前端主动跳登录页，不必等服务端拒绝。
 */
export function saveSession(token: string, expiresAt?: string): void {
  try {
    localStorage.setItem(TOKEN_KEY, token);
    if (expiresAt) {
      localStorage.setItem(EXPIRES_KEY, expiresAt);
    }
  } catch {
    // 存储不可用时静默失败：登录本身已成功，只是刷新后会话丢失。
  }
}

/** 清除会话。登出、401、切换账号时都要调用。 */
export function clearSession(): void {
  try {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(EXPIRES_KEY);
  } catch {
    // 同上：清理失败不影响后续流程。
  }
}

/**
 * 会话是否已过期。
 *
 * 提前 30 秒判过期：网络往返加上处理时间都算在内，
 * 否则"前端认为还有效 → 发出请求 → 服务端刚好判过期"会变成一次莫名其妙的 401。
 */
export function isSessionExpired(): boolean {
  const expiresAt = getExpiresAt();
  if (!expiresAt) return false;
  const deadline = new Date(expiresAt).getTime();
  if (Number.isNaN(deadline)) return false;
  return deadline - 30_000 <= Date.now();
}
