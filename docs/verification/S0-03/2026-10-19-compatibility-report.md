# S0-03 技术栈兼容性验证报告

> 日期：2026-10-19（Asia/Shanghai）；结论：完成技术选型和最小构建验收；生产集成未验收。
>
> 关联：[ADR-011](../../adr/ADR-011-technology-baseline.md)、[版本矩阵](../../architecture/dependency-version-matrix.md)、[开发规划](../../企业级酒店%20PMS%20开发任务规划.md)。

## 1. 验证范围与环境

真实工程位于仓库的 `openbackupsaas/` 与 `frontend-engineering-vue/`。本次先检查现有工程，再复制源码和配置到独立临时目录，排除 `.git`、`target`、`node_modules`、`dist`、`.DS_Store`，仅在副本中调整依赖与按钮页面。

| 环境项 | 本次记录 |
| --- | --- |
| OS / CPU | macOS 27.0.1 / aarch64 |
| Java | Temurin 21.0.12.1+1 LTS |
| Maven | Wrapper 下载运行 3.9.11；未依赖全局 mvn |
| Node / npm | 24.15.0 / 11.12.1 |
| Docker | CLI 存在，daemon socket 不可连接 |
| 数据库 | 未启动 PostgreSQL，未配置数据源，未执行迁移 |
| 源码保护 | [37 个文件的初始 SHA-256](./source-manifest.json)与结束核对一致；不包含生成的构建目录 |

这些结果是当前环境的快照。下载依赖使用本机缓存及可用源，不构成全新机器完全离线可复现的承诺。没有部署或改动远程系统。

## 2. 已执行项目与结果

| 编号 | 对象与动作 | 结果 | 能支持的结论 / 不能支持的结论 |
| --- | --- | --- | --- |
| V01 | 原后端 `./mvnw -B -ntp verify` | BUILD SUCCESS；1 测试，0 失败、0 错误、0 跳过 | 初始化工程上下文及打包通过；没有交易、租户或数据库业务覆盖 |
| V02 | 原前端 `npm run build` | 类型检查、Vite 构建通过 | 当前演示工程可构建；不是后台业务页面验收 |
| V03 | 隔离前端 `npm ci --no-audit --no-fund` 后 `npm run build` | 全新安装 151 个包；类型检查、构建通过 | 现有锁文件在当前环境可安装和构建 |
| V04 | 原后端 `dependency:tree` | 保存实际解析树 | 记录传递依赖，不是漏洞扫描 |
| V05 | 隔离后端应用候选 POM，`./mvnw -B -ntp -DskipTests package` | BUILD SUCCESS；明确 Tests are skipped | 候选依赖解析、编译、打包成功；候选应用启动与测试未执行 |
| V06 | 候选后端 `dependency:tree` | 保存实际解析树 | 包含 MyBatis、Flyway PostgreSQL、springdoc 等解析版本 |
| V07 | 隔离前端安装 Element Plus 2.14.7，替换为最小按钮页后 `npm run build` | 类型检查、构建通过 | 组件与当前 Vue/TS/Vite 的最小构建路径可用；无浏览器交互或视觉检查 |
| V08 | 基线前端与 Element Plus 候选分别 `npm audit --json` | 两次均 0 条已知漏洞 | 限 npm 审计当时覆盖范围；不表示整个项目无漏洞 |
| V09 | Docker 可用性检查 | daemon socket 连接失败 | 未执行 PostgreSQL/Testcontainers，保留到 S1 验证 |
| V10 | 源码与配置 SHA-256 前后比较 | 37/37 一致 | 实际 POM、lockfile、源码未因本次探针改动 |

保留的构建输出摘要见 [build-evidence.txt](./build-evidence.txt)。原后端构建约 4.607 秒，候选打包约 11.205 秒；前端隔离基线 254 ms，组件探针 185 ms。上述耗时只描述本机输出，不用于性能承诺或比较技术栈快慢。

基线前端构建输出 JS 73.19 kB / gzip 28.33 kB，CSS 3.56 kB；按钮探针 JS 89.52 kB / gzip 34.43 kB，CSS 29.62 kB。两个页面内容不同，不能将差值直接解释为完整业务引入组件库的固定增量。

## 3. 候选探针的精确输入

### 3.1 后端

[候选 POM](./candidate-backend-pom.xml)保留 Boot 4.1.1 / Java 21，去掉 Cloud BOM 和未使用组件，加入 JDBC、Validation、Actuator、Flyway、PostgreSQL 专用迁移模块、MyBatis 4.1.0、springdoc 3.1.1 及相关测试 starter。源代码与主类沿用副本原样。

