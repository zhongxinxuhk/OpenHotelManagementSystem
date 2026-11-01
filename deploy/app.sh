#!/usr/bin/env bash
#
# PMS 后端裸机部署脚本（S2-07）
#
# 定位：中间件由 deploy/compose 编排（Docker），后端 api/worker **裸机**运行——
# 本脚本负责它们的启停。两边读**同一个** env 文件（deploy/compose/.env）：
#   · 口令只有一份：compose 建账号、本脚本起的后端连接账号，是同一个值；
#   · PMS_DB_PORT / PMS_RABBIT_PORT 在 .env 里是**宿主端口**语义——
#     compose 把容器端口映射到它，裸机后端连 127.0.0.1:<它>，两边天然一致；
#   · 容器网络内的地址（postgres:5432）只存在于 compose 内部，与裸机无关。
#
# 前置：
#   1. cd deploy/compose && cp -n .env.example .env（至少设置四个密码）
#   2. docker compose up -d --wait（中间件就绪）
#   3. cd openbackupsaas && ./mvnw -DskipTests package（产出 jar）
#
# 用法：
#   ./deploy/app.sh start api|worker|all
#   ./deploy/app.sh stop  api|worker|all
#   ./deploy/app.sh status
#   ./deploy/app.sh logs  api|worker  # 跟随输出，Ctrl-C 退出（不影响进程）
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/deploy/compose/.env}"
RUN_DIR="$ROOT/deploy/app-run"
JAR="${PMS_JAR:-$ROOT/openbackupsaas/target/openbackupsaas-0.0.1-SNAPSHOT.jar}"

mkdir -p "$RUN_DIR"

# ---------- 加载统一 env ----------
if [[ -f "$ENV_FILE" ]]; then
  # source 前先校验：env 文件里如果有 shell 语法之外的东西（如值带未闭合引号），
  # 直接退出比"带着半个环境启动"好。set -a 让其中变量自动导出给子进程。
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
else
  echo "!! 找不到统一 env 文件：$ENV_FILE" >&2
  echo "   请先：cp deploy/compose/.env.example deploy/compose/.env 并设置密码" >&2
  exit 1
fi

: "${PMS_DB_HOST:=127.0.0.1}"      # 裸机连 compose 映射到宿主的端口
: "${PMS_RABBIT_HOST:=127.0.0.1}"

pid_file() { echo "$RUN_DIR/$1.pid"; }
log_file() { echo "$RUN_DIR/$1.log"; }

is_running() {
  local pf; pf="$(pid_file "$1")"
  [[ -f "$pf" ]] && kill -0 "$(cat "$pf")" 2>/dev/null
}

start_one() {
  local role="$1"
  if is_running "$role"; then
    echo "== $role 已在运行（pid $(cat "$(pid_file "$role")")）"
    return 0
  fi
  if [[ ! -f "$JAR" ]]; then
    echo "!! 找不到 jar：$JAR" >&2
    echo "   先构建：cd openbackupsaas && ./mvnw -DskipTests package" >&2
    exit 1
  fi
  if [[ -z "${PMS_DB_PASSWORD:-}" || -z "${PMS_DB_MIGRATOR_PASSWORD:-}" || -z "${PMS_RABBIT_PASSWORD:-}" ]]; then
    echo "!! .env 缺少口令（PMS_DB_PASSWORD / PMS_DB_MIGRATOR_PASSWORD / PMS_RABBIT_PASSWORD）" >&2
    exit 1
  fi
  # 住客实名密钥（ADR-018 §2.4）。缺了应用也会启动失败（GuestPiiCipher 构造即校验），
  # 但在这里先拦一次，给出的提示比 JVM 堆栈清楚得多。
  if [[ -z "${PMS_GUEST_PII_KEY:-}" || -z "${PMS_GUEST_PII_HMAC_KEY:-}" ]]; then
    echo "!! .env 缺少住客实名密钥（PMS_GUEST_PII_KEY / PMS_GUEST_PII_HMAC_KEY）" >&2
    echo "   生成：openssl rand -base64 32 | 分别写入这两个变量（两者必须不同）" >&2
    exit 1
  fi

  # macOS 自带 bash 3.2 在 set -u 下展开空数组会报 unbound，这里用简单变量代替数组
  local extra_args=""
  if [[ "$role" == "worker" ]]; then
    # worker 无 HTTP：后台任务（Outbox/Inbox/外部任务/到期承接/对账）都在这里
    extra_args="--spring.main.web-application-type=none"
  fi

  echo "== 启动 ${role}（jar: $(basename "$JAR")，db: $PMS_DB_HOST:${PMS_DB_PORT:-55432}，mq: $PMS_RABBIT_HOST:${PMS_RABBIT_PORT:-55672}）"
  # $extra_args 不加引号是刻意的：值是固定的 flag，没有空格，为兼容 bash 3.2
  nohup java -jar "$JAR" $extra_args \
    > "$(log_file "$role")" 2>&1 &
  echo $! > "$(pid_file "$role")"
  sleep 2
  if ! kill -0 "$(cat "$(pid_file "$role")")" 2>/dev/null; then
    echo "!! $role 启动即退出，日志尾部：" >&2
    tail -20 "$(log_file "$role")" >&2 || true
    rm -f "$(pid_file "$role")"
    exit 1
  fi
  echo "== $role 已启动（pid $(cat "$(pid_file "$role")")，日志 $(log_file "$role")）"
}

stop_one() {
  local role="$1"
  if ! is_running "$role"; then
    echo "== $role 未在运行"
    rm -f "$(pid_file "$role")"
    return 0
  fi
  local pid; pid="$(cat "$(pid_file "$role")")"
  echo "== 停止 ${role}（pid ${pid}，等待优雅停机）"
  kill -TERM "$pid"
  for _ in $(seq 1 30); do
    kill -0 "$pid" 2>/dev/null || { echo "== $role 已停止"; rm -f "$(pid_file "$role")"; return 0; }
    sleep 1
  done
  echo "== 30 秒未退出，强制结束"
  kill -KILL "$pid" 2>/dev/null || true
  rm -f "$(pid_file "$role")"
}

status_all() {
  local role
  for role in api worker; do
    if is_running "$role"; then
      echo "$role: RUNNING (pid $(cat "$(pid_file "$role")"))"
    else
      echo "$role: STOPPED"
    fi
  done
}

ensure_role() {
  case "$1" in
    api|worker) return 0 ;;
    *) echo "用法：$0 {start|stop} api|worker|all / status / logs api|worker" >&2; exit 1 ;;
  esac
}

cmd="${1:-}"
shift || true
case "$cmd" in
  start)
    target="${1:-all}"
    if [[ "$target" == "all" ]]; then ensure_role api; ensure_role worker; start_one api; start_one worker
    else ensure_role "$target"; start_one "$target"; fi
    ;;
  stop)
    target="${1:-all}"
    if [[ "$target" == "all" ]]; then stop_one api; stop_one worker
    else ensure_role "$target"; stop_one "$target"; fi
    ;;
  status)
    status_all
    ;;
  logs)
    role="${1:-}"
    ensure_role "$role"
    exec tail -n 100 -f "$(log_file "$role")"
    ;;
  *)
    echo "用法：$0 {start|stop} api|worker|all / status / logs api|worker" >&2
    exit 1
    ;;
esac
