# S2-01 Outbox 发布与 Worker 隔离：验证记录

> 日期：2026-10-30（AEST）；轮次：`R-20261030-16`；任务：S2-01（P0）；状态：**DONE**
> 依据：[架构 §6.3](../enterprise_hotel_pms_architecture.md)、[规划 §S2-01](../企业级%20PMS%20开发任务规划.md)、[ADR-011](../adr/ADR-011-technology-baseline.md)（消息：只选 RabbitMQ，阶段 2 接入）
> 代码：`journal/messaging/*`、`journal/service/Outbox*`、`journal/mapper/OutboxPublisherMapper`、`shared/runtime/WorkerRoleCondition`、`db/migration/V8`

## 1. 交付物

| 交付项 | 落点 | 说明 |
| --- | --- | --- |
| 发布器 | `OutboxPublisherService` | 三步独立提交：领取（UPDATE，落租约）→ 发布（网络调用，无事务无行锁）→ 标记（带租约校验） |
| 领取 SQL | `OutboxPublisherMapper.claimBatch` | `WITH claimed AS MATERIALIZED (SELECT … LIMIT n FOR UPDATE SKIP LOCKED)` + `UPDATE … FROM claimed … RETURNING` |
| 发布端口与实现 | `EventPublisher` / `RabbitEventPublisher` | publisher confirm（correlated，**等待**确认）+ `mandatory`/returns + 持久化投递 |
| Worker 入口 | `pms.runtime.role=worker` + `WorkerRoleCondition` + `OutboxPoller` | 同制品按角色启停；worker 配 `--spring.main.web-application-type=none` 不起 Tomcat |
| 积压与事件年龄指标 | `OutboxBacklogMonitor` | `pms.outbox.pending`、`pms.outbox.oldest_event_age_seconds`、`pms.outbox.published/failed/backlog_breach` |
| 迁移 | `V8__outbox_delivery.sql` | `last_error` 列 + 部分索引 `idx_outbox_event_due (next_attempt_at, occurred_at, event_id) WHERE published_at IS NULL`；`next_attempt_at` 回填后设为 NOT NULL DEFAULT now() |

## 2. 语义定调（这轮最重要的三条）

### 2.1 允许重复，不允许丢失

选择"至少一次"之后，重复不是缺陷而是契约的一部分：去重责任在消费侧（S2-02 用 `eventId` 做幂等键）。
发布器能保证的是**不丢**：任何崩溃点之后，事件最终都会被投递。

| 崩溃位置 | 库里状态 | 恢复方式 | 后果 |
| --- | --- | --- | --- |
| 领取之后、发布之前 | 有租约、未发布 | 租约到期后重新领取 | 正常发布一次，**不丢** |
| 发布之后、标记之前 | 有租约、未发布（但已发出） | 租约到期后重新领取 | **重发一次**，消费侧去重 |
| 标记之后 | 已发布 | 不再参与领取 | — |

### 2.2 发布不进事务

把网络调用包进事务，等于在等 Broker 确认的整段时间里持有行锁——一个慢 Broker 就能拖死所有 Worker；
而且事务回滚会连"失败记录"一起丢掉，恰恰在最需要诊断的时候没有诊断信息。
因此领取、发布、标记各自独立提交，代价是"发布成功但标记前崩溃会重发"，这正是 2.1 的取舍。

### 2.3 租约条件才是去重守卫，`SKIP LOCKED` 只负责不互相等待

这一条是**反向验证推翻了我的初判**才写对的（见 §4.3）。初版注释写的是"领取必须 `SKIP LOCKED`，
否则会重复领取"——实测删掉 `SKIP LOCKED` 用例仍通过；真正删掉的是租约条件
（`lease_until IS NULL OR lease_until <= now()`）才出现 10 条重复。
`SKIP LOCKED` 的作用是让后到的 Worker 立刻跳到别的行，而不是阻塞在前一批行锁上。

## 3. 门禁实测（本地，真实 PostgreSQL 与真实 RabbitMQ）

```
PMS_IT_DB=true PMS_IT_RABBIT=true ./mvnw -o -B -ntp clean verify
→ Tests run: 199, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
```

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 全量（含 PG + Rabbit） | `PMS_IT_DB=true PMS_IT_RABBIT=true ./mvnw clean verify` | **199 / 0 / 0 / 0**（上轮 183；新增 16 = 集成 13 + Broker 冒烟 3） |
| 无库无 Broker | `./mvnw test` | 199 run / **174 skipped** / 0 failure（真跳过，不是伪通过） |
| 迁移 | `flyway_schema_history` 最新 | `8`，真实应用 |
| 契约 | `OpenApiContractTest` | 通过（本轮无新增 HTTP 端点，基线未变） |

### 3.1 真实 CI

CI 必须也能验"确认与持久化"，因此 backend 门禁新增 `rabbitmq:3.13-management` service 并打开
`PMS_IT_RABBIT`，与既有「零跳过」断言咬合：Broker 起不来就红，而不是静默跳过。

