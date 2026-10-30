# PMS 开发文档总入口

> 版本：1.0；建立日期：2026-10-29（AEST）；维护人：项目负责人。
>
> 本目录是**开发执行层**文档：回答「怎么做、做到哪、下一步做什么、凭什么算做完」。
> 需求、领域模型、架构与技术选型仍以 `docs/` 下既有文档为准，本目录不重复定义业务规则。

## 1. 文档分工（先读哪一份）

| 层 | 文档 | 回答的问题 | 何时读 |
| --- | --- | --- | --- |
| 需求 | [requirements/scope.md](../requirements/scope.md)、[glossary.md](../requirements/glossary.md)、[roles-and-use-cases.md](../requirements/roles-and-use-cases.md)、[decision-register.md](../requirements/decision-register.md) | 做什么、谁来做、哪些业务项还没定 | 接新任务前 |
| 领域 | [domain/state-machines.md](../domain/state-machines.md)、[data-model.md](../domain/data-model.md)、[data-dictionary.md](../domain/data-dictionary.md)、[transaction-boundaries.md](../domain/transaction-boundaries.md) | 状态怎么迁移、表怎么建、事务边界在哪 | 写后端交易代码前 |
| 架构 | [enterprise_hotel_pms_architecture.md](../enterprise_hotel_pms_architecture.md)、[architecture/dependency-version-matrix.md](../architecture/dependency-version-matrix.md) | 模块分层、可靠性目标、技术基线 | 任何跨模块改动前 |
| 决策 | [adr/](../adr/) 全部 ADR（含新增 [ADR-015](../adr/ADR-015-frontend-ant-design-pro.md)） | 为什么这样选、回退路径 | 与既有结论冲突时 |
| 规划 | [企业级酒店 PMS 开发任务规划.md](../企业级酒店%20PMS%20开发任务规划.md) | 65 项任务的依赖、估算、门禁 G0—G6 | 排期与阶段放行 |
| **执行** | **本目录** | **功能清单、进度标记、前端规范、每轮开发记录** | **每次开发前后必读** |

本目录文件：

| 文件 | 作用 |
| --- | --- |
| [README.md](./README.md) | 本文件：状态枚举、进度标记强制流程、环境与命令 |
| [feature-catalog.md](./feature-catalog.md) | **功能清单**：15 个域、约 120 个功能项（FT-*），含交付物、优先级、任务映射、当前状态 |
| [progress.md](./progress.md) | **开发进度台账**：65 项任务状态快照 + 按轮次（Round）追加的开发记录 |
| [frontend-antd-pro.md](./frontend-antd-pro.md) | 前端工程规范：Ant Design Pro 深度使用约定、目录、组件与文案禁令 |
| [backend-setup.md](./backend-setup.md) | 后端开发说明：档案与环境变量、启动步骤、包结构、**当前具备/不具备**、已知坑 |

## 2. 状态枚举与进度标记图例

功能项（FT-*）与任务（S*-*）统一使用下列 8 个状态，**禁止自造状态词**：

| 状态 | 含义 | 允许的下一步 |
| --- | --- | --- |
| `TODO` | 未开始 | → READY |
| `READY` | 输入、依赖、验收条件齐备，可立即开工 | → DOING |
| `DOING` | 正在实现 | → VERIFYING |
| `VERIFYING` | 代码已提交，正在跑测试/评审/证据收集 | → DONE 或回 DOING |
| `BLOCKED` | 存在明确外部依赖或业务未决，需记录责任人与解除条件 | → READY |
| `DONE` | 实现 + 测试 + 证据 + 文档全部通过（见 §4） | 仅可因缺陷回退 |
| `DEFERRED` | P2 延期项，不占当期工期 | 触发条件满足后转 READY |
| `N/A` | 本期明确不适用（须写明理由与重新启用条件） | — |

**完成度口径**（写进 [feature-catalog.md](./feature-catalog.md) 的「完成度」列）：

