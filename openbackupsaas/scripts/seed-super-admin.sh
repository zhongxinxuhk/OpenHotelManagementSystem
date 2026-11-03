#!/usr/bin/env bash
#
# 超级管理员初始化执行器（S3-01 前后端对接）
#
# 作用：
#   1. 调用 scripts/seed-super-admin.sql 落库（租户 / 门店 / SUPER_ADMIN 角色 / admin 账号）；
#   2. **自检哈希**：确认 SQL 里写的 BCrypt 哈希确实能被项目的 BCryptPasswordEncoder 验证通过。
#      这一步不是多余——哈希抄错前缀或算法时，登录会永远返回 401，
#      而报错信息统一是"凭据无效或账号不可用"，排查起来毫无线索。
#      在落库之前就知道"这个哈希是坏的"，比登不上再查要快得多。
#
# 用法：
#   ./scripts/seed-super-admin.sh                 # 用默认口令 admin
#   ./scripts/seed-super-admin.sh --random        # 生成一次性随机口令（推荐用于共享环境）
#   ./scripts/seed-super-admin.sh --verify-only   # 只做哈希自检，不落库
#
# 依赖：psql（连业务库）、java（跑哈希自检；仅自检需要）
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SQL_FILE="$ROOT/scripts/seed-super-admin.sql"
HASH='$2a$10$nnsm474B8Jjg6B8GdPu3G.Fsl0tZqyBtY/5zK4TbnPS.RRl7gX6HO'
RAW_DEFAULT='admin'

# ---------- 连接参数 ----------
# 与应用侧同一套口径：deploy/compose/.env 里的 PMS_DB_* 决定连哪个库。
ENV_FILE="${ENV_FILE:-$ROOT/../deploy/compose/.env}"
if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

DB_HOST="${PMS_DB_HOST:-127.0.0.1}"
DB_PORT="${PMS_DB_PORT:-55432}"
DB_NAME="${PMS_DB_NAME:-pms}"
DB_USER="${PMS_DB_USER:-pms_app}"

# 连接失败时自动回退到 IPv6 回环。
#
# 为什么需要：本机 Docker 发布的端口（compose 的 "55432:5432"）在 IPv4 回环上不通，
# 只有 ::1 能连上——表现为 "connection to 127.0.0.1, port 55432 failed: Connection refused"。
# 这不是数据库没起，是 IPv4 路径被本机网络层拦了。
#
# 自动回退的价值：脚本不该要求每个操作者都记得"这台机器要加 ::1"。
# 只在第一次失败后回退并打日志，不掩盖真实的认证失败（认证失败不是连接失败）。
PSQL_BASE=(psql -v ON_ERROR_STOP=1 -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME")

db_reachable() {
  local host="$1"
  PGPASSWORD="${PMS_DB_PASSWORD:-}" psql -v ON_ERROR_STOP=1 \
    -h "$host" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -c 'SELECT 1' >/dev/null 2>&1
}

if ! db_reachable "$DB_HOST"; then
  if [[ "$DB_HOST" == "127.0.0.1" ]] && db_reachable "::1"; then
    echo "== ${DB_HOST}:${DB_PORT} 不可达，改用 ::1 重试"
    DB_HOST="::1"
    PSQL_BASE=(psql -v ON_ERROR_STOP=1 -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME")
  fi
fi

VERIFY_ONLY=0
RAW_PASSWORD="$RAW_DEFAULT"

for arg in "$@"; do
  case "$arg" in
    --random)
      RAW_PASSWORD="$(head -c 24 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9' | head -c 20)"
      ;;
    --verify-only)
      VERIFY_ONLY=1
      ;;
    *)
      echo "!! 未知参数：$arg（可用：--random / --verify-only）" >&2
      exit 1
      ;;
  esac
done

