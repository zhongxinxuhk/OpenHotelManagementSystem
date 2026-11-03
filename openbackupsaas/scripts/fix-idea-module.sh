#!/usr/bin/env bash
#
# 修复 IDEA 里「测试类满屏报错、Maven 却 BUILD SUCCESS」
#
# 症状
#   IDEA Build 输出 `package org.junit.jupiter.api does not exist` /
#   `cannot find symbol class MockMvc` 等上百条，全部集中在 src/test 下；
#   主代码照常通过；`./mvnw -o clean test-compile` 也是 BUILD SUCCESS。
#
# 根因（两条，别再各自্单独归因）
#   1) IDEA 默认开启「Store generated project files externally」（官方文档写明：
#      新导入项目默认勾选）。开启后模块配置不住在项目里，而被搬到可随时清掉的缓存：
#        ~/Library/Caches/JetBrains/IntelliJIdea<ver>/projects/<proj>.<hash>/external_build_system/modules/*.xml
#   2) 只要 openbackupsaas/ 下同时存在一个 openbackupsaas.iml，两个来源打架，
#      IDEA 会把它吃掉并写出一份残缺的模块配置 —— 缺少 test scope 依赖，
#      于是 JPS 只拿生产依赖去编译测试代码。
#
# 因此规则只有一条：**项目内的 .iml 和外部存储文件，必须只留一个**。
#
# 用法
#   ./scripts/fix-idea-module.sh                       # 默认：删掉冲突的 .iml，保留外部存储（当前推荐）
#   ./scripts/fix-idea-module.sh --write-external      # 在上述基础上，重写外部存储里的模块文件
#   ./scripts/fix-idea-module.sh --files-in-project    # 改为「文件模式」：关外部存储 + 生成 .iml
#                                                      # （必须先把 IDEA 完全退出，否则它会在退出时改回来）
#
# 不管走哪条，脚本跑完都要在 IDEA 里做一步：
#   Maven 面板 → ⟳ Reload All Maven Projects → 等索引完成 → Build
#
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_DIR="$(cd "$MODULE_DIR/.." && pwd)"
MODULE_NAME="openbackupsaas"
IML="$MODULE_DIR/$MODULE_NAME.iml"

MODE="default"
case "${1:-}" in
  --write-external)   MODE="write-external" ;;
  --files-in-project) MODE="files-in-project" ;;
  "")                 : ;;
  *) echo "未知参数：$1（可选 --write-external / --files-in-project）" >&2; exit 2 ;;
esac

backup_and_remove() {
  [[ -f "$1" ]] || return 0
  local dst="/tmp/$(basename "$1").$(date +%Y%m%d%H%M%S).bak"
  mv "$1" "$dst"
  echo "==> 移走冲突文件 $(basename "$1") → $dst"
}

# ---------------------------------------------------------------- 生成素材
cd "$MODULE_DIR"
echo "==> 解析依赖（offline）"
./mvnw -o -B -ntp dependency:tree -DoutputType=text 2>/dev/null \
  | grep -oE "[a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+:jar:[^:]+:(compile|runtime|test|provided)" \
  | sort -u > "/tmp/$MODULE_NAME-gav.txt"

