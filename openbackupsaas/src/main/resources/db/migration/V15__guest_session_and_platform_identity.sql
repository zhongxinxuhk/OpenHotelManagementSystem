-- =====================================================================
-- V15 平面隔离与会话（ADR-019）+ 人工实名审核 + 平台身份
--
-- 需求口径（2026-11-01 需求方）：
--   · 住客会话**不复用**员工会话，角色完全隔离，杜绝越权；
--   · 实名核验走"权威接口 + 人工审核"双通道，权威接口熔断时人工审核**照常**；
--   · 权威接口地址与参数在管理员后台自定义（对接哪些渠道后续再定），当前先做人工审核。
--
-- 为什么是**三张会话表**而不是一张表加 surface 列：
--   一张表加 `surface` 列，意味着每一条会话查询都必须记得带 `surface = ?`。
--   漏一次，一个住客令牌就能在商家侧被认出来——而这正是"杜绝越权"要防的那件事。
--   这与 ADR-016 拒绝"tenant_id 可空"是同一个论证：隔离要在**结构上**成立，
--   不能依赖每个查询都写对。分表之后，认证代码查错表就是查不到，不存在"忘了带条件"。
--
-- 令牌哈希**域分离**（ADR-019 §2.2）：
--   guest 令牌的库内哈希 = SHA-256("guest-session:v1:" || token)
--   platform 令牌     = SHA-256("platform-session:v1:" || token)
--   员工令牌保持 SHA-256(token)（存量不迁移）。
--   这样同一个令牌串在三张表里的哈希值互不相同，"先查 A 再兜底查 B"这种写法
--   即使被人加进来，也命不中另一平面的会话。
-- =====================================================================

-- ---------- 住客会话（无 tenant_id） ----------
CREATE TABLE guest_session (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    uuid        NOT NULL,
    -- SHA-256("guest-session:v1:" || token) 的十六进制；明文令牌不落库
    token_hash    text        NOT NULL,
    issued_at     timestamptz NOT NULL,
    expires_at    timestamptz NOT NULL,
    revoked_at    timestamptz,
    revoke_reason text,
    last_seen_at  timestamptz,
    version       bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_guest_session_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_guest_session_account FOREIGN KEY (account_id) REFERENCES guest_account (id),
    CONSTRAINT ck_guest_session_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_guest_session_expiry     CHECK (expires_at > issued_at),
    CONSTRAINT ck_guest_session_revoke     CHECK (revoked_at IS NULL OR revoked_at >= issued_at)
);

CREATE INDEX idx_guest_session_account ON guest_session (account_id);

COMMENT ON TABLE guest_session IS
    '住客会话。**与 user_session / platform_session 物理分开**，令牌哈希带域前缀（ADR-019）；'
    '本表无 tenant_id——住客不属于任何租户';

-- ---------- 实名人工审核留痕 ----------
-- 审核必须留痕，且**留痕要能独立于档案存在**：档案被重新提交覆盖后，
-- 上一次为什么通过/驳回仍然要查得到。所以这张表不随 guest_id_verification 更新而清空。
CREATE TABLE guest_verification_review (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id     uuid        NOT NULL,
    -- 当时的掩码快照：事后核对"审的是哪一份资料"，不能只看当前档案（可能已被重新提交覆盖）
    id_number_mask text        NOT NULL,
    real_name_mask text        NOT NULL,
    decision       text        NOT NULL,
    -- 决定来源：MANUAL = 人工审核；AUTHORITY:<channelCode> = 权威渠道返回。
    -- 将来接权威渠道时只新增这个取值，状态机不改。
    source         text        NOT NULL,
    -- 人工审核时必填；权威渠道决定时可为空（渠道返回码写在 detail）
    reason         text,
    detail         text,
    -- 人工审核的执行者；权威渠道决定时为空（system）
    reviewer_id    uuid,
    reviewer_name  text,
    decided_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_guest_review_account FOREIGN KEY (account_id)
        REFERENCES guest_account (id) ON DELETE CASCADE,
    CONSTRAINT ck_guest_review_decision CHECK (decision IN ('VERIFIED', 'REJECTED')),
    CONSTRAINT ck_guest_review_source   CHECK (source = 'MANUAL' OR source LIKE 'AUTHORITY:%'),
    CONSTRAINT ck_guest_review_masks    CHECK (length(id_number_mask) >= 4 AND length(real_name_mask) > 0),
    -- 人工决定必须有理由：没有理由的"通过"事后无法复核，等于没有审核
    CONSTRAINT ck_guest_review_manual_reason CHECK (source <> 'MANUAL' OR length(btrim(reason)) > 0),
    CONSTRAINT ck_guest_review_manual_actor  CHECK (source <> 'MANUAL' OR reviewer_id IS NOT NULL)
);