# ---------- 步骤 1：哈希自检 ----------
echo "== 步骤 1/2：BCrypt 哈希自检"
# 自检用的哈希来自 SQL 文件本身，避免"脚本里的哈希"与"SQL 里的哈希"是两回事。
SQL_HASH="$(grep -oE '\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}' "$SQL_FILE" | head -1)"
if [[ -z "$SQL_HASH" ]]; then
  echo "!! 在 $SQL_FILE 里没找到 BCrypt 哈希" >&2
  exit 1
fi

verify_hash() {
  local hash="$1" raw="$2"
  local tmpdir
  tmpdir="$(mktemp -d)"
  trap 'rm -rf "$tmpdir"' RETURN
  cat > "$tmpdir/Verify.java" <<'JAVA'
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public class Verify {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        boolean ok = encoder.matches(args[0], args[1]);
        System.out.println(ok ? "MATCH" : "MISMATCH");
        System.exit(ok ? 0 : 1);
    }
}
JAVA
  local cp
  cp="$(cd "$ROOT" && ./mvnw -o -q dependency:build-classpath \
        -Dmdep.outputFile="$tmpdir/cp.txt" -DincludeScope=runtime >/dev/null 2>&1 \
        && cat "$tmpdir/cp.txt")"
  javac -cp "$cp" -d "$tmpdir" "$tmpdir/Verify.java" 2>/dev/null
  java -cp "$tmpdir:$cp" Verify "$raw" "$hash"
}

if command -v java >/dev/null 2>&1; then
  if verify_hash "$SQL_HASH" "$RAW_DEFAULT"; then
    echo "   OK：SQL 中的哈希可由项目 BCryptPasswordEncoder 验证（明文 admin）"
  else
    echo "!! 哈希自检失败：SQL 里的哈希与明文 admin 不匹配" >&2
    echo "   请用项目编码器重新生成（见本脚本头部说明）" >&2
    exit 1
  fi
else
  echo "   跳过：未找到 java，无法自检（生产环境请人工确认）"
fi

if [[ "$VERIFY_ONLY" == "1" ]]; then
  echo "== 仅自检模式，未落库"
  exit 0
fi

# ---------- 步骤 2：落库 ----------
# --random 模式下需要把新口令的哈希写进 SQL 才能落库，
# 因此这里生成一份临时 SQL（落到临时目录），不污染仓库里的脚本。
if [[ "$RAW_PASSWORD" != "$RAW_DEFAULT" ]]; then
  echo "== 步骤 2/2：生成随机口令并落库（口令只显示这一次）"
  tmpdir="$(mktemp -d)"
  trap 'rm -rf "$tmpdir"' EXIT
  new_hash="$(cd "$ROOT" && ./mvnw -o -q dependency:build-classpath -Dmdep.outputFile="$tmpdir/cp.txt" \
        -DincludeScope=runtime >/dev/null 2>&1; cd "$tmpdir" && cat > Gen.java <<'JAVA'
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public class Gen {
    public static void main(String[] args) {
        System.out.println(new BCryptPasswordEncoder().encode(args[0]));
    }
}
JAVA
  javac -cp "$(cat "$tmpdir/cp.txt")" -d "$tmpdir" "$tmpdir/Gen.java" 2>/dev/null && \
  java -cp "$tmpdir:$(cat "$tmpdir/cp.txt")" Gen "$RAW_PASSWORD")"
  sed "s|\\\$2[aby]\\\$[0-9]{2}\\\$[./A-Za-z0-9]{53}|$new_hash|" "$SQL_FILE" > "$tmpdir/seed.sql"
  SQL_TO_RUN="$tmpdir/seed.sql"
else
  echo "== 步骤 2/2：落库（默认口令 admin）"
  SQL_TO_RUN="$SQL_FILE"
fi

PGPASSWORD="${PMS_DB_PASSWORD:-}" psql \
  -v ON_ERROR_STOP=1 \
  -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
  -f "$SQL_TO_RUN"

if [[ "$RAW_PASSWORD" != "$RAW_DEFAULT" ]]; then
  echo ""
  echo "== 一次性随机口令：$RAW_PASSWORD"
  echo "   请立即保存；该口令不会被再次显示，也无法从库里读回（只存哈希）。"
fi