gen_python() {  # $1: 目标文件  $2: 格式(file|external)
python3 - "$MODULE_DIR" "$MODULE_NAME" "$1" "$2" <<'PY'
import pathlib, shutil, sys

module_dir, module_name, target, fmt = sys.argv[1:5]
rank = {"compile": 0, "runtime": 1, "test": 2}
best = {}
for line in open(f"/tmp/{module_name}-gav.txt"):
    line = line.strip()
    if not line:
        continue
    g, a, _, v, s = line.split(":")
    key = (g, a, v)
    if key not in best or rank[s] < rank[best[key]]:
        best[key] = s

attr = {"runtime": ' scope="RUNTIME"', "test": ' scope="TEST"'}.get
libs = sorted(best.items(), key=lambda kv: (kv[0][0], kv[0][1], kv[0][2]))
entries = "\n".join(
    f'    <orderEntry type="library" name="Maven: {g}:{a}:{v}" level="project"{attr(s, "")} />'
    for (g, a, v), s in libs
)

if fmt == "file":
    body = f'''<?xml version="1.0" encoding="UTF-8"?>
<module external.linked.project.id="$MODULE_DIR$/pom.xml" external.system.module.type="SINGLE_MODULE" external.system.module.version="223-2" org.jetbrains.idea.maven.project.MavenProjectsManager.isMavenModule="true" type="JAVA_MODULE" version="4">
  <component name="AdditionalModuleElements">
    <content url="file://$MODULE_DIR$" dumb="true">
      <sourceFolder url="file://$MODULE_DIR$" isTestSource="false" />
    </content>
  </component>
  <component name="NewModuleRootManager" LANGUAGE_LEVEL="JDK_21">
    <output url="file://$MODULE_DIR$/target/classes" />
    <output-test url="file://$MODULE_DIR$/target/test-classes" />
    <content url="file://$MODULE_DIR$">
      <sourceFolder url="file://$MODULE_DIR$/src/main/java" isTestSource="false" />
      <sourceFolder url="file://$MODULE_DIR$/src/main/resources" type="java-resource" />
      <sourceFolder url="file://$MODULE_DIR$/src/test/java" isTestSource="true" />
      <sourceFolder url="file://$MODULE_DIR$/src/test/resources" type="java-test-resource" />
      <sourceFolder url="file://$MODULE_DIR$/target/generated-sources/annotations" isTestSource="false" generated="true" />
      <sourceFolder url="file://$MODULE_DIR$/target/generated-test-sources/test-annotations" isTestSource="true" generated="true" />
      <excludeFolder url="file://$MODULE_DIR$/target" />
    </content>
    <orderEntry type="inheritedJdk" />
    <orderEntry type="sourceFolder" forTests="false" />
{entries}
  </component>
</module>
'''
    pathlib.Path(target).write_text(body)
else:
    body = f'''<module>
  <component name="DeprecatedModuleOptionManager">
    <option key="type" value="JAVA_MODULE" />
  </component>
  <component name="ExternalSystem" externalSystem="Maven" externalSystemModuleType="SINGLE_MODULE" externalSystemModuleVersion="223-2" linkedProjectId="$MODULE_DIR$/pom.xml" />
  <component name="NewModuleRootManager" LANGUAGE_LEVEL="JDK_21">
    <output url="file://$MODULE_DIR$/target/classes" />
    <output-test url="file://$MODULE_DIR$/target/test-classes" />
    <content url="file://$MODULE_DIR$">
      <sourceFolder url="file://$MODULE_DIR$/src/main/java" isTestSource="false" />
      <sourceFolder url="file://$MODULE_DIR$/src/main/resources" type="java-resource" />
      <sourceFolder url="file://$MODULE_DIR$/src/test/java" isTestSource="true" />
      <sourceFolder url="file://$MODULE_DIR$/src/test/resources" type="java-test-resource" />
      <sourceFolder url="file://$MODULE_DIR$/target/generated-sources/annotations" isTestSource="false" generated="true" />
      <excludeFolder url="file://$MODULE_DIR$/target" />
    </content>
    <orderEntry type="inheritedJdk" />
    <orderEntry type="sourceFolder" forTests="false" />
{entries}
  </component>
</module>
'''
    p = pathlib.Path(target)
    if p.exists():
        shutil.copy2(p, p.with_suffix(".xml.bak"))
    p.write_text(body)

print(f"    写入 {target}（依赖库 {len(libs)} 个）")
PY
}

EXTERNAL_DIRS=$(find "$HOME/Library/Caches/JetBrains" -type d -path "*projects/*openhotelmanagementsystem*/external_build_system/modules" 2>/dev/null || true)

case "$MODE" in
  default)
    backup_and_remove "$IML"
    ;;

  write-external)
    backup_and_remove "$IML"
    if [[ -z "$EXTERNAL_DIRS" ]]; then
      echo "==> 未找到外部存储目录，改用「文件模式」"
      gen_python "$IML" file
    else
      for d in $EXTERNAL_DIRS; do
        gen_python "$d/$MODULE_NAME.xml" external
      done
    fi
    ;;

  files-in-project)
    MISC="$PROJECT_DIR/.idea/misc.xml"
    if [[ -f "$MISC" ]]; then
      cp "$MISC" "$MISC.bak"
      python3 - "$MISC" <<'PY'
import pathlib, re, sys
p = pathlib.Path(sys.argv[1])
t = p.read_text()
t = t.replace('  <component name="ExternalStorageConfigurationManager" enabled="true" />\n', '')
t = re.sub(r'    <option name="enabledProfiles">.*?</option>\n', '', t, flags=re.S)
p.write_text(t)
PY
      echo "==> 已关闭外部存储开关（备份 .idea/misc.xml.bak）"
    fi
    for d in $EXTERNAL_DIRS; do
      backup_and_remove "$d/$MODULE_NAME.xml"
    done
    gen_python "$IML" file
    ;;
esac

cat <<'EOF'

==> 完成。IDEA 里请执行：
    Maven 面板 → ⟳ Reload All Maven Projects → 索引完成后 Build（应为 0 错误）
    Maven 面板里 native / nativeTest 两个 profile 不要勾选（本机没有 GraalVM native）。
EOF
