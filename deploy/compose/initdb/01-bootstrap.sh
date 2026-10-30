#!/usr/bin/env bash
#
# 建库、建角色与分权（仅数据卷首次初始化时执行一次）。
#
# 为什么包一层 shell，而不是把 db-bootstrap.sql 直接挂进 /docker-entrypoint-initdb.d：
#   该 SQL 使用 psql 变量（:'dbname'），entrypoint 直接执行 .sql 时不传变量——
#   DO 块（建角色）能跑，从建库开始的语句全部报 syntax error，而且 initdb
#   **不会因为脚本错误而中止**，容器照样 healthy，分权悄悄缺失（本轮首跑真实踩到）。
#   这里显式 -v 传变量并打开 ON_ERROR_STOP：初始化失败就让容器起不来，
#   把错误放到最显眼的位置。
#
# SQL 本体是仓库里的单一事实源（openbackupsaas/scripts/db-bootstrap.sql），
# 本地 dev-db.sh、CI、这里跑的是同一份。
set -euo pipefail

DB_NAME="${POSTGRES_DB:-pms}"
BOOTSTRAP_SQL="/bootstrap/db-bootstrap.sql"

if [[ ! -f "$BOOTSTRAP_SQL" ]]; then
  echo "!! 找不到 $BOOTSTRAP_SQL（compose 挂载缺失？）" >&2
  exit 1
fi

/usr/bin/psql -v ON_ERROR_STOP=1 -U postgres -d postgres \
  -v dbname="$DB_NAME" \
  -f "$BOOTSTRAP_SQL"

# 库 owner 统一为迁移账号，与 scripts/dev-db.sh 的语义对齐（那里 CREATE DATABASE
# OWNER pms_migrator）。原因：PostgreSQL 13+ 的 trusted 扩展（如 V4 依赖的 btree_gist）
# 允许"库 owner"安装；compose 的库由镜像用 POSTGRES_DB 建出（owner 是 postgres），
# 不修正的话 V4 迁移会 permission denied——同一个"owner 巧合"，第二个受害者。
/usr/bin/psql -v ON_ERROR_STOP=1 -U postgres -d postgres \
  -v dbname="$DB_NAME" <<'SQL'
ALTER DATABASE :"dbname" OWNER TO pms_migrator;
SQL

echo "== 角色与分权初始化完成（db: ${DB_NAME}，owner=pms_migrator）"
