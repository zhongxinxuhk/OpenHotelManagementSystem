# PMS 中间件编排（Docker Compose）

> 任务来源：S2-07「最小部署、备份与恢复闭环」的交付物之一。
> 位置约定与任务规划一致（`deploy/compose/`）。

## 1. 这是什么，不是什么

**是**：本地开发、CI 门禁、单机预发布使用的 PostgreSQL 17 + RabbitMQ 3.13 编排。
三处跑的是**同一份文件**——改端口、改版本、改初始化逻辑，三处同时生效，并且 CI 每次推送都在实际验证它。

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

## 5. dev-db.sh 与 compose 怎么选

两套环境**默认端口相同（55432），同一时刻只能起一个**。

| 场景 | 建议 |
| --- | --- |
| 日常改代码、跑后端测试 | 两者皆可。compose 的优势是与 CI/预发完全同构 |
| 没有 Homebrew PostgreSQL 17 / 想要干净环境 | 用 compose（不依赖本机 PG 安装） |
| 沙箱/受限环境跑不了 Docker | 用 `scripts/dev-db.sh` |
| 需要并存 | 在 `.env` 里改 `PMS_DB_PORT`（如 55433），启动应用时 `PMS_DB_PORT=55433` |

## 6. 常用操作

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

## 7. CI 如何使用这份编排

后端门禁（`.github/workflows/backend.yml`）不再使用 GitHub Actions 的 service containers，
而是直接 `docker compose up -d --wait` 起同一份编排：

- **本地 = CI = 预发**三处环境同构，消除「本地绿 CI 红」里的一整类环境差异问题
  （本项目已为此交过学费：tsc 缺 umi 生成物、模板 CNAME 混进产物）；
- 编排文件本身被 CI 持续验证——compose 写坏了，后端门禁直接红，而不是等部署时才发现；
- CI 数据卷每次都是空的，等于每轮都实测「从零初始化」路径。

设计原则与流水线全貌见 [`docs/development/cicd-pipeline.md`](../../docs/development/cicd-pipeline.md)。

## 8. FAQ

**Q：`docker compose up` 报端口被占用？**
`55432` 被占 = 本机 `dev-db.sh` 的集群在跑（`./scripts/dev-db.sh stop`）或在 `.env` 改端口。
`15672` 被占 = 其它 RabbitMQ/管理工具，改 `PMS_RABBIT_MANAGEMENT_PORT`。

**Q：应用连不上 RabbitMQ（`access refused`）？**
九成是改过 `PMS_RABBIT_USER/PASSWORD` 但数据卷还是旧的（默认用户只在首次初始化创建）。
`docker compose down -v` 重建，或在管理界面同步用户。

**Q：改了 `initdb/` 下的脚本为什么不生效？**
初始化脚本只在**空数据卷**首次启动时执行。重置：`docker compose down -v`（会删数据）。

**Q：为什么 compose 里没有应用服务（api/worker）？**
应用的部署形态（jar 直跑 / 容器 / 系统服务）与迁移作业、健康检查门、秘密注入一起构成
部署门，属 S2-07 剩余交付与 S6 生产拓扑。编排文件里先放一个「能跑但没镜像来源」的 app
服务只会制造假象——先有镜像流水线，再有 app 服务。

**Q：初始化踩过哪些坑（为什么脚本长这样）？**
三个真实踩过的坑，都写进了 compose 文件注释，改脚本前先看：

1. `db-bootstrap.sql` 使用 psql 变量，entrypoint 直接执行 `.sql` 不传变量——
   必须经 `01-bootstrap.sh` 包装传参，且 `ON_ERROR_STOP` 必须打开：
   **initdb 对脚本错误不中止**，服务照样 healthy、分权悄悄缺失。
2. initdb 只 glob 目录**顶层**——脚本挂成子目录会被静默忽略（日志里连 running 都没有）。
3. 宿主机环境变量不会自动进入容器——initdb 脚本要用的变量必须在 service 的
   `environment` 里显式声明（漏了的现象同样是「healthy 但角色无口令」）。
4. 库 owner 统一修正为 `pms_migrator`：PostgreSQL 13+ 的 trusted 扩展（V4 依赖的
   `btree_gist`）只允许库 owner 安装，不修正则迁移在 V4 卡住。

**Q：这是生产方案吗？**
不是。单机 Compose 没有 HA 与 RPO=0 能力，见 §1 边界声明。