CREATE INDEX idx_guest_review_account ON guest_verification_review (account_id, decided_at DESC);

COMMENT ON TABLE guest_verification_review IS
    '住客实名决定留痕（人工审核 / 权威渠道）。档案被重新提交覆盖后，历史决定仍可追溯；'
    '人工决定强制要求 reason 与 reviewer';

-- ---------- 权威核验渠道配置 + 熔断状态 ----------
-- 需求："权威接口支持在管理员后台自定义设置，后续再说明对接哪些权威核验接口"。
-- 所以这张表是**配置载体**，本轮不接任何具体厂商；接了之后按 code 找实现。
CREATE TABLE guest_verification_channel (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    code                 text        NOT NULL,
    name                 text        NOT NULL,
    -- AUTHORITY = 权威核验接口；MANUAL = 人工审核（永远可用，不受熔断影响）
    kind                 text        NOT NULL,
    -- 仅 AUTHORITY 使用：接口地址、超时、以及**凭据引用**（不是凭据本身）
    endpoint             text,
    credential_ref       text,
    timeout_ms           integer     NOT NULL DEFAULT 3000,
    -- 排序：数字小的先试。人工渠道固定排最后（靠 priority 大值实现，不写死逻辑）
    priority             integer     NOT NULL DEFAULT 100,
    status               text        NOT NULL DEFAULT 'ACTIVE',
    -- 熔断状态：CLOSED 正常放行 / OPEN 熔断中（直接跳过，落人工）/ HALF_OPEN 试探一次
    circuit_state        text        NOT NULL DEFAULT 'CLOSED',
    consecutive_failures integer     NOT NULL DEFAULT 0,
    opened_until         timestamptz,
    last_failure_at      timestamptz,
    last_failure_reason  text,
    version              bigint      NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_guest_channel_code UNIQUE (code),
    CONSTRAINT ck_guest_channel_kind   CHECK (kind IN ('AUTHORITY', 'MANUAL')),
    CONSTRAINT ck_guest_channel_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_guest_channel_circuit CHECK (circuit_state IN ('CLOSED', 'OPEN', 'HALF_OPEN')),
    CONSTRAINT ck_guest_channel_name_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_guest_channel_timeout CHECK (timeout_ms BETWEEN 200 AND 30000),
    -- 权威渠道必须有 endpoint；人工渠道必须没有（否则会让人以为它有外部依赖）
    CONSTRAINT ck_guest_channel_endpoint CHECK (
        (kind = 'AUTHORITY' AND endpoint IS NOT NULL AND length(btrim(endpoint)) > 0)
        OR (kind = 'MANUAL' AND endpoint IS NULL)),
    -- 熔断中的渠道必须记着什么时候可以再试，否则会出现"永远 OPEN"这种没人敢动的状态
    CONSTRAINT ck_guest_channel_open_until CHECK (
        circuit_state <> 'OPEN' OR opened_until IS NOT NULL)
);

COMMENT ON TABLE guest_verification_channel IS
    '实名核验渠道配置（权威接口 + 人工）。熔断状态存在这里，但**人工审核路径不读本表**——'
    '这是"权威接口熔断时人工照常"的实现保证；凭据只存引用，不存明文';

-- 人工渠道是**结构性存在**的，不靠运维记得去加：没有它就没有实名结论的兜底路径。
INSERT INTO guest_verification_channel (code, name, kind, endpoint, priority)
VALUES ('MANUAL_REVIEW', '人工审核', 'MANUAL', NULL, 999)
ON CONFLICT (code) DO NOTHING;

