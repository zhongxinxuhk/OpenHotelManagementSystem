-- =====================================================================
-- 超级管理员初始化（admin / admin）
--
-- 用法（需连接业务库，且在 Flyway 迁移完成之后执行）：
--   psql -h 127.0.0.1 -p 55432 -U pms_app -d pms -f scripts/seed-super-admin.sql
--   或用迁移账号（可写库）：psql -U pms_migrator -d pms -f scripts/seed-super-admin.sql
--
-- 【为什么不放进 Flyway 迁移链】
--   V1—V12 描述的是**表结构**，随应用启动自动执行，会跑到每一个环境。
--   本脚本创建的是一个**知道弱口令的账号**：
--     · 放进迁移链 = 每个环境（含预发、生产）自动多出一个 admin/admin，
--       而口令强度校验只发生在改密接口（AuthenticationService#requireAcceptablePassword，
--       长度下限 12），登录接口并不校验长度 —— 也就是说这条弱口令在任何环境都能直接登录；
--     · 弱口令进版本库 = 凭据入库，等同于泄漏。
--   所以它留在 scripts/ 下作为**显式动作**：谁执行、什么时候执行，由人决定。
--   生产环境若需要初始管理员，应当在执行后立即改密（长度 ≥12 位），
--   或改用 scripts/seed-super-admin.sh 传入一次性随机口令。
--
-- 【脚本做什么】
--   1. 建租户 pms-admin（可重复执行，已存在则复用）；
--   2. 建门店 hq（同样幂等）；
--   3. 建角色 SUPER_ADMIN，授予**字典里已登记的全部权限点**；
--   4. 建账号 admin，把门店 hq 纳入其数据范围；
--   5. 写一条说明到注释里，并回显账号信息。
--
-- 【幂等】全部使用 ON CONFLICT / 条件插入，重复执行不会报错，
--   也不会覆盖已存在账号的密码（改密请走 /api/v1/auth/password）。
--
-- 【安全说明】
--   · 口令哈希用 BCrypt（强度 10），与后端 BCryptPasswordEncoder 完全一致；
--     库里**不存明文**，明文只在本次交付说明中出现一次。
--   · 该哈希由项目自身的编码器生成并验证（见 scripts/seed-super-admin.sh 的自检步骤），
--     不是从别处抄来的——抄来的哈希可能用不同的字符集或前缀，导致登录永远失败。
--   · 权限点是**运行时求值**：从 permission 表全量授予，因此将来新增权限点后
--     重跑本脚本即可补齐，不必手工维护一张越来越长的清单。
-- =====================================================================

\set ON_ERROR_STOP on

BEGIN;

-- ---------------------------------------------------------------------
-- 1. 租户
-- ---------------------------------------------------------------------
-- code 需满足 ck_tenant_code_fmt：小写字母/数字开头，可含连字符，2–32 位。
INSERT INTO tenant (code, name, status)
VALUES ('pms-admin', 'PMS 超级管理员租户', 'ACTIVE')
ON CONFLICT (code) DO NOTHING;

-- ---------------------------------------------------------------------
-- 2. 门店（超级管理员需要至少一间门店，否则前端拿不到任何可操作对象）
-- ---------------------------------------------------------------------
-- timezone 用 IANA 名称；这里取 Australia/Sydney，与门店所在时区一致。
INSERT INTO hotel (tenant_id, code, name, timezone, status)
SELECT t.id, 'hq', '总部门店', 'Australia/Sydney', 'ACTIVE'
FROM tenant t
WHERE t.code = 'pms-admin'
ON CONFLICT (tenant_id, code) DO NOTHING;

-- ---------------------------------------------------------------------
-- 3. 角色：授予字典中已登记的全部权限点
-- ---------------------------------------------------------------------
-- 为什么"全量授予"而不是列清单：权限字典由各次迁移逐步扩充（V2/V3/V6/V7/V11/V12），
-- 手工维护清单必然漂移——新增权限点时忘了加，超级管理员就会莫名其妙少一块能力。
-- 从 permission 表 SELECT 意味着"字典里有的就是有的"，永远同步。
INSERT INTO role (tenant_id, code, name, status)
SELECT t.id, 'SUPER_ADMIN', '超级管理员', 'ACTIVE'
FROM tenant t
WHERE t.code = 'pms-admin'
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO role_permission (tenant_id, role_id, permission_code)
SELECT t.id, r.id, p.code
FROM tenant t
JOIN role r ON r.tenant_id = t.id AND r.code = 'SUPER_ADMIN'
CROSS JOIN permission p
WHERE t.code = 'pms-admin'
ON CONFLICT (role_id, permission_code) DO NOTHING;

