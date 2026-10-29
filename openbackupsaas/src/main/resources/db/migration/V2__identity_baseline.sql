-- =====================================================================
-- V2 身份与授权基线：员工账号 / 角色 / 权限点 / 门店数据范围 / 会话
-- （S1-03，迁移批次 DB-A 剩余部分）
--
-- 约定延续 V1：
--   1. 每张业务表都带 tenant_id；(id, tenant_id) 复合唯一支撑跨表复合外键，
--      使"把 A 租户的员工挂到 B 租户的门店范围"在数据库层直接失败。
--   2. 密码与会话令牌都只存派生值：password_hash 用 BCrypt，token_hash 用 SHA-256 十六进制。
--      任何明文凭据不落库、不进日志。
--   3. permission 是**全局字典**（不带 tenant_id）：权限点是代码里的常量，
--      租户差异体现在"哪个角色拿到哪些权限点"，不是各自定义权限名。
--   4. 会话用**服务端记录**而不是自包含 JWT：撤权必须能立刻生效，
--      不接受"等 token 过期"这种延迟失效。
--   5. 删除一律 RESTRICT：有引用的账号/角色不能被静默删除，先停用再处理。
--   6. 不启用行级安全（RLS）：当前隔离靠"所有查询强制带 tenant_id + 复合外键"，
--      理由与启用条件见 docs/adr/ADR-016-authn-authz-tenant-isolation.md。
-- =====================================================================

CREATE TABLE app_user (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL,
    username      text        NOT NULL,
    display_name  text        NOT NULL,
    password_hash text        NOT NULL,
    status        text        NOT NULL DEFAULT 'ACTIVE',
    version       bigint      NOT NULL DEFAULT 0,
    last_login_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_app_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT uq_app_user_username      UNIQUE (tenant_id, username),
    CONSTRAINT uq_app_user_id_tenant     UNIQUE (id, tenant_id),
    -- 账号只允许小写字母/数字与 . _ -，长度 3–64：避免大小写与空白造成"看起来是同一个人"
    CONSTRAINT ck_app_user_username_fmt  CHECK (username ~ '^[a-z0-9][a-z0-9._-]{2,63}$'),
    CONSTRAINT ck_app_user_display_blank CHECK (length(btrim(display_name)) > 0),
    CONSTRAINT ck_app_user_status        CHECK (status IN ('ACTIVE', 'DISABLED')),
    -- BCrypt 输出固定 60 字符；下限校验防止把明文误写进该列
    CONSTRAINT ck_app_user_password_hash CHECK (password_hash LIKE '$2%' AND length(password_hash) = 60)
);

COMMENT ON TABLE app_user IS '员工账号：登录主体，归属单一租户；账号在租户内唯一，跨租户可重名';
COMMENT ON COLUMN app_user.username IS '登录名，租户内唯一，小写字母/数字与 . _ -';
COMMENT ON COLUMN app_user.password_hash IS 'BCrypt 派生值，禁止存明文或可逆加密';
COMMENT ON COLUMN app_user.status IS 'ACTIVE 可登录；DISABLED 立即禁止新会话，已有会话在下次请求时被拒';

CREATE INDEX idx_app_user_tenant ON app_user (tenant_id);

CREATE TABLE role (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL,
    code       text        NOT NULL,
    name       text        NOT NULL,
    status     text        NOT NULL DEFAULT 'ACTIVE',
    version    bigint      NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_role_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT uq_role_code            UNIQUE (tenant_id, code),
    CONSTRAINT uq_role_id_tenant       UNIQUE (id, tenant_id),
    CONSTRAINT ck_role_code_fmt        CHECK (code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    CONSTRAINT ck_role_name_blank      CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_role_status          CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

COMMENT ON TABLE role IS '租户自定义角色；角色编码租户内唯一（如 ADMIN / MANAGER / FRONT_DESK）';

CREATE INDEX idx_role_tenant ON role (tenant_id);

CREATE TABLE permission (
    code        text PRIMARY KEY,
    name        text NOT NULL,
    description text,
    CONSTRAINT ck_permission_code_fmt CHECK (code ~ '^[a-z]+:[a-z]+$')
);

COMMENT ON TABLE permission IS '全局权限点字典，命名规则「资源:动作」；新增权限点必须同时改本表与代码常量';

CREATE TABLE role_permission (
    tenant_id       uuid NOT NULL,
    role_id         uuid NOT NULL,
    permission_code text NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, permission_code),
    -- 复合外键：角色必须属于同一租户
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id, tenant_id) REFERENCES role (id, tenant_id),
    CONSTRAINT fk_role_permission_perm FOREIGN KEY (permission_code) REFERENCES permission (code)
);

