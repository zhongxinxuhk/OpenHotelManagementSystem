# PMS 中间件编排（Docker Compose）

> 任务来源：S2-07「最小部署、备份与恢复闭环」的交付物之一。
> 位置约定与任务规划一致（`deploy/compose/`）。

## 1. 这是什么，不是什么

**是**：本地开发、CI 门禁、单机预发布使用的 PostgreSQL 17 + RabbitMQ 3.13 编排（Docker）；
后端 api/worker **裸机部署**（`deploy/app.sh`，不走容器）——部署形态是刻意的分工，
见 §「中间件与后端的部署分工」。两边读**同一个** `.env`，三处（本地/CI/预发）跑**同一份**编排文件。

**不是**：生产 HA 方案。没有主从、没有故障转移、没有异地备份；数据卷是单机 named volume。
生产的拓扑、RPO/RTO 目标在阶段 6（S6-06）实施。**不要把单机 Compose 标记为生产方案。**

## 2. 快速开始

```bash
cd deploy/compose

cp .env.example .env          # 然后编辑 .env，至少设置三个密码
docker compose up -d --wait   # --wait 会等两个服务都 healthy 才返回
docker compose ps             # 两个服务 STATUS 应为 Up (healthy)
```

应用侧**零配置**即可连接（默认值全部对齐）：

```bash
cd ../../openbackupsaas
./mvnw spring-boot:run        # PMS_DB_PORT 默认 55432，RabbitMQ 默认 55672
```

### 后端裸机部署（api / worker）

中间件在 Docker 里，后端进程跑在宿主机上（`deploy/app.sh`）：

```bash
# 1. 构建 jar（宿主机构建，复用 ~/.m2 缓存）
cd ../../openbackupsaas && ./mvnw -DskipTests package

# 2. 启动 / 查看 / 停止（自动 source 同一个 .env，连 compose 映射出来的端口）
cd ../deploy
./app.sh start api            # 只起 HTTP 进程（PMS_RUNTIME_ROLE=api）
./app.sh start worker         # 只起后台任务进程（无 HTTP；Outbox/Inbox/任务/对账）
./app.sh start all            # 两个都起（单机演示形态）
./app.sh status
./app.sh stop all
./app.sh logs api             # 跟随日志（Ctrl-C 退出不影响进程）
```

PID 与日志在 `deploy/app-run/`（已 gitignore）。停机先 SIGTERM 等 30 秒优雅退出，
超时才 SIGKILL——与应用的 `server.shutdown=graceful` 配合。

**统一 env 管理**：中间件与后端读**同一个** `.env`——口令变量（`PMS_DB_PASSWORD` 等）
在文件里只出现一次，中间件建账号、后端连接账号用的是同一个值；改一处，全家生效。
容器网络内的地址（`postgres:5432`）不属于 `.env`，由 compose 服务拓扑决定。

## 3. 服务与连接信息

| 服务 | 镜像 | 宿主端口（默认） | 容器端口 | 数据卷 |
| --- | --- | --- | --- | --- |
| `postgres` | `postgres:17` | `55432`（`PMS_DB_PORT`） | 5432 | `pms-pgdata` |
| `rabbitmq` | `rabbitmq:3.13-management-alpine` | `55672`（`PMS_RABBIT_PORT`） | 5672 | `pms-rabbit` |
| ↳ 管理界面 | — | `15672`（`PMS_RABBIT_MANAGEMENT_PORT`） | 15672 | — |

所有端口、库名、凭据都在 `.env` 里改，不需要动 compose 文件本体。

### 数据库账号（分权，与本地/CI 一致）

| 账号 | 用途 | 权限 | 口令来源 |
| --- | --- | --- | --- |
| `postgres` | 初始化与运维 | 超级用户 | `.env` 的 `POSTGRES_PASSWORD` |
| `pms_migrator` | Flyway 迁移 | DDL（建表/索引，表 owner） | `.env` 的 `PMS_DB_MIGRATOR_PASSWORD` |
| `pms_app` | 应用运行时 | 仅 DML | `.env` 的 `PMS_DB_PASSWORD` |

两个业务账号都不是超级用户——**应用被攻破 ≠ 可以改库结构**。授权逻辑只有一份：
`openbackupsaas/scripts/db-bootstrap.sql`（本地脚本、CI、本编排共用，改授权只改它）。

### RabbitMQ 账号

默认用户 `pms_mq`（不是 `guest`——guest 在端口映射后必被拒），口令在 `.env`。
**只在数据卷首次初始化时创建**：改用户/密码后需 `docker compose down -v` 重建，或在管理界面手工同步。

## 4. 首次启动时发生了什么

数据卷为空时，`postgres` 容器按文件名顺序执行初始化（之后**不会再执行**）：

| 顺序 | 文件 | 做什么 |
| --- | --- | --- |
| 1 | `01-db-bootstrap.sql`（挂载自 `openbackupsaas/scripts/db-bootstrap.sql`） | 建 `pms` 库、建 `pms_migrator` / `pms_app`、分权与默认权限 |
| 2 | `initdb/02-set-passwords.sh` | 按环境变量给两个业务角色设口令（psql 变量转义，密码含引号也安全） |