- 一个 FT 项按 4 个检查点均分：① 数据库/契约 ② 后端实现 ③ 前端页面 ④ 测试与证据。每通过 1 项记 25%。
- 纯后端项（无前端交付）按 3 个检查点计：① 数据/契约 ② 实现 ③ 测试与证据。
- `DONE` 必须同时是 100%；100% 不等于 `DONE`（例：页面能打开但并发测试未跑 → 仍是 `VERIFYING`）。
- 汇总完成度 = Σ(FT 完成度) / FT 总数，**只用于趋势**，不作对外承诺。

## 3. 每轮开发的进度标记流程（强制）

一次「开发轮次」= 一次可独立审查、可独立提交的变更。轮次编号 `R-YYYYMMDD-NN`（同日多轮递增）。

开工 → 收口四个动作，缺一即视为本轮未完成：

| 步骤 | 动作 | 落点 |
| --- | --- | --- |
| 1 开工 | 在 [progress.md](./progress.md) §4 追加本轮条目（Round ID、目标、涉及 FT/S 任务），状态置 `DOING`；把相关功能行状态改为 `DOING` | progress.md、feature-catalog.md |
| 2 实现 | 只做本轮声明范围，超出范围的发现写入「本轮发现」而不夹带实现 | — |
| 3 验证 | 跑下列门禁命令并把**实际输出摘要**写进本轮条目；失败即回 `DOING` 并记录根因 | progress.md |
| 4 收口 | 更新功能行状态与完成度 → 更新 progress.md 顶部快照 → 在开发规划 §1.3 追加一行 → 需要留存证据时写 `docs/verification/<阶段>/<日期>-<主题>.md` | 三处同步 |

**收口一致性规则**：同一轮次的最终状态必须同时出现在 (a) feature-catalog 行、(b) progress.md 快照与轮次条目、(c) 规划文档 §1.3 执行进度表。三者不一致时，以 (c) 为历史事实、(a) 为当前口径，并立即修正 (b)。

### 3.1 门禁命令（每轮至少跑与本次改动相关的那几项）

```bash
# 后端：编译 + 单元/上下文测试（不连库时集成测试会被跳过，此时只有单元测试是有效证据）
cd openbackupsaas && ./mvnw -q -B verify

# 后端：与 CI 完全等价（连真实 PostgreSQL；CI 还会断言零跳过，见 .github/workflows/backend.yml）
cd openbackupsaas && PMS_IT_DB=true ./mvnw -B -ntp -Dstyle.color=never clean verify

# 后端：含真实 RabbitMQ 的 Outbox 发布冒烟（需要本机 Broker；CI 由 service 提供）
# 起一个一次性 Broker：docker run -d --name pms-rabbit -p 55672:5672 \
#   -e RABBITMQ_ERLANG_COOKIE=cookietest --tmpfs /var/lib/rabbitmq:rw,exec rabbitmq:3.13-management-alpine
PMS_IT_RABBIT=true PMS_RABBIT_PORT=55672 ./mvnw -o -B test -Dtest=RabbitEventPublisherSmokeTest

# 后端：仅编译（快速反馈）
cd openbackupsaas && ./mvnw -q -B -DskipTests compile

# 前端：类型检查 + 静态检查 + 生产构建（Ant Design Pro / Umi Max），与 CI 完全等价
cd frontend-admin-pro && npm run tsc && npm run biome:lint && npm run build

# 前端：本地起服务（端口 8000）
cd frontend-admin-pro && npm start             # 带 mock 数据，仅用于纯 UI 走查
cd frontend-admin-pro && npm run start:no-mock # MOCK=none 直连后端，联调/验收只用这个
```

记录格式固定为：`命令 → 结果（通过/失败 + 关键行）→ 未覆盖项`。**不允许只写「构建通过」而不留命令与关键输出。**

**本地与 CI 必须用同一条命令**：CI 的定义在 `.github/workflows/`，那里的断言（后端零跳过、前端产物不含 `CNAME`、产物 `dist/index.html` 必须存在）本身就是门禁的一部分。本地跑"差不多"的命令，会让 CI 成为第一个发现问题的环境——`npm run tsc` 缺 `max setup` 导致的 CI 失败就是这么来的（见 [CI 记录](../verification/CI/2026-10-30-github-actions.md) §3.1）。

