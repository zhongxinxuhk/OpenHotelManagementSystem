# CI/CD 流水线设计（v2）

> 任务来源：S2-07「最小部署、备份与恢复闭环」 + R-20261030-21 重设计。
> 本文档回答三个问题：流水线现在长什么样、为什么这样设计、下一步往哪长。

## 1. 现状（2026-10-30，v2 改造后）

| Workflow | 触发 | 做什么 | 产物 |
| --- | --- | --- | --- |
| `backend` | push/PR 触及后端、契约基线、workflow 文件 | JDK 21 + **compose 起中间件** + `clean verify` + 零跳过断言 | surefire 报告（always）、可执行 jar |
| `frontend` | push/PR 触及前端 | `tsc`（含 `max setup`）+ `biome` + `build` + 产物断言 | `dist/` |
| `qodana_code_quality` | push/PR | 静态质量扫描（token 未配置时跳过并告警） | Qodana 报告 |
| `release` | 手动（指定 ref） | 打包 jar + dist + SHA256SUMS | 两个 artifact + 校验和 |

**v2 的关键变化**：backend 门禁的中间件不再用 GitHub Actions 的 service containers，
改为直接运行 `deploy/compose/docker-compose.yml`。

## 2. 设计原则

### 2.1 一份编排，三处运行（本轮落地的核心）

本地开发、CI 门禁、单机预发布跑的是**同一个** `deploy/compose/docker-compose.yml`。
这不是整洁癖，是血换来的：本项目「本地绿 CI 红」的两次事故（tsc 缺 umi 生成物、模板 CNAME 混入产物）
本质都是**环境定义只有口头约定，没有可执行载体**。编排文件就是环境的可执行定义——

- 本地怎么起库，CI 就怎么起库（`docker compose up -d --wait`，两个词，没有第二套参数）；
- 编排文件写错（端口冲突、healthcheck 拼错、init 脚本坏），**CI 当场红**，
  而不是等某天部署时才发现；
- CI 数据卷每次为空，等于每轮推送都实测「从零初始化」路径——分权脚本坏了照样红。

### 2.2 门禁必须不可绕过

- **零跳过断言**：`PMS_IT_DB` / `PMS_IT_RABBIT` 没生效、中间件没就绪，用例会被静默跳过，
  报告照样 BUILD SUCCESS。所以 CI 在测试后解析 surefire XML，`skipped > 0` 或 `tests == 0` 直接失败。
- **契约基线是门禁的输入**：只改 `docs/api/openapi-v1.json` 也会触发 backend——
  否则「悄悄放宽契约」可以绕过评审。
- **前端产物断言**：`dist/index.html` 必须存在、`dist/CNAME` 必须不存在
  （后者曾把部署域名指向 Ant Design 官方预览站）。

### 2.3 最小权限 + 可取消

- 所有 workflow `permissions: contents: read`；并发组按 ref 取消旧运行。
- 凭据只存在于 job 环境变量（CI 用 `ci_*` 一次性口令），不进版本库（S6-05）。

### 2.4 失败可取证

- surefire 报告 `always` 上传（7 天）；前端 dist、后端 jar 成功时留档（7 天）——
  「过了门禁的产物」与「门禁运行记录」同样值得追溯。

## 3. 流水线拓扑

```
                     push / PR
                         │
        ┌────────────────┼─────────────────┐
        ▼                ▼                 ▼
    backend门禁      frontend门禁      qodana扫描
        │                │
        │ compose起中间件 │ tsc→biome→build
        │ clean verify   │ 产物断言
        │ 零跳过断言      │
        ▼                ▼
    jar 留档          dist 留档
        │
        ├──(手动)──▶ release：jar + dist + SHA256SUMS
        │
        ▼ (规划中，见 §5)
    镜像构建 → 预发部署（compose）→ 冒烟 → 生产（阶段 6，HA）
```

## 4. 「部署」为什么还没有出现在流水线里

`release` 只打包不部署，是**刻意的**，不是没做完：

1. **部署目标还不存在。** 单机预发环境的宿主机、访问方式、回滚手段都未定义；
   写一个 deploy job 只能是 `ssh` 进某台机器然后祈祷，这不是流水线是玄学。
2. **部署形态已定为分工制（R-20261030-22）**：中间件 compose、后端**裸机**
   （`deploy/app.sh`）。预发的 deploy job 相应简化为：compose 起中间件 →
   宿主机 `mvnw package` → `app.sh` 滚动替换 → 冒烟；镜像构建不再是预发部署的前置。
   生产形态（容器化）在阶段 6 重新评估。
3. **部署门应有自己的门禁。** 迁移作业怎么跑（先停写？还是在线？）、健康检查不过怎么回滚、
   谁批准部署到预发——这些是 S2-07 剩余交付的内容，在落地时一起定义。

**原则：流水线里不出现「会假装成功」的阶段。**

## 5. 路线图

| 阶段 | 内容 | 前置 | 状态 |
| --- | --- | --- | --- |
| G1 门禁 | backend / frontend / qodana / release（本表 §1） | — | **已落地** |
| 本地/预发编排 | 中间件 compose + 后端裸机脚本（`deploy/app.sh`），统一 `.env` 单源口令 | — | **已落地**（R-20261030-22） |
| 镜像构建 | 后端容器化（多阶段 Dockerfile + registry），与生产 HA 一起评估 | 阶段 6 或预发需要时 | 未开始（裸机分工下非前置） |
| 预发部署 | deploy job：compose 起中间件 → 宿主机构建 → `app.sh` 滚动替换 → 冒烟（`/actuator/health`、一条真实下单） | 预发宿主机 | 未开始 |
| 备份闭环 | `deploy/backup/`：pg_dump 定时 + 异地存放 + **恢复演练记录**（能恢复才算有备份） | 预发环境 | 未开始 |
| 生产 HA | 主从 PG、RabbitMQ 集群、多实例、蓝绿/灰度 | 阶段 6（S6-06） | 未开始 |

## 6. 变更本设计的约定

- 新增 workflow 必须回答四个问题：触发条件、权限范围、失败时留下什么证据、
  是否引入了「本地无法复现」的环境假设。
- 修改 compose 编排必须在本地 `down -v && up -d --wait` 全量重演一次——
  CI 会替你再验一遍，但别把 CI 当第一发现人。
- 本文档与实际 workflow 不一致时，**以 workflow 为准并当天修文档**；
  反过来（只改文档不改流水线）视为设计未落地，不记入完成度。
