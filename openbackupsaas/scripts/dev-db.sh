#!/usr/bin/env bash
#
# 本地开发数据库（一次性、可丢弃）
#
# 为什么不用系统里已有的 PostgreSQL 集群：本脚本只操作独立数据目录与非默认端口，
# 不触碰用户既有集群与配置；数据目录在 /tmp 下，重启即消失，重跑脚本即可恢复。
#
# 用法：
#   ./scripts/dev-db.sh start    # 初始化（首次）+ 启动 + 建角色/库/授权
#   ./scripts/dev-db.sh stop
#   ./scripts/dev-db.sh status
#   ./scripts/dev-db.sh reset    # 停库并删除数据目录（破坏性，需显式执行）
#
# 连接信息（默认）：
#   应用账号   pms_app       /  空密码（仅本机 trust 认证）
#   迁移账号   pms_migrator  /  空密码
#   端口       55432   （避开系统默认 5432）
#
set -euo pipefail

PGDATA="${PMS_DEV_PGDATA:-/tmp/pms-pgdata}"
PGPORT="${PMS_DEV_PGPORT:-55432}"
PGHOST_DIR="/tmp/pms-pgsock"
DB_NAME="${PMS_DEV_DB:-pms}"

PG_BIN="${PMS_DEV_PGBIN:-$(brew --prefix postgresql@17 2>/dev/null)/bin}"

require_pg() {
  if [[ ! -x "${PG_BIN}/initdb" ]]; then
    echo "找不到 PostgreSQL 17 二进制：${PG_BIN}/initdb" >&2
    echo "请先安装：brew install postgresql@17，或用 PMS_DEV_PGBIN 指定目录" >&2
    exit 1
  fi
}

init_cluster() {
  if [[ -d "${PGDATA}/base" ]]; then
    return 0
  fi
  mkdir -p "${PGHOST_DIR}"
  echo "== 初始化数据目录 ${PGDATA}（端口 ${PGPORT}，仅本机 trust 认证）"
  # trust 仅适用于这个一次性本地集群；任何共享/生产环境必须使用口令 + TLS。
  "${PG_BIN}/initdb" -D "${PGDATA}" -U postgres -E UTF8 --locale=C \
    --auth-local=trust --auth-host=trust >/dev/null
}

start_cluster() {
  if "${PG_BIN}/pg_ctl" -D "${PGDATA}" status >/dev/null 2>&1; then
    echo "== 已在运行：${PGDATA}"
    return 0
  fi
  mkdir -p "${PGHOST_DIR}"
  echo "== 启动 PostgreSQL（端口 ${PGPORT}）"
  "${PG_BIN}/pg_ctl" -D "${PGDATA}" \
    -o "-p ${PGPORT} -k ${PGHOST_DIR} -c listen_addresses=127.0.0.1" \
    -l "${PGDATA}/server.log" -w start >/dev/null
}

psql_super() {
  "${PG_BIN}/psql" -h 127.0.0.1 -p "${PGPORT}" -U postgres -v ON_ERROR_STOP=1 "$@"
}

bootstrap_roles() {
  echo "== 建立角色与数据库（迁移账号 / 应用账号分权；SQL 见 scripts/db-bootstrap.sql）"
  "${PG_BIN}/psql" -h 127.0.0.1 -p "${PGPORT}" -U postgres -d postgres \
    -v ON_ERROR_STOP=1 -v "dbname=${DB_NAME}" -q -f "$(dirname "$0")/db-bootstrap.sql"
}

case "${1:-start}" in
  start)
    require_pg
    init_cluster
    start_cluster
    bootstrap_roles
    echo "== 就绪：jdbc:postgresql://127.0.0.1:${PGPORT}/${DB_NAME}（应用账号 pms_app，迁移账号 pms_migrator）"
    ;;
  stop)
    require_pg
    "${PG_BIN}/pg_ctl" -D "${PGDATA}" -m fast -w stop >/dev/null && echo "== 已停止"
    ;;
  status)
    require_pg
    "${PG_BIN}/pg_ctl" -D "${PGDATA}" status || true
    ;;
  reset)
    require_pg
    "${PG_BIN}/pg_ctl" -D "${PGDATA}" -m fast -w stop >/dev/null 2>&1 || true
    echo "== 删除 ${PGDATA}（破坏性操作，仅限本地一次性集群）"
    rm -rf "${PGDATA}"
    ;;
  *)
    echo "用法：$0 {start|stop|status|reset}" >&2
    exit 2
    ;;
esac