Boot BOM 实际解析出 Framework 7.0.9、Security 7.1.1、HikariCP 7.0.2、Flyway 12.4.0、PostgreSQL driver 42.7.13、JUnit Jupiter 6.0.3。完整证据：[原依赖树](./backend-original-tree.txt)、[候选依赖树](./backend-candidate-tree.txt)。

未通过禁用数据源自动配置来宣称应用启动成功。候选打包显式跳过测试，新增的数据源和迁移配置需要在 S1 实现后执行。

### 3.2 前端

副本安装命令：

```sh
npm install --save-exact element-plus@2.14.7 --no-audit --no-fund
```

副本 `src/App.vue` 的完整内容：

```vue
<script setup lang="ts">
import { ElButton } from 'element-plus'
import 'element-plus/es/components/button/style/css'
</script>

<template><ElButton type="primary">PMS compatibility probe</ElButton></template>
```

保存[候选 package.json](./candidate-frontend-package.json)和[候选 package-lock.json](./candidate-frontend-package-lock.json)，用于重新执行同一依赖组合。无需将该按钮页替换进真实工程。

## 4. 复现步骤

以下步骤在新建的临时工作目录运行，先将两个真实工程复制到临时目录，排除构建产物和依赖目录。`$PMS_PROBE_ROOT` 指自行创建的绝对路径；`$PMS_REPO_ROOT` 指本仓库绝对路径。不要让两者相同。

1. 在未经依赖调整的后端副本执行 `./mvnw -B -ntp verify`，记录测试结果；执行 `./mvnw -B -ntp dependency:tree -DoutputFile=dependency-tree.txt` 保存依赖。
2. 在未经依赖调整的前端副本执行 `npm ci --no-audit --no-fund`、`npm run build`、`npm audit --json`，保留基线输出。
3. 将本目录的 `candidate-backend-pom.xml` 复制为临时后端的 `pom.xml`；执行 `./mvnw -B -ntp -DskipTests package` 和上述依赖树命令。该步骤只复现候选编译打包。
4. 将本目录候选前端 package/lock 文件分别复制为临时前端的 `package.json`、`package-lock.json`，将临时 `src/App.vue` 替换为 §3.2 内容；执行 `npm ci --no-audit --no-fund`、`npm run build`、`npm audit --json`。
5. 按 `source-manifest.json` 重新计算真实工程同名文件的 SHA-256；若当前源码已继续开发，以新检查点重新生成清单，不据旧清单误判正常变更。

测试启动日志可能包含框架自动生成的开发密码。本目录仅保留允许的构建结果摘要与无凭据的依赖信息，不归档完整应用启动日志。

## 5. 未执行项及责任任务

| 未执行项 | 解除条件 / 后续验收 | 任务 |
| --- | --- | --- |
| 候选应用启动、数据库连接 | Docker/数据库可用；真实数据源配置；启动与 readiness 检查 | S1-01/02 |
| Flyway 空库和旧版升级 | 初始化、增量迁移、失败恢复、历史校验 | S1-02—S1-05 |
| MyBatis SQL 与事务 | Mapper 实际 CRUD、回滚、租户条件、锁顺序、行数检查 | S1 交易任务 |
| 范围排斥约束与维修互斥 | 真实 PostgreSQL、双向竞争、事务回滚、超时边界 | S1-05 及 G1 |
| Security / API 文档访问控制 | 显式认证授权配置、跨租户拒绝、文档与管理端点限制 | S1-01、S0-09 及权限任务 |
| Testcontainers | 可运行容器，测试生命周期与清理行为验证 | S1-01 |
| RabbitMQ 与消息恢复 | 阶段 2 重新锁定受支持版本；发布、消费、失败恢复测试 | S2 |
| Element Plus 实际页面 | 浏览器、交互、键盘、布局、表格和房态日历检查 | S3-01 及页面任务 |
| 全量 SCA / 许可证 / 镜像扫描 | 保存扫描器、数据库版本、扫描范围及处置证据 | CI 与发布门禁 |

## 6. 完成判定与交接

S0-03 的验收是技术选型、依赖处置清单及后端/前端最小构建证据；本次满足该范围，标记 **DONE**。此状态不包含候选后端运行集成、G0、G1 或生产放行。业务范围与状态机的未决项继续保持 VERIFYING。

下一步 S0-04 输出数据模型候选与不变量保障映射；关键业务规则冻结后再形成正式迁移。所有本次结果的机器可读摘要见 [evidence.json](./evidence.json)，两次前端审计见[基线 JSON](./frontend-baseline-audit.json)、[候选 JSON](./frontend-candidate-audit.json)。
