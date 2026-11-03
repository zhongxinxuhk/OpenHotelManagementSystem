#!/usr/bin/env bash
#
# 给业务角色设置口令（仅在数据卷首次初始化时执行一次）。
#
# 为什么单独一个脚本而不是改 db-bootstrap.sql：
#   db-bootstrap.sql 同时服务两套环境——
#   · scripts/dev-db.sh：本机 trust 认证，空密码可用，不希望被强制要求口令；
#   · compose / CI / 预发布：端口映射到宿主，host 连接是口令认证，必须设口令。
#   所以"建角色与授权"留在共享 SQL（单一事实源），"设口令"放这里按需执行。
#
# 密码经 psql 变量 :'pwd' 传入：psql 会做引号转义，避免密码含单引号时拼接出错误 SQL。
# 千万不要用字符串拼接（"... PASSWORD '$VAR'"）——那既是注入面，也是排不完的转义坑。
#
# （实现注记：这里刻意不写 ${!var} 间接展开的"通用"循环——它在这个上下文里
#   报 invalid indirect expansion，而且省下的三行抵不上排查它的时间。显式写。）
set -euo pipefail

DB_NAME="${POSTGRES_DB:-pms}"

psql_exec() {
  # 容器内本地 socket 是 trust 认证（官方镜像 initdb 行为），以 postgres 执行
  /usr/bin/psql -v ON_ERROR_STOP=1 -U postgres -d "$DB_NAME" "$@"
}

set_password() {
  local role="$1" password="$2"
  psql_exec -v pwd="$password" -v role="$role" <<'SQL'
ALTER ROLE :"role" PASSWORD :'pwd';
SQL
  echo "== 已为角色 ${role} 设置口令"
}

if [[ -n "${PMS_DB_PASSWORD:-}" ]]; then
  set_password pms_app "$PMS_DB_PASSWORD"
else
  echo "!! 未设置 PMS_DB_PASSWORD，pms_app 将保持无口令（仅限本机回环场景）" >&2
fi

if [[ -n "${PMS_DB_MIGRATOR_PASSWORD:-}" ]]; then
  set_password pms_migrator "$PMS_DB_MIGRATOR_PASSWORD"
else
  echo "!! 未设置 PMS_DB_MIGRATOR_PASSWORD，pms_migrator 将保持无口令（仅限本机回环场景）" >&2
fi
