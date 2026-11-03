-- =====================================================================
-- V18 住客验证码登录挑战（ADR-019 §2.10 续，R-20261102-07）
--
-- 邮箱+验证码**登录**的挑战表。与 V17 的归属挑战（绑定用）分表，
-- 不是复用，理由写在表注释里，这里只补充最关键的一条：
--
--   绑定挑战是"归属验证"：发起人已登录，挑战必须绑定 account_id，
--   confirm 校验"这个挑战是我的"（防接管）。
--   登录挑战是"身份证明"：发起人**匿名**（登录前没有身份），
--   account_id 无法归属——它必须可空；凭证定位发生在**码校验通过之后**。
--
-- 把两类挑战塞进一张表会出现两个都难受的形状：
--   · account_id 可空会让绑定挑战的 NOT NULL 防线失效；
--   · 强行 NOT NULL 会让登录挑战没地方写。
-- 分表让各自的约束保持最严。
--
-- 防爆破三件套与 V17 完全对齐：SHA-256 哈希存储、5 次尝试上限、
-- 10 分钟过期、一次性消费（consumed_at）。外加一条登录特有的：
-- **邮箱未注册也照发码**（响应形态与已注册一致）——confirm 时
-- "码错"与"邮箱未注册"统一文案，登录接口不构成邮箱枚举器。
-- =====================================================================

CREATE TABLE guest_login_challenge (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- 登录前无法归属账户：登录挑战按 (provider, subject) 定位凭证，
    -- 这一步发生在码校验通过之后（见 GuestCodeLoginService）。
    account_id  uuid,
    provider    text        NOT NULL,
    subject     text        NOT NULL,
    -- SHA-256(6 位数字码) 的十六进制；明文只在发送通道里，不落库
    code_hash   text        NOT NULL,
    expires_at  timestamptz NOT NULL,
    attempts    integer     NOT NULL DEFAULT 0,
    consumed_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- 刻意**不建外键**：account_id 是事后回填的线索（发了码但从未成功登录的
    -- 挑战连线索都没有）。外键会让"删除未注册邮箱的挑战行"依赖账户表，
    -- 而这些行本就与账户无关。
    CONSTRAINT ck_guest_login_challenge_provider CHECK (provider = 'EMAIL'),
    CONSTRAINT ck_guest_login_challenge_subject_blank CHECK (length(btrim(subject)) > 0),
    CONSTRAINT ck_guest_login_challenge_code_hash CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_guest_login_challenge_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_guest_login_challenge_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_guest_login_challenge_consumed CHECK (consumed_at IS NULL OR consumed_at >= created_at)
);

-- 定位凭证用（码校验通过后按 provider+subject 找账户）
CREATE INDEX idx_guest_login_challenge_subject ON guest_login_challenge (provider, subject);
-- 清理过期挑战用
CREATE INDEX idx_guest_login_challenge_expires ON guest_login_challenge (expires_at);

COMMENT ON TABLE guest_login_challenge IS
    '住客验证码登录的挑战（匿名发起，与绑定归属挑战分表）。'
    '防爆破三件套同 V17：SHA-256 存储 + 5 次尝试上限 + 10 分钟过期 + 一次性消费。'
    '枚举防护：邮箱未注册也照发码，confirm 统一文案';
