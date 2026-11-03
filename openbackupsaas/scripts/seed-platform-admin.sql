-- =====================================================================
-- 平台管理员初始化（ADR-019，R-20261101-31）
--
-- 用途：创建第一个平台管理员账号，授予全部平台动作。
-- 没有这个账号，实名人工审核与核验渠道配置就没有执行者。
--
-- 为什么不进 Flyway 迁移链：与超级管理员种子（seed-super-admin.sql）同样的理由——
-- 迁移链会跑到每个环境（含生产），等于给每个环境自动发一个固定口令的管理账号。
-- 平台账号影响面是全局的（审实名、改核验渠道），更不能这么做。
--
-- 运行方式（见 seed-platform-admin.sh）：
--   ./scripts/seed-platform-admin.sh                 # 口令取 PMS_PLATFORM_ADMIN_PASSWORD
--   ./scripts/seed-platform-admin.sh --verify-only   # 只做哈希自检
--
-- 幂等：可重复执行。已存在的账号不会被覆盖（改口令请走改密流程）。
-- =====================================================================

-- ---------- 平台管理员 ----------
-- 口令哈希由 seed-platform-admin.sh 用项目自身的 BCryptPasswordEncoder 生成
-- （\gset 从 psql 变量取值；直接用 psql 执行时请设置 :platform_password_hash）。
INSERT INTO platform_user (id, username, display_name, password_hash, status, must_change_password)
VALUES (gen_random_uuid(),
        COALESCE(:'platform_username', 'platform-admin'),
        '平台管理员',
        :'platform_password_hash',
        'ACTIVE',
        true)
ON CONFLICT (username) DO NOTHING;

-- 授予当前全部平台动作（4 个）。将来新增动作后重跑本脚本即可补齐——
-- 与超级管理员"全量授予"同一策略：种子的职责是"不漏"，而不是"按需挑"。
INSERT INTO platform_user_action (platform_user_id, action_code)
SELECT u.id, a.code
FROM platform_user u
CROSS JOIN platform_action a
WHERE u.username = COALESCE(:'platform_username', 'platform-admin')
  AND u.status = 'ACTIVE'
ON CONFLICT (platform_user_id, action_code) DO NOTHING;

-- ---------- 结果核对（应返回动作条数 = platform_action 全量） ----------
SELECT u.username,
       u.must_change_password,
       count(pa.action_code) AS granted_actions,
       (SELECT count(*) FROM platform_action) AS total_actions
FROM platform_user u
LEFT JOIN platform_user_action pa ON pa.platform_user_id = u.id
WHERE u.username = COALESCE(:'platform_username', 'platform-admin')
GROUP BY u.username, u.must_change_password;
