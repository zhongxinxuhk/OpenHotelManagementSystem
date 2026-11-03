-- =====================================================================
-- V17 住客凭证绑定的归属挑战（ADR-019 §2.10，R-20261101-33）
--
-- 绑定一个新凭证（手机号/邮箱/第三方）到已有账户，核心安全问题是
-- "如何证明这个手机号是你的"。流程：发起挑战 → 通道发送验证码 →
-- 提交验证码 → 通过后绑定。验证码服务商未定，本轮交付机制与发送器桩。
--
-- 三条设计要点：
--   1. **验证码不落明文**：6 位数字熵只有 10^6 ≈ 20 bit，单独扛不住暴力枚举，
--      所以库里存 SHA-256 哈希，防线是"哈希存储 + 尝试次数上限 + 短过期"三件套——
--      任何一条单独存在都不够，必须同时生效。
--   2. **挑战归属发起者**：challenge 绑定 account_id，confirm 时校验
--      "这个挑战是我发起的"。否则拿到别人的 challengeId 就能把
--      自己的手机号绑到别人账户上（账户接管）。
--   3. **consumed_at 一次性**：验证码用过即作废。同一码绑两个方式、
--      或重放同一次验证，都必须被挡住。
-- =====================================================================

CREATE TABLE guest_ownership_challenge (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  uuid        NOT NULL,
    provider    text        NOT NULL,
    subject     text        NOT NULL,
    -- SHA-256(6 位数字码) 的十六进制；明文只在发送通道里，不落库
    code_hash   text        NOT NULL,
    expires_at  timestamptz NOT NULL,
    attempts    integer     NOT NULL DEFAULT 0,
    consumed_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_guest_challenge_account FOREIGN KEY (account_id)
        REFERENCES guest_account (id) ON DELETE CASCADE,
    CONSTRAINT ck_guest_challenge_provider
        CHECK (provider IN ('PHONE', 'EMAIL', 'WECHAT', 'QQ', 'CNID_SSO', 'APPLE', 'CORP_SSO')),
    CONSTRAINT ck_guest_challenge_subject_blank CHECK (length(btrim(subject)) > 0),
    CONSTRAINT ck_guest_challenge_code_hash CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    -- USERNAME 不走挑战（密码即证明），防止出现"给用户名发验证码"这种没有意义的行
    CONSTRAINT ck_guest_challenge_not_username CHECK (provider <> 'USERNAME'),
    CONSTRAINT ck_guest_challenge_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_guest_challenge_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_guest_challenge_consumed CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);

CREATE INDEX idx_guest_challenge_account ON guest_ownership_challenge (account_id);
-- 清理过期挑战用（将来加定时清理时按它扫）
CREATE INDEX idx_guest_challenge_expires ON guest_ownership_challenge (expires_at);

COMMENT ON TABLE guest_ownership_challenge IS
    '住客凭证绑定的归属挑战。验证码 SHA-256 存储；防线=哈希+尝试上限+短过期三件套；'
    '挑战归属发起者（account_id），confirm 校验归属防接管';