所以「从零重建环境」= `docker compose down -v && docker compose up -d --wait`，
顺带每次都验证了 V1→V12 迁移链的第一步（角色分权）可用。

## 5. 中间件与后端的部署分工

| | 中间件（PG / RabbitMQ） | 后端（api / worker） |
| --- | --- | --- |
| 形态 | Docker Compose | **裸机进程**（`deploy/app.sh`） |
| 配置 | `deploy/compose/.env`（同一个文件） | 同左（脚本 source 后导出给 java） |
| 变更节奏 | 低（版本/端口/口令） | 高（每天多次重启） |
| 为什么 | 有状态服务，容器化隔离数据目录与版本 | 编辑-构建-重启循环要快；排障直接看日志 |
| 升级方式 | `compose pull` / 换镜像版本 | `mvnw package` + `app.sh stop/start` |

口令只有一个来源（`.env`）：中间件建账号、后端连账号，同一个值；
`PMS_DB_PORT` / `PMS_RABBIT_PORT` 在文件里是**宿主端口**——compose 把容器端口映射到它，
裸机后端连 `127.0.0.1:<宿主端口>`，两个世界读同一个数。

## 6. dev-db.sh 与 compose 怎么选

两套环境**默认端口相同（55432），同一时刻只能起一个**。

| 场景 | 建议 |
| --- | --- |
| 日常改代码、跑后端测试 | 两者皆可。compose 的优势是与 CI/预发完全同构 |
| 没有 Homebrew PostgreSQL 17 / 想要干净环境 | 用 compose（不依赖本机 PG 安装） |
| 沙箱/受限环境跑不了 Docker | 用 `scripts/dev-db.sh` |
| 需要并存 | 在 `.env` 里改 `PMS_DB_PORT`（如 55433），启动应用时 `PMS_DB_PORT=55433` |

## 7. 常用操作

```bash
# 状态与日志
docker compose ps
docker compose logs -f postgres          # 或 rabbitmq

# 重置单个服务（保留另一个的数据）
docker compose restart postgres

# 彻底重置（删数据卷，破坏性！初始化脚本会重新执行）
docker compose down -v

# 备份与恢复（逻辑备份，适合单机预发布）
docker compose exec postgres pg_dump -U pms_migrator -d pms -Fc \
        > "pms-$(date +%Y%m%d-%H%M%S).dump"
docker compose exec -T postgres pg_restore -U pms_migrator -d pms --clean --if-exists < pms-YYYYMMDD-HHMMSS.dump
```

> 备份的**定时调度、异地存放与恢复演练**属 S2-07 后续交付（`deploy/backup/`）。
> 上面是手工速查，不是备份方案。

## 8. CI 如何使用这份编排

后端门禁（`.github/workflows/backend.yml`）不再使用 GitHub Actions 的 service containers，
而是直接 `docker compose up -d --wait` 起同一份编排：

- **本地 = CI = 预发**三处环境同构，消除「本地绿 CI 红」里的一整类环境差异问题
  （本项目已为此交过学费：tsc 缺 umi 生成物、模板 CNAME 混进产物）；
- 编排文件本身被 CI 持续验证——compose 写坏了，后端门禁直接红，而不是等部署时才发现；
- CI 数据卷每次都是空的，等于每轮都实测「从零初始化」路径。

设计原则与流水线全貌见 [`docs/development/cicd-pipeline.md`](../../docs/development/cicd-pipeline.md)。

## 9. FAQ

**Q：`docker compose up` 报端口被占用？**
`55432` 被占 = 本机 `dev-db.sh` 的集群在跑（`./scripts/dev-db.sh stop`）或在 `.env` 改端口。
`15672` 被占 = 其它 RabbitMQ/管理工具，改 `PMS_RABBIT_MANAGEMENT_PORT`。

**Q：应用连不上 RabbitMQ（`access refused`）？**
九成是改过 `PMS_RABBIT_USER/PASSWORD` 但数据卷还是旧的（默认用户只在首次初始化创建）。
`docker compose down -v` 重建，或在管理界面同步用户。

**Q：改了 `initdb/` 下的脚本为什么不生效？**
初始化脚本只在**空数据卷**首次启动时执行。重置：`docker compose down -v`（会删数据）。

**Q：为什么后端不进 compose？**
裸机是**刻意的分工**，不是没做完：
- 后端还在快速迭代（每天要重启很多次），裸机 `./mvnw package` + `./app.sh start` 的
  编辑-重启循环远快于镜像重建；
- 免去 CI 镜像构建流水线（registry、tag 策略）这一整块前置依赖，先把「环境统一」拿到手；
- 排障直接看进程日志，不用进容器。
需要容器形态时（S2-07 镜像流水线 / 预发部署），`Dockerfile` 与 compose 服务一并演进。

**Q：裸机脚本从哪里读配置？**
`deploy/compose/.env`——与中间件**同一个文件**（`source` 后导出给 java 进程）。
`.env` 里的 `PMS_DB_PORT` 是宿主端口：compose 映射到它、裸机连它，两边语义天然一致。

**Q：这是生产方案吗？**
不是。单机 Compose 没有 HA 与 RPO=0 能力，见 §1 边界声明。
