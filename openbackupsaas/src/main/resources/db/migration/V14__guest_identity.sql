-- =====================================================================
-- V14 住客身份：账户、登录凭证与实名档案（ADR-018，S4-01 前置）
--
-- 需求口径（DEC-10 已定案）：
--   · 登录方式 = 手机号、邮箱，以及第三方（微信、QQ、国家统一身份认证、Apple 等）；
--   · 不论用哪种方式进入，**先实名**；实名凭据是身份证号，**加密存储**；
--   · 设主身份 id，第三方来源作为可绑定凭证挂在主 id 下。
--
-- 三条设计要点：
--   1. **本组表刻意没有 tenant_id** —— 全库唯一例外。住客不属于任何租户。
--      代价是"所有查询强制带租户"这条规则出现了缺口，所以配套三条约束：
--      (a) 跨平面只许单向引用：租户业务表可以引用 guest_account.id，
--          住客表**不得**引用任何租户业务表（否则住客侧被攻破就能顺外键读租户数据）；
--      (b) 住客表不进租户导出与按租户的备份演练清单；
--      (c) 包名 guest / 表名前缀 guest_，便于在代码里一眼认出。
--   2. **凭证与实名分开**：实名验的是人不是号码。换手机号不该影响实名结论，
--      绑微信也不该再验一次身份证。
--   3. **身份证号用"加密 + HMAC"两列**，不用确定性加密：
--      密文用 AES-256-GCM（随机 nonce）保机密性；另存 HMAC-SHA256 做唯一索引。
--      确定性加密虽然也能查重，但会把"两条记录是不是同一个人"泄露给任何能读表的人，
--      而且密钥一旦泄漏，**所有历史记录同时失效**（无法只换新数据）。
--      HMAC 索引同样泄露相等性，但它是单向的，且可用独立密钥轮换。
-- =====================================================================

-- ---------- 主身份 ----------
CREATE TABLE guest_account (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- 昵称/称呼，可为空；真实姓名在 guest_id_verification 里（加密）
    display_name text,
    status       text        NOT NULL DEFAULT 'ACTIVE',
    version      bigint      NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_guest_account_status CHECK (status IN ('ACTIVE', 'CLOSED')),
    CONSTRAINT ck_guest_account_name_blank CHECK (display_name IS NULL OR length(btrim(display_name)) > 0)
);

COMMENT ON TABLE guest_account IS
    '住客主身份。**本表无 tenant_id**（住客不属于任何租户，ADR-018 §2.6）；不得引用任何租户业务表';

-- ---------- 登录凭证（一人多份） ----------
CREATE TABLE guest_credential (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id  uuid        NOT NULL,
    provider    text        NOT NULL,
    subject     text        NOT NULL,
    -- 这个凭证**是否已被证明属于该账户**（验证码 / OAuth 回调）。为空表示仅登记、未验证。
    verified_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_guest_credential_account FOREIGN KEY (account_id) REFERENCES guest_account (id),
    -- 同一凭证只能指向一个账户：这是"抢占别人手机号"的唯一库层防线
    CONSTRAINT uq_guest_credential UNIQUE (provider, subject),
    CONSTRAINT ck_guest_credential_provider
        CHECK (provider IN ('PHONE', 'EMAIL', 'WECHAT', 'QQ', 'CNID_SSO', 'APPLE', 'CORP_SSO')),
    CONSTRAINT ck_guest_credential_subject_blank CHECK (length(btrim(subject)) > 0)
);

CREATE INDEX idx_guest_credential_account ON guest_credential (account_id);

COMMENT ON TABLE guest_credential IS
    '住客登录凭证。唯一 (provider, subject) 保证同一凭证不能绑到两个账户；verified_at 为空表示尚未验证归属';

-- ---------- 实名档案（一人一份） ----------
CREATE TABLE guest_id_verification (
    account_id     uuid        PRIMARY KEY,
    -- 密文格式：'v1:' + base64(nonce(12) | ciphertext | tag(16))；见 GuestPiiCipher
    id_number_enc  text        NOT NULL,
    -- HMAC-SHA256(规范化号码) 的十六进制；只用于判重，不能反推原文
    id_number_hmac text        NOT NULL,
    -- 掩码（前 6 位 + 后 4 位），供客服人工核对，避免每次解密
    id_number_mask text        NOT NULL,
    real_name_enc  text        NOT NULL,
    real_name_mask text        NOT NULL,
    status         text        NOT NULL DEFAULT 'PENDING',
    submitted_at   timestamptz NOT NULL DEFAULT now(),
    verified_at    timestamptz,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_guest_verification_account FOREIGN KEY (account_id)
        REFERENCES guest_account (id) ON DELETE CASCADE,
    -- 同一身份证不能绑两个账户。放在库层而不是服务层：
    -- 服务层的"先查再写"在并发下会漏，唯一索引不会。
    CONSTRAINT uq_guest_id_number_hmac UNIQUE (id_number_hmac),
    CONSTRAINT ck_guest_verification_status CHECK (status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    CONSTRAINT ck_guest_verification_mask CHECK (
        length(id_number_mask) >= 4 AND length(real_name_mask) > 0)
);

COMMENT ON TABLE guest_id_verification IS
    '住客实名档案，一人一份。身份证号只以密文 + HMAC 索引 + 掩码三种形式存在，库内无明文；'
    'status 缺省 PENDING，没有该行即表示 UNVERIFIED（不重复记状态，避免两处真相）';