-- ---------------------------------------------------------------------
-- 4. 账号：admin
-- ---------------------------------------------------------------------
-- 哈希说明（BCrypt 强度 10，与 BCryptPasswordEncoder 默认一致）：
--   $2a$10$nnsm474B8Jjg6B8GdPu3G.Fsl0tZqyBtY/5zK4TbnPS.RRl7gX6HO
-- 对应明文：admin
--
-- 满足 ck_app_user_password_hash（$2 前缀 + 恰好 60 字符），数据库不会拒收。
-- 满足 ck_app_user_username_fmt（admin：首字母 a，其余 dmin 在允许集内）。
--
-- ON CONFLICT DO NOTHING：账号已存在时不覆盖密码_hash。
--   这一条很重要——重跑脚本不应该把运维改过的密码打回 admin，
--   也不应该用同一个盐重新哈希（那等于静默改口令）。
INSERT INTO app_user (tenant_id, username, display_name, password_hash, status)
SELECT t.id, 'admin', '超级管理员',
       '$2a$10$nnsm474B8Jjg6B8GdPu3G.Fsl0tZqyBtY/5zK4TbnPS.RRl7gX6HO', 'ACTIVE'
FROM tenant t
WHERE t.code = 'pms-admin'
ON CONFLICT (tenant_id, username) DO NOTHING;

-- ---------------------------------------------------------------------
-- 5. 角色绑定 + 门店数据范围
-- ---------------------------------------------------------------------
-- 角色绑定：
INSERT INTO user_role (tenant_id, user_id, role_id)
SELECT t.id, u.id, r.id
FROM tenant t
JOIN app_user u ON u.tenant_id = t.id AND u.username = 'admin'
JOIN role r ON r.tenant_id = t.id AND r.code = 'SUPER_ADMIN'
WHERE t.code = 'pms-admin'
ON CONFLICT (user_id, role_id) DO NOTHING;

-- 门店范围：授予该租户下的**全部**门店。
-- 复合外键 fk_user_hotel_scope_hotel 保证门店与账号同租户，跨租户挂接在库层失败。
INSERT INTO user_hotel_scope (tenant_id, user_id, hotel_id)
SELECT t.id, u.id, h.id
FROM tenant t
JOIN app_user u ON u.tenant_id = t.id AND u.username = 'admin'
JOIN hotel h ON h.tenant_id = t.id
WHERE t.code = 'pms-admin'
ON CONFLICT (user_id, hotel_id) DO NOTHING;

COMMIT;

-- ---------------------------------------------------------------------
-- 回显：让执行者当场确认落库结果，而不是"跑完就以为成了"
-- ---------------------------------------------------------------------
\echo ''
\echo '== 超级管理员初始化结果 =='
SELECT t.code AS 租户编码,
       u.username AS 登录名,
       u.display_name AS 显示名,
       u.status AS 账号状态,
       r.code AS 角色,
       (SELECT count(*) FROM role_permission rp WHERE rp.role_id = r.id) AS 已授予权限点数,
       (SELECT count(*) FROM user_hotel_scope s WHERE s.user_id = u.id) AS 可访问门店数
FROM tenant t
JOIN app_user u ON u.tenant_id = t.id AND u.username = 'admin'
JOIN user_role ur ON ur.user_id = u.id AND ur.tenant_id = t.id
JOIN role r ON r.id = ur.role_id
WHERE t.code = 'pms-admin';

\echo ''
\echo '登录方式：POST /api/v1/auth/login'
\echo '  {"tenantCode":"pms-admin","username":"admin","password":"admin"}'
\echo ''
\echo '注意：口令 admin 仅 5 位，弱于系统改密接口的 12 位下限。'
\echo '      生产环境请在首次登录后立即改密，或用 scripts/seed-super-admin.sh 生成一次性随机口令。'