-- ---------- 平台身份（无 tenant_id，与 app_user 完全隔离） ----------
-- 为什么不复用 app_user（把 tenant_id 改成可空）：
--   ADR-016 的前提是"不存在不带租户的员工查询"。一旦可空，所有租户查询都要写
--   `tenant_id = ? OR tenant_id IS NULL`——隔离从"编译期保证"退化成"每个查询记得写"。
-- 为什么不伪造一个"平台租户"：
--   那会让平台账号被租户边界挡住，跨租户能力反而没了；而且租户列表里会多出一个假租户。
CREATE TABLE platform_user (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    username             text        NOT NULL,
    display_name         text        NOT NULL,
    password_hash        text        NOT NULL,
    status               text        NOT NULL DEFAULT 'ACTIVE',
    must_change_password boolean     NOT NULL DEFAULT false,
    last_login_at        timestamptz,
    version              bigint      NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    -- 平台账号是全局的：没有租户前缀，用户名必须全局唯一
    CONSTRAINT uq_platform_user_username UNIQUE (username),
    CONSTRAINT ck_platform_user_username_fmt CHECK (username ~ '^[a-z][a-z0-9._-]{2,63}$'),
    CONSTRAINT ck_platform_user_display_blank CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT ck_platform_user_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_platform_user_password_hash CHECK (password_hash LIKE '$2%' AND length(password_hash) = 60)
);

COMMENT ON TABLE platform_user IS
    '平台雇员。**无 tenant_id**，与 app_user 完全隔离（ADR-017 §2.1、ADR-019 §2.1）；'
    '平台权限走 platform_action，不复用租户内的 role/permission 字典';

CREATE TABLE platform_session (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid        NOT NULL,
    -- SHA-256("platform-session:v1:" || token)
    token_hash    text        NOT NULL,
    issued_at     timestamptz NOT NULL,
    expires_at    timestamptz NOT NULL,
    revoked_at    timestamptz,
    revoke_reason text,
    last_seen_at  timestamptz,
    version       bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_platform_session_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_platform_session_user FOREIGN KEY (user_id) REFERENCES platform_user (id),
    CONSTRAINT ck_platform_session_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_platform_session_expiry     CHECK (expires_at > issued_at),
    CONSTRAINT ck_platform_session_revoke     CHECK (revoked_at IS NULL OR revoked_at >= issued_at)
);

CREATE INDEX idx_platform_session_user ON platform_session (user_id);

-- ---------- 平台动作字典 ----------
-- 与租户 permission 表**分表**：两者的授权模型不同（租户是"资源:动作"且带门店范围，
-- 平台是"平面动作"且无数据范围），混在一张表里迟早有人给平台账号授一个租户权限点。
CREATE TABLE platform_action (
    code        text PRIMARY KEY,
    name        text NOT NULL,
    description text,
    CONSTRAINT ck_platform_action_code CHECK (code ~ '^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$')
);

INSERT INTO platform_action (code, name, description) VALUES
    ('guest.verification.read',   '查看实名审核队列',
     '只看到证件与姓名的掩码；看不到完整证件号（解密走独立受审计流程）'),
    ('guest.verification.review', '执行实名人工审核',
     '通过或驳回实名申请，必须填写理由；该动作写入 guest_verification_review 留痕'),
    ('guest.channel.read',        '查看核验渠道配置', '权威核验渠道与熔断状态；只读'),
    ('guest.channel.write',       '维护核验渠道配置',
     '新增/修改权威核验渠道地址与参数、启停、手动复位熔断（需原因）')
ON CONFLICT (code) DO NOTHING;

COMMENT ON TABLE platform_action IS
    '平台动作字典。刻意不含"跨租户查看租户业务数据"这类动作——平台不跨租户（DEC-02）';

CREATE TABLE platform_user_action (
    platform_user_id uuid        NOT NULL,
    action_code      text        NOT NULL,
    granted_at       timestamptz NOT NULL DEFAULT now(),
    granted_by       uuid,
    PRIMARY KEY (platform_user_id, action_code),
    CONSTRAINT fk_platform_user_action_user FOREIGN KEY (platform_user_id)
        REFERENCES platform_user (id) ON DELETE CASCADE,
    -- 外键到字典：动作码拼错会直接写入失败，而不是"授了一个不存在的权限、看起来授了但没用"
    CONSTRAINT fk_platform_user_action_action FOREIGN KEY (action_code)
        REFERENCES platform_action (code)
);

COMMENT ON TABLE platform_user_action IS
    '平台动作直授。**不引入平台角色层**：平台账号数量是个位数到十几，'
    '多一层分组只增加配错机会；审计要的是"谁在何时做了什么"，直授反而更直接。'
    '数量增长后再引入角色分组，届时本表语义不变（仍是最终生效的动作集合）';