### 3.2 接口契约（OpenAPI v1）

基线：[`docs/api/openapi-v1.json`](../api/openapi-v1.json)，由 `OpenApiContractTest` 逐次比对**结构**（HTTP 方法、状态码、参数、schema 字段），文案不参与比对。

确实要改接口时，按这个顺序：

```bash
# 1) 起本地服务（local 档案默认开启 springdoc）
cd openbackupsaas && ./mvnw -B spring-boot:run
# 2) 导出并覆盖基线（去掉 servers、格式化）
curl -s http://127.0.0.1:8080/v3/api-docs | python3 -c "import json,sys;d=json.load(sys.stdin);d.pop('servers',None);print(json.dumps(d,ensure_ascii=False,indent=2,sort_keys=True))" > ../docs/api/openapi-v1.json
# 3) 跑契约测试确认一致
PMS_IT_DB=true ./mvnw -B test -Dtest=OpenApiContractTest
```

**必须在本轮进度条目里写明"契约变更"及原因**。让变更是有意识的行为，而不是被测试逼着点一下"重新生成"。

## 4. 「DONE」判定（与规划文档 §8.2 DoD 对齐）

一个功能项/任务只有在下列全部成立时才可标 `DONE`：

1. 交付物与任务声明一致：迁移、接口、页面、配置、运行说明同步更新。
2. 与风险相称的测试通过；涉及库存、占用、幂等、资金、故障恢复的，用**真实 PostgreSQL** 验证过，不只靠 mock。
3. 无未处理的鉴权绕过、越权读、秘密泄露、敏感信息进日志。
4. 失败路径有可观测状态，运维有受控处置手段（不是「改库了事」）。
5. 与上一版本的迁移/回退影响已写明。
6. 有可复现证据（命令、输入、关联 ID、数据库核对 SQL）。

「页面能打开」「接口返回 200」「依赖装上了」都不构成 DONE。

## 5. 项目结构与环境

```text
openbackupsaas/            # Spring Boot 4.1.1 / Java 21 / MyBatis / Flyway（后端）
frontend-admin-pro/        # Ant Design Pro 6 (Umi Max 4 + React 19 + antd 6)（管理端）
frontend-engineering-vue/  # 已冻结的 Vue 骨架，按 ADR-015 停用，待确认后归档删除
docs/                      # 需求、领域、架构、ADR、验证证据、本目录
```

| 环境 | 版本 | 备注 |
| --- | --- | --- |
| JDK | Temurin 21.0.13 | 用 `./mvnw`，不依赖全局 Maven |
| Node | 22.22.2（engines ≥22） | 前端要求 `>=22.0.0` |
| PostgreSQL | 17.x | 事务正确性验证必须用真实实例；Docker 不可用时须在轮次条目注明「数据库集成未跑」 |

## 6. 协作与提交约定

- 一轮变更一次提交；提交信息 `feat|fix|docs|chore(<模块>): <说明>`，正文写 Round ID。
- 禁用 `--amend` 覆盖已推送历史；禁用 `--no-verify` 跳过钩子；钩子失败先修根因。
- 破坏性数据库变更走「扩展 → 回填 → 收缩」三段，不与应用版本切换同批实施。
- 删除/归档旧文件前先征得确认，默认移入回收站而非直接删除（`frontend-engineering-vue/` 即按此处理）。

## 7. 两条长期约束（与本目录同等效力）

1. **前端页面不写自述式营销文本。** 页面只呈现业务数据、字段标签、操作与状态；不出现介绍产品/团队/理念的标语、欢迎辞、价值观文案、「我们致力于…」式叙述。细则见 [frontend-antd-pro.md](./frontend-antd-pro.md) §7。
2. **每轮开发必须标记进度。** 按 §3 四步执行，未标记的轮次不计入完成。
