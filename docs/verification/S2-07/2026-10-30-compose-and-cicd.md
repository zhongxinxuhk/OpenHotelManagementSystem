# S2-07（部分）中间件 Compose 编排与 CI/CD v2 — 验证记录

- 任务：S2-07 的 compose 部分（R-20261030-21）；功能项 M-07 → VERIFYING 40%
- 日期：2026-10-30（AEST）
- 提交：`899f9e7`（编排与 CI v2）→ `0d80d2d`（修复收尾步骤）
- 结论：**通过**。编排本地实测可用、真实应用跑通全迁移链、CI 用同一份编排实跑全绿。
  备份闭环、镜像构建、app 编排仍为 TODO（见 §5）。

## 1. 交付物

| 文件 | 说明 |
| --- | --- |
| `deploy/compose/docker-compose.yml` | postgres:17 + rabbitmq:3.13-management-alpine；healthcheck、named volume；端口默认与应用零配置约定对齐（PG 55432 / MQ 55672 / 管理 15672） |
| `deploy/compose/initdb/01-bootstrap.sh` | 以 psql 变量调用 `openbackupsaas/scripts/db-bootstrap.sql`（**单一事实源**，本地/CI/编排同一份分权 SQL）+ 库 owner 修正 |
| `deploy/compose/initdb/02-set-passwords.sh` | 给业务角色设口令；psql 变量转义，不拼 SQL |
| `deploy/compose/.env.example` | 全部可配置项与注释；真实 `.env` 已被 .gitignore 忽略 |
| `deploy/compose/README.md` | 边界声明、快速开始、连接信息、dev-db.sh 选择指南、备份速查、四个真实踩坑 |
| `docs/development/cicd-pipeline.md` | CI/CD v2 设计：原则、拓扑、路线图、变更约定 |
| `.github/workflows/backend.yml` | service containers → 同一份 compose；`deploy/compose/**` 纳入触发 paths |
| `openbackupsaas/scripts/db-bootstrap.sql` | 显式 `GRANT CREATE, USAGE ON SCHEMA public TO pms_migrator`（修正隐式依赖） |

## 2. 本地实测（override 端口 55433/55673，避开本机 dev 集群）

| 验证项 | 结果 |
| --- | --- |
| `docker compose up -d --wait` | 两服务 healthy；`down -v` 后全量重演共 3 次（每修一处重演一次） |
| 角色与分权 | `pms_migrator`/`pms_app` 存在；`pms_app` 建库/建表权 = **false/false**；迁移账号 DDL 探针（建表/插删）通过 |
| 口令认证 | 宿主机分别以 `pms_app` / `pms_migrator` + 密码连接 OK |
| RabbitMQ | `pms_mq` 用户存在；管理 API `pms_mq:***` 认证返回 200 |
| **真实应用** | 连 compose 库启动：Flyway 跑完 **18 个迁移**（29 张业务表、`btree_gist` 就绪），`Started openFrameworksApplication` |
| dev 环境回归 | 修改后的 bootstrap SQL 在 dev 库（55432）重跑幂等通过；迁移数/表数与 compose 环境一致（18 / 29） |
| CI 实跑 | [run 41070968121](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41070968121) @ `0d80d2d` → **success**，105 秒，`tests=257 skipped=0`——compose 起中间件的完整流程（起→测→断言→收）在真实 CI 上走通 |

## 3. 本轮抓出的四个真问题（都在首跑暴露）

1. **initdb 对脚本错误不中止。** 共享分权 SQL 直接挂进 initdb 时因 psql 变量缺失而中途报错，
   容器照样 healthy、**分权悄悄缺失**——错误被"健康状态"掩盖。修法：shell 包装 + `ON_ERROR_STOP`，
   初始化失败就让容器起不来。
2. **initdb 只 glob 目录顶层。** 脚本挂成子目录被静默忽略，日志里连 `running` 都没有。
3. **宿主机变量不会自动进容器。** initdb 脚本要用的 `PMS_DB_PASSWORD` 必须在 service
   `environment` 显式声明；漏了的现象同样是「healthy 但角色无口令」。
4. **"建库人恰好是迁移账号"是隐式依赖。** PG15+ 的 public schema CREATE 与 PG13+ 的
   trusted 扩展（`btree_gist`）都只认库 owner——dev 环境两次都是"巧合"在兜底
   （`dev-db.sh` 恰好 `OWNER pms_migrator` 建库）。compose 换了一种建库方式，巧合即拆穿：
   一次 `permission denied for schema public`、一次 `permission denied to create extension`。
   修复落在共享 SQL 与 initdb 脚本里（显式 GRANT + `ALTER DATABASE ... OWNER TO pms_migrator`）。

