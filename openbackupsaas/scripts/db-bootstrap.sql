-- =====================================================================
-- 数据库角色与授权（迁移账号 / 应用账号分权）
--
-- 用法（由 scripts/dev-db.sh 或 CI 调用，需超级用户连接）：
--   psql -h 127.0.0.1 -p <port> -U postgres -d postgres \
--        -v dbname=pms -f scripts/db-bootstrap.sql
--
-- 设计意图：
--   pms_migrator —— 迁移账号，拥有表与索引（DDL），只在部署/启动时使用；
--   pms_app      —— 应用账号，仅 SELECT/INSERT/UPDATE/DELETE，不能建表、不能改结构。
--   两个账号都不是超级用户。这样"应用服务被攻破"不等于"可以改库结构"。
--
-- 幂等：重复执行不会报错，已存在的角色与授权保持不变。
-- =====================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_migrator') THEN
        CREATE ROLE pms_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pms_app') THEN
        CREATE ROLE pms_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
    END IF;
END
$$;

-- CREATE DATABASE 不能在事务块/DO 内执行，用 \gexec 条件创建
SELECT format('CREATE DATABASE %I OWNER pms_migrator', :'dbname')
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'dbname')
\gexec

\connect :dbname

-- 迁移账号：要能建表（DDL）。
-- 不能依赖"建库人恰好是 pms_migrator"这个巧合：PostgreSQL 15+ 里 public schema
-- 的 CREATE 默认只属于库 owner（pg_database_owner），dev 环境碰巧由本脚本以
-- OWNER pms_migrator 建库才可用；换一种建库方式（如 Docker 镜像的 POSTGRES_DB，
-- owner 是 postgres）迁移就会 permission denied（R-20261030-21 实测踩到）。
-- 显式授予，幂等无害。
GRANT CREATE, USAGE ON SCHEMA public TO pms_migrator;

-- 应用账号：只有 DML
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM pms_app;
GRANT USAGE ON SCHEMA public TO pms_app;
GRANT CONNECT ON DATABASE :"dbname" TO pms_app;

-- 迁移账号后续新建的表/序列，自动给应用账号 DML 权限
ALTER DEFAULT PRIVILEGES FOR ROLE pms_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pms_app;
ALTER DEFAULT PRIVILEGES FOR ROLE pms_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO pms_app;