COMMENT ON TABLE role_permission IS '角色与权限点绑定；权限点本身全局共享，授予关系按租户隔离';

CREATE INDEX idx_role_permission_code ON role_permission (permission_code);

CREATE TABLE user_role (
    tenant_id  uuid NOT NULL,
    user_id    uuid NOT NULL,
    role_id    uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id, tenant_id) REFERENCES app_user (id, tenant_id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id, tenant_id) REFERENCES role (id, tenant_id)
);

COMMENT ON TABLE user_role IS '员工与角色绑定；两侧复合外键保证不会跨租户授权';

CREATE INDEX idx_user_role_role ON user_role (role_id);

CREATE TABLE user_hotel_scope (
    tenant_id  uuid NOT NULL,
    user_id    uuid NOT NULL,
    hotel_id   uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, hotel_id),
    CONSTRAINT fk_user_hotel_scope_user  FOREIGN KEY (user_id, tenant_id) REFERENCES app_user (id, tenant_id),
    -- 复合外键：门店必须与账号同租户，跨租户数据范围在数据库层失败
    CONSTRAINT fk_user_hotel_scope_hotel FOREIGN KEY (hotel_id, tenant_id) REFERENCES hotel (id, tenant_id)
);

COMMENT ON TABLE user_hotel_scope IS '门店数据范围：账号能操作哪些门店；"无记录"表示没有任何门店数据权限，不是"全部"';

CREATE INDEX idx_user_hotel_scope_hotel ON user_hotel_scope (hotel_id);

CREATE TABLE user_session (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     uuid        NOT NULL,
    user_id       uuid        NOT NULL,
    token_hash    text        NOT NULL,
    issued_at     timestamptz NOT NULL,
    expires_at    timestamptz NOT NULL,
    revoked_at    timestamptz,
    revoke_reason text,
    last_seen_at  timestamptz,
    version       bigint      NOT NULL DEFAULT 0,
    CONSTRAINT uq_user_session_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_user_session_user FOREIGN KEY (user_id, tenant_id) REFERENCES app_user (id, tenant_id),
    CONSTRAINT ck_user_session_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_user_session_expiry     CHECK (expires_at > issued_at),
    CONSTRAINT ck_user_session_revoke     CHECK (revoked_at IS NULL OR revoked_at >= issued_at)
);

COMMENT ON TABLE user_session IS '服务端会话；令牌明文只在登录响应里出现一次，库内只存 SHA-256 十六进制';
COMMENT ON COLUMN user_session.revoke_reason IS 'LOGOUT 主动登出；PASSWORD_CHANGED 改密回收；ADMIN_REVOKED 管理撤权';
COMMENT ON COLUMN user_session.last_seen_at IS '最近一次通过校验的时间；当前仅在登录与改密时写入，未做逐请求更新（见 S1-03 记录）';

CREATE INDEX idx_user_session_user ON user_session (user_id, tenant_id);
CREATE INDEX idx_user_session_expiry ON user_session (expires_at) WHERE revoked_at IS NULL;

-- 权限点字典：本轮只登记已存在或即将使用的权限点。
-- 命名规则「资源:动作」由 ck_permission_code_fmt 强制，避免出现 read_org 这类风格混杂。
INSERT INTO permission (code, name, description) VALUES
    ('org:read',         '读取组织', '读取租户/集团/门店档案'),
    ('org:write',        '维护组织', '创建与修改租户/集团/门店'),
    ('identity:read',    '读取身份', '读取员工、角色与门店数据范围'),
    ('identity:write',   '维护身份', '创建与修改员工、角色与门店数据范围'),
    ('reservation:read', '读取预订', '查看订单、占用与房态'),
    ('reservation:write','操作预订', '创建/确认/取消订单与占用')
ON CONFLICT (code) DO NOTHING;