这四个问题合起来正是本轮方法论的价值：**编排不是"写个 yaml"，是要实际从零跑通应用迁移链**。
只验证"容器 healthy"的话，1/3/4 全部漏网。

## 4. CI/CD v2 要点

- **一份编排，三处运行**：本地、CI、预发用同一个 `docker-compose.yml`；
  编排文件写坏会在 backend 门禁当场红，而不是等部署时才发现。
- **compose 触发 paths**：改 `deploy/compose/**` 必触发后端门禁。
- **CI 首跑踩坑**：`docker compose down -v` 同样要完整插值 compose 文件，
  `:?` 必填校验在收尾步骤拿不到值 → run 染红（测试 257/0/0 全绿仍算失败）。
  修复：收尾步骤提供占位值（down 不启动服务，不会成为真实凭据）。
  **教训：`if: always()` 的清理步骤也是流水线的一部分，它的失败同样阻塞合入。**
- 设计全文（含"部署为什么暂缺"与路线图）：`docs/development/cicd-pipeline.md`。

## 5. 未做与边界

| 项 | 归属 |
| --- | --- |
| 应用镜像构建与推送（registry 选型、tag 策略、多阶段瘦身） | S2-07 剩余 |
| compose 中的 api/worker 服务与迁移作业（先有镜像再有服务） | S2-07 剩余 |
| 备份定时、异地存放、**恢复演练记录** | S2-07 剩余 |
| 预发宿主机与部署门（健康检查、回滚、审批） | S2-07 剩余 / S6 |
| 生产 HA（主从、集群、RPO/RTO） | 阶段 6（S6-06） |

单机 Compose 不是生产 HA、不是 RPO=0 方案——README §1 有显式声明，台账不以"部署完成"记账。


## 8. R-20261030-22 增补：统一 env 单源 + 后端裸机部署

用户决策调整部署分工：**中间件 Docker Compose，后端组件裸机**，两边读同一个 `.env`。
上一轮曾短暂把 api/worker 塞进 compose（未推送方向），本轮回滚为最终形态。

### 8.1 交付物

| 文件 | 说明 |
| --- | --- |
| `deploy/app.sh` | 后端裸机启停：`start/stop/status/logs`（api/worker/all）；source `.env` 导出给 java；SIGTERM 30s 优雅停机后 SIGKILL 兜底；缺 jar/缺口令启动即失败并给行动指引 |
| `deploy/compose/.env.example` | 扩展为中间件+后端统一模板（JVM/OpenAPI/端口），口令单源 |
| `deploy/compose/README.md` | 新增「中间件与后端的部署分工」章节；裸机部署操作 |

### 8.2 验证（override 端口写入 .env，compose 与脚本同源）

- compose 起中间件 healthy；裸机 `app.sh start api` 对**空卷**跑完迁移（flyway latest=12、30 张表）
- api + worker 并行 RUNNING；worker 无 Tomcat、Outbox/Inbox/外部任务/对账后台轮询确认
- `stop all` 优雅停机（SIGTERM），状态回 STOPPED，PID 文件清理
- CI [run 41079783284](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41079783284) @ `84e469e` → **success**，`tests=257 skipped=0`

### 8.3 脚本层两个踩坑

1. **bash 里 `$role（` 后紧跟全角字符**：全角括号的首字节被计入变量名（实际变量名变成
   `role\xE3`），`set -u` 下报 unbound——报错消息里只能看到一个乱码字符，非常隐蔽。
   规则：**变量后接非 ASCII 一律加花括号**。
2. **macOS 自带 bash 3.2 在 `set -u` 下展开空数组报 unbound**（`${extra[@]}`，即使定义过
   `extra=()`）。规则：部署脚本避免空数组 + set -u 组合，或用 `${arr[@]+"${arr[@]}"}`。

### 8.4 沙箱限制记录

nohup 起的裸机子进程活不过工具调用周期（api 曾在正常轮询中消失、日志无任何异常——
被沙箱清理）。完整生命周期（启动→迁移→并行运行→优雅停止）在**单条命令内**验证通过；
真实宿主机不受影响。另：沙箱代理会拦截 curl 出网（返回 AUTH_REQUIRED JSON），
应用健康验证一律用日志与 DB 侧证据，不依赖 HTTP 端口（沙箱还会给进程注入随机 server.port）。
