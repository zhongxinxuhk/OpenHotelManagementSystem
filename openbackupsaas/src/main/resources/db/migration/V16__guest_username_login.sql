-- =====================================================================
-- V16 住客用户名登录 + 登录方式开关（ADR-019 §2.9，R-20261101-32）
--
-- 需求口径（2026-11-01 需求方）：
--   · 住客登录**再加一个"用户名"方式**，先支持基础的用户名+密码；
--   · 手机号、邮箱等登录项**在管理员控制台去设置**。
--
-- 两条设计要点：
--   1. **密码是凭证的属性，不是账户的属性**。用户名+密码是一对，
--      放在 guest_credential 上（provider=USERNAME, subject=用户名, secret_hash=BCrypt），
--      与"换手机号不影响实名结论"是同一条分界。其它 provider 的 secret_hash 必须为空——
--      微信/QQ 的凭据在外面（OAuth token），库里有 secret 反而让人误以为我们在存它。
--   2. **登录方式的可用性 = 实现状态（代码）∧ 策略开关（控制台）**。
--      两个条件缺一不可：策略表即使把 PHONE 打开，代码没有实现照样"未开放"；
--      反过来，实现了的 USERNAME 被策略关掉也进不来。这样"接通了新通道"永远是
--      一次代码发布，而"暂时关掉某个方式"永远是控制台的一个开关，两者不会混。
-- =====================================================================

-- ---------- 凭证：加密码列 + 扩 provider ----------
ALTER TABLE guest_credential ADD COLUMN secret_hash text;

ALTER TABLE guest_credential DROP CONSTRAINT ck_guest_credential_provider;
-- ⚠️ 与 GuestProvider 枚举保持同步：增删枚举值必须同时改这里与代码
ALTER TABLE guest_credential ADD CONSTRAINT ck_guest_credential_provider
    CHECK (provider IN ('PHONE', 'EMAIL', 'WECHAT', 'QQ', 'CNID_SSO', 'APPLE', 'CORP_SSO', 'USERNAME'));

-- USERNAME 凭证必须有 BCrypt 哈希（60 字符）；其它 provider 必须为空。
-- 没有这个 CHECK，"给微信凭证顺手存个密码"这类写法不会报任何错，
-- 而它意味着我们把别人的凭据当成了自己的。
ALTER TABLE guest_credential ADD CONSTRAINT ck_guest_credential_secret
    CHECK ((provider = 'USERNAME')
           = (secret_hash IS NOT NULL AND secret_hash LIKE '$2%' AND length(secret_hash) = 60));

COMMENT ON COLUMN guest_credential.secret_hash IS
    '仅 USERNAME 凭证使用：BCrypt(密码)。其它 provider 必须为空（CHECK 兜底）——'
    '第三方凭据在外面，我们不存也不该存';

-- ---------- 登录方式策略（管理员控制台） ----------
CREATE TABLE guest_login_policy (
    provider   text        PRIMARY KEY,
    enabled    boolean     NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- 操作者的平台用户名；种子行为 'system'
    updated_by text        NOT NULL DEFAULT 'system',
    CONSTRAINT ck_guest_login_policy_provider
        CHECK (provider IN ('PHONE', 'EMAIL', 'WECHAT', 'QQ', 'CNID_SSO', 'APPLE', 'CORP_SSO', 'USERNAME'))
);

COMMENT ON TABLE guest_login_policy IS
    '住客登录方式开关（管理员控制台）。可用性 = 实现状态（代码）∧ 本表开关；'
    'provider 集合与 guest_credential 的 CHECK、GuestProvider 枚举三处保持同步';

-- 种子：用户名登录先开放；其余方式在接通外部通道前保持关闭。
-- 这些行是**结构性配置**（迁移写入），与 MANUAL_REVIEW 渠道同性质，
-- 已加入 DatabaseCleaner 的保留清单。
INSERT INTO guest_login_policy (provider, enabled, updated_by) VALUES
    ('USERNAME', true,  'system'),
    ('PHONE',    false, 'system'),
    ('EMAIL',    false, 'system'),
    ('WECHAT',   false, 'system'),
    ('QQ',       false, 'system'),
    ('CNID_SSO', false, 'system'),
    ('APPLE',    false, 'system'),
    ('CORP_SSO', false, 'system')
ON CONFLICT (provider) DO NOTHING;

-- ---------- 平台动作字典：策略管理 ----------
INSERT INTO platform_action (code, name, description) VALUES
    ('guest.authpolicy.read',  '查看住客登录方式开关',
     '各登录方式的实现状态与启用状态；只读'),
    ('guest.authpolicy.write', '设置住客登录方式开关',
     '启用或停用某个登录方式。未实现的方式打开后对外仍是"未开放"'
     '（可用性 = 实现 ∧ 开关）')
ON CONFLICT (code) DO NOTHING;