实跑结果：backend [run 41032295277](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41032295277)
@ `e5a39ab` → **success**，9 步全绿（job 114 秒），关键日志：

```
[INFO] Tests run: 199, Failures: 0, Errors: 0, Skipped: 0
tests=199 skipped=0 failures=0 errors=0
```

其中包含 3 个真实 Broker 冒烟用例（CI 中未被跳过），说明"确认后才算发布"在 CI 上也是实测过的，
不是只在本地跑过一次。

## 4. 反向验证（三条，都改掉了原本会写错的东西）

### 4.1 删掉租约条件 → 出现 10 条重复领取

```
AssertionFailedError: 同一条事件不得被两个 Worker 同时领到 ==> expected: <0> but was: <10>
```

结论：租约条件是去重守卫。已把这句话写进 SQL 注释与用例注释，并纠正原先"SKIP LOCKED 保证不重复"的错误表述。

### 4.2 删掉 `SKIP LOCKED` → 用例仍通过

说明它不承担正确性，只承担吞吐（避免后到者阻塞）。用例**不能**用它来证明互斥，否则是在测一个不存在的保证。

### 4.3 `UPDATE … FROM (子查询)` 形式在并发下突破批大小（本轮最值得记的坑）

首版领取语句是 `UPDATE … FROM (SELECT … LIMIT n FOR UPDATE SKIP LOCKED)`。计划看上去完全正确
（`Limit` 在 `LockRows` 之上），psql 里单会话/双会话/PREPARE 复刻都严格返回 10 行；
但用 MyBatis 并发跑时**偶发一次领走全部 20 行**（诊断输出：`各批大小=[0, 20]`，
`attempt_count>1` 为 0，即单条语句租下了 20 行）。同一 SQL 的参数绑定经日志确认无误
（`30(Long), 10(Integer)`）。

改成 `WITH claimed AS MATERIALIZED (…) + UPDATE … FROM claimed` 后，**连跑 10 次全部通过**。

- `MATERIALIZED` 是必须的：不加它规划器仍可能把 CTE 拉平，限额随之失效。
- 最可能的机制：外层 `UPDATE … FROM` 在并发下对 Source 子计划做 EvalPlanQual 重查，
  重查会重新执行子计划，`LIMIT` 因此被"按重查次数"重复生效，累计更新的行数超限。
  物化 CTE 后子计划结果固定，重查拿不到新行。**这条属推断，未拿到 PG 内部证据，记录在此供后续复现。**
- 结论不是"结论"，而是"证据"：能稳定复现的是行为，不能确认的是机制。

### 4.4 一次误诊也值得记：`/tmp` 上的开发库被系统清理

连续失败中出现 `FATAL: could not open file "global/pg_filenode.map"`——`/tmp` 被清理导致
开发集群损坏。`scripts/dev-db.sh reset && start` 重建即可（该库本就是一次性、可丢弃的）。
教训：**把"用例失败"和"环境损坏"分开判断**，否则会用代码去解释一个已经坏掉的数据库。

## 5. 运行态冒烟（真实 Broker，不只是测试）

以 Worker 角色无 Web 启动：`--spring.main.web-application-type=none --pms.runtime.role=worker`
（日志只有 `Started openFrameworksApplication`，无 Tomcat）。

| 场景 | 操作 | 观察结果 |
| --- | --- | --- |
| 正常投递 | 插入 1 条未发布事件 | 数秒内 `published_at` 有值、`lease_token` 释放；RabbitMQ 队列收到 1 条，报文为完整信封（`eventId`/`tenantId`/`aggregateVersion`/`eventOrdinal`，`payload` 以 JSON 对象嵌入而非转义字符串） |
| Broker 停机 | `docker stop` 后插入事件 | 事件留在库中、`published_at` 为空、`last_error=发送失败：Connection refused (connect failed)`、`next_attempt_at > now()`；日志显示退避 2→4→8→16 秒 |

## 6. 未覆盖与边界

1. **死信与人工重放**（M-04 / S2-04）：目前失败事件无限重试并封顶退避（默认 5 分钟），没有"停止重试"的死信状态。
2. **告警送达**（M-06 / S2-06）：本轮只做"判定 + 计数 + ERROR 日志"，Prometheus/Alertmanager 与值班送达未接。
   一个只写日志的告警如果被称为告警，会让人以为有人在看。
3. **Inbox 去重**（M-02 / S2-02）：重复投递目前完全依赖消费侧，尚未实现。
4. **消费者与队列绑定**：发布器不声明队列与绑定（那是消费侧的事），只按需声明 topic 交换机。
5. **批内保序的边界**：只能保证批内按 `occurred_at → 版本 → 序号` 排序；跨批的严格有序需消费侧按
   `aggregateVersion` 处理乱序（S2-02）。
6. **配置陷阱**：`confirm-timeout` 必须小于 `lease-duration`，否则确认还没回来租约就到期，
   同一事件会被两个 Worker 同时发布。当前只是注释约束，未做启动期校验（后续可加）。
