-- =====================================================================
-- V19 住客忘记密码的重置挑战（R-20261103-01）
--
-- 邮箱验证码**重置口令**的挑战表。第三张挑战表（绑定 V17 / 登录 V18 / 重置本表），
-- 结构与前两者同构：SHA-256 哈希存储 + 5 次尝试上限 + 10 分钟过期 + 一次性消费。
-- 分表而不是复用，除 R-07 已记录的"可用性不联动"外，本表多一条理由：
--
--   **码按用途绑定（抗钓鱼）**：登录挑战的码只能完成登录，重置挑战的码只能
--   重置口令。若两类挑战共用一张表，攻击者发起"登录挑战"后诱骗用户读出
--   邮件里的码（邮件写的是"登录验证码"），即可拿去完成**重置口令**——
--   用户对"我在登录"与"我在改密码"的心智警觉完全不同。
--   分表后 challengeId 只在对应流程内有效，且重置邮件正文会明示操作类型
--   （ChallengeDelivery.purpose，R-20261103-01）。
--
-- account_id 可空、无外键：与登录挑战同——匿名发起（忘记密码 = 没有身份），
-- 凭证定位发生在码校验通过之后。
-- =====================================================================

CREATE TABLE guest_password_reset_challenge (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  uuid,
    provider    text        NOT NULL,
    subject     text        NOT NULL,
    code_hash   text        NOT NULL,
    expires_at  timestamptz NOT NULL,
    attempts    integer     NOT NULL DEFAULT 0,
    consumed_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_guest_pwd_reset_provider CHECK (provider = 'EMAIL'),
    CONSTRAINT ck_guest_pwd_reset_subject_blank CHECK (length(btrim(subject)) > 0),
    CONSTRAINT ck_guest_pwd_reset_code_hash CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_guest_pwd_reset_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_guest_pwd_reset_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_guest_pwd_reset_consumed CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);

CREATE INDEX idx_guest_pwd_reset_subject ON guest_password_reset_challenge (provider, subject);
CREATE INDEX idx_guest_pwd_reset_expires ON guest_password_reset_challenge (expires_at);

COMMENT ON TABLE guest_password_reset_challenge IS
    '住客忘记密码的重置挑战（匿名发起，第三张挑战表）。防爆破三件套同 V17/V18；'
    '码按用途绑定：本表的码只能重置口令，不能登录（抗钓鱼）；'
    '枚举防护：未注册邮箱也照发码，confirm 统一文案';
