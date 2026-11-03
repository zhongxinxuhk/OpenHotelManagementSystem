#!/usr/bin/env bash
# =====================================================================
# 平台管理员初始化（ADR-019）。创建第一个平台账号并授予全部平台动作。
#
# 用法：
#   ./scripts/seed-platform-admin.sh                # 明文口令 = platform-admin
#   ./scripts/seed-platform-admin.sh --random       # 生成一次性随机口令（推荐，生产用）
#   ./scripts/seed-platform-admin.sh --verify-only  # 只做哈希自检，不落库
#
# 连接参数读 deploy/compose/.env（PMS_DB_*），与 seed-super-admin.sh 同一套。
#
# 两条与超级管理员种子相同的纪律（R-20261101-31）：
#   1. **不进 Flyway 迁移链**：迁移链会跑到每个环境（含生产），
#      等于给每个环境自动发一个固定口令的全局管理账号。
#   2. **哈希必须用项目自身的 BCryptPasswordEncoder 生成并自检**：
#      网上抄来的哈希前缀若与项目编码器不兼容（$2y$ vs $2a$），
#      登录会永远失败且文案统一为"凭据无效"，无从排查。
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SQL_FILE="$ROOT/scripts/seed-platform-admin.sql"
PLATFORM_USERNAME="${PMS_PLATFORM_ADMIN_USERNAME:-platform-admin}"

# ---------- 连接参数（与 seed-super-admin.sh 同一套） ----------
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
GENERATE_RANDOM=0
RAW_PASSWORD="${PMS_PLATFORM_ADMIN_PASSWORD:-platform-admin}"

for arg in "$@"; do
  case "$arg" in
    --random)   GENERATE_RANDOM=1 ;;
    --verify-only) VERIFY_ONLY=1 ;;
    *) echo "!! 未知参数：$arg（可用：--random / --verify-only）" >&2; exit 1 ;;
  esac
done

if [[ "$GENERATE_RANDOM" == "1" ]]; then
  RAW_PASSWORD="$(head -c 24 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9' | head -c 20)"
fi

# ---------- 用项目自身的编码器生成哈希 ----------
echo "== 步骤 1/3：生成 BCrypt 哈希（平台账号 ${PLATFORM_USERNAME}）"

# 复用主依赖类路径（spring-security-crypto 在其中）。
./mvnw -o -q -DskipTests dependency:build-classpath \
  -Dmdep.outputFile=/tmp/pms-platform-cp.txt -DincludeScope=runtime >/dev/null 2>&1
cp="$(cat /tmp/pms-platform-cp.txt)"

tmpdir="$(mktemp -d)"
cat > "$tmpdir/GenHash.java" <<'EOF'
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public class GenHash {
    public static void main(String[] args) {
        System.out.println(new BCryptPasswordEncoder().encode(args[0]));
    }
}
EOF
javac -cp "$cp" -d "$tmpdir" "$tmpdir/GenHash.java" >/dev/null 2>&1
HASH="$(java -cp "$tmpdir:$cp" GenHash "$RAW_PASSWORD")"
echo "   哈希已生成（不回显明文与哈希）"

# ---------- 自检 ----------
echo "== 步骤 2/3：哈希自检"
cat > "$tmpdir/Verify.java" <<'EOF'
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public class Verify {
    public static void main(String[] args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        System.out.println(encoder.matches(args[0], args[1]) ? "OK" : "FAIL");
    }
}
EOF
javac -cp "$cp" -d "$tmpdir" "$tmpdir/Verify.java" >/dev/null 2>&1
if [[ "$(java -cp "$tmpdir:$cp" Verify "$RAW_PASSWORD" "$HASH")" != "OK" ]]; then
  echo "!! 自检失败：生成的哈希无法被项目 BCryptPasswordEncoder 验证，终止" >&2
  exit 1
fi
echo "   OK：哈希可由项目 BCryptPasswordEncoder 验证"
rm -rf "$tmpdir"

if [[ "$VERIFY_ONLY" == "1" ]]; then
  echo "== 已按要求跳过落库"
  exit 0
fi

# ---------- 落库 ----------
echo "== 步骤 3/3：执行种子 SQL"
PGPASSWORD="${PMS_DB_PASSWORD:-}" "${PSQL_BASE[@]}" \
  -v platform_username="$PLATFORM_USERNAME" \
  -v platform_password_hash="$HASH" \
  -f "$SQL_FILE"

echo
echo "== 完成。登录方式：POST /api/v1/platform/auth/login"
if [[ "$GENERATE_RANDOM" == "1" ]]; then
  echo "   ** 随机口令只在此处显示一次：$RAW_PASSWORD"
else
  echo "   口令为脚本默认值（platform-admin），**上生产前必须更换**"
fi
