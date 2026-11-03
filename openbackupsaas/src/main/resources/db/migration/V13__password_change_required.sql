-- =====================================================================
-- V13 首次登录强制改密（S3-02 续）
--
-- 为什么需要这一列：
-- 员工账号由管理员开通，初始口令只有管理员和员工两个人知道。如果不强制更换，
-- 这个口令会一直有效 —— 也就是说管理员**长期持有**该员工账号的可用凭据，
-- 出了事从日志上分辨不出到底是谁操作的。强制改密把"管理员知道的临时口令"
-- 和"员工自己的口令"切成两段，责任才落得下来。
--
-- 两条设计约束：
--   1. 标记记在账号上而不是会话上：员工可能在改密前从多台设备登录，
--      标记在账号才能保证每一台都被拦住。
--   2. 默认值 false（存量账号不受影响）。迁移只加列，不改任何既有账号的行为 ——
--      否则上线瞬间所有在用账号都会被挡在改密页前。
--      新建员工由应用层显式置 true（见 IdentityAdminService#createUser）。
--
-- 关于种子里的超级管理员 admin/admin：
-- 它是**开发与联调专用**（docs/operations/super-admin-seed.md 已标注），
-- 刻意不置 true，否则文档里的快速启动与 CI 都要多一步交互。
-- 生产环境请用 seed-super-admin.sh --random 生成随机口令，不需要强制改密兜底。
-- =====================================================================

ALTER TABLE app_user
    ADD COLUMN must_change_password boolean NOT NULL DEFAULT false;

-- 部分索引：正常情况下绝大多数账号为 false，只为"待改密"的少数行建索引，
-- 让"还有谁没改初始口令"这类运维查询不必全表扫。
CREATE INDEX idx_app_user_must_change_password
    ON app_user (tenant_id)
    WHERE must_change_password;

COMMENT ON COLUMN app_user.must_change_password IS
    '首次登录必须改密：为真时除 /auth/me、/auth/password、/auth/logout 外一律拒绝；改密成功即置 false';
