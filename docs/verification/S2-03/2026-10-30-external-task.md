# S2-03 持久化任务与外部调用状态：验证记录

> 日期：2026-10-30（AEST）；轮次：`R-20261030-18`；任务：S2-03（P0）；状态：**DONE**
> 依据：[规划 §S2-03](../企业级%20PMS%20开发任务规划.md)、[ADR-003](../adr/ADR-003-atomic-command-transaction.md)（外部 HTTP 不在核心事务内）
> 代码：`integration/{domain,mapper,service}`、`db/migration/V10`

## 1. 交付物

| 交付项 | 落点 | 说明 |
| --- | --- | --- |
| 任务表 | `external_task`（V10） | 状态机、尝试次数、租约、退避、错误分类、`result_ref`；`(tenant, type, 幂等键)` 未结任务唯一 |
| 尝试台账 | `external_task_attempt`（V10） | 每次**真实调用**一行；随任务级联删除 |
| 调度器 | `ExternalTaskWorker` | Worker 角色 + 可配开关，`fixedDelay` 轮询 |
| 执行器 | `ExternalTaskExecutor` | 领取 → 渠道门 → （未知先查询）→ 调用 → 分类 → 结算 |
| 查询与补偿接口 | `ExternalTaskService` | 入队、任务与尝试查询、**人工结案**（仅 `MANUAL_REVIEW` 可改判） |
| 渠道策略 | `ChannelPolicyRegistry` + `ChannelGate` | 每渠道次数/间隔/超时/熔断阈值与冷却；限流 + 熔断 |
| 状态图 | `TaskStatus` 类注释 | PENDING / RUNNING / SUCCEEDED / FAILED / MANUAL_REVIEW / DEAD |

## 2. 四类结果，四种处置（这轮的核心）

| 分类 | 含义 | 处置 | 为什么不能一视同仁 |
| --- | --- | --- | --- |
| `SUCCESS` | 对方明确成功 | `SUCCEEDED` | — |
| `PERMANENT` | 对方明确拒绝（4xx 业务错误） | `FAILED`，**终局** | 再试一万次也一样，重试只是浪费配额 |
| `RETRYABLE` / `RATE_LIMITED` | 确定没生效 | 退避重试；次数耗尽转 `DEAD` | 重发是安全的 |
| `UNKNOWN` | **可能已生效**（超时、连接中断） | 能查 → **先查**；不能查 → `MANUAL_REVIEW` | 当失败重发 = 重复扣款/重复占房 |

`UNKNOWN` 必须是一等公民：把它并进"失败"就会重复执行，并进"成功"就会漏单。
**兜底也按 UNKNOWN 处理**——处理器抛出的未捕获异常一律视为未知，宁可转人工，不可自动重发。

## 3. 关键设计决定

### 3.1 执行器整体没有 `@Transactional`

外部 HTTP 在事务之外执行。若把"读任务—调用—写状态"包进一个事务：一是网络等待期间一直持有行锁；
二是回滚会把"这次调用真的发出去了"这条事实一起抹掉——第七次超时之后没人知道对方到底收没收到。
所以每一步都是独立语句，**用租约而不是事务**保证同一时刻只有一个执行者。

### 3.2 "尝试"只统计真实调用

限流/熔断跳过的轮次只释放租约（`release`），不加 `attempts`。
否则"对方让我慢一点"会变成"我没机会再试"——重试预算被无意义的等待吃光。

### 3.3 崩溃恢复靠领取条件，不靠清理任务

领取条件包含两条路径：`PENDING 且退避到期`，或 **`RUNNING 且租约过期`**。
只认前者会让执行器崩溃过的任务永远停在 `RUNNING`。

异常路径**不主动释放租约**：若异常发生在调用之后，释放会让别的实例立刻重发一次。
让租约自然过期，是最保守也最安全的选择。

### 3.4 退避带抖动

基数 × 2^(n-1)，截断到上限后再乘 0.8–1.2 的抖动。
没有抖动时，同一批任务会在同一时刻集体重试，把刚恢复的对方再打挂一次，形成"恢复—打挂"循环。
`jitter` 可注入，测试断言的是**区间**而不是某个具体秒数。

## 4. 门禁实测

```
PMS_IT_DB=true PMS_IT_RABBIT=true ./mvnw -o -B -ntp clean verify
→ Tests run: 222, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
```

| 项 | 结果 |
| --- | --- |
| 全量（真实 PostgreSQL + 真实 RabbitMQ） | **222 / 0 / 0 / 0**（上轮 211；新增 11） |
| 无库无 Broker | 222 run / **197 skipped** / 0 failure |
| 迁移 | `flyway_schema_history` 最新 `10`，真实应用 |
| 真实 CI | [run 41058995729](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41058995729) @ `573f0f5` → **success**，99 秒，`tests=222 skipped=0 failures=0` |

## 5. 反向验证

把"未知先查询"分支临时关掉（`if (false && …)`）后，核心用例立即失败：

```
AssertionFailedError: 关键：查询确认成功后不得再调用一次（那就是重复执行）
  ==> expected: <1> but was: <2>
```

同一笔任务被真实调用了两次——正是这一层要防的事故。恢复后 11 例全绿。

## 6. 逐条对照验收

| 验收要求 | 对应用例 | 结果 |
| --- | --- | --- |
| 执行器崩溃后可重新领取 | `crashRecoveryReclaimsRunningTaskWithExpiredLease`（租约有效期内不得抢、过期后可领） | ✅ |
| 未知结果先查询，或按协议安全重试 | `unknownWithQuerySupportQueriesInsteadOfResending`、`unknownThenQueryNotFoundAllowsSafeResend` | ✅ |
| 不具备幂等/查询能力的操作转人工 | `unknownWithoutQuerySupportGoesManualReview` | ✅ |
| 永久错误不会无限重试 | `permanentErrorIsTerminalAndNotRetried`（第二轮 claimed=0、调用次数保持 1） | ✅ |
| 退避与抖动 | `retryableSchedulesBackoffWithinJitterBounds`（基数 10 秒 → 实测落在 7–13 秒） | ✅ |
| 最大次数 | `maxAttemptsExhaustedGoesDead` | ✅ |
| 每渠道限流 | `rateLimitedChannelSkipsWithoutConsumingAttempts`（跳过不消耗尝试） | ✅ |
| 熔断隔离 | `circuitOpensAfterConsecutiveFailuresAndSkipsFurtherCalls` | ✅ |
| 人工处置边界 | `manualResolutionOnlyFromManualReview`（`PENDING` 不得被改判） | ✅ |

## 7. 未覆盖与边界

1. **单机内存限流/熔断**：多实例部署时每实例各自计数，实际总 QPS 会翻倍。
   分布式限流需要共享状态（Redis/网关），而 Redis 按 ADR-011 首期不引入——
   定位是"单实例自保 + 减少无谓调用"，精确配额应在网关层做（S6）。
2. **`timeout` 只作为策略值暴露**，尚未在框架层强制中断调用：真实超时控制要由处理器（HTTP 客户端）
   自己设置超时并翻译成 `UNKNOWN`。框架层不做线程中断——粗暴中断会让"到底发没发出去"更说不清。
3. **没有真实渠道处理器**：OTA（L 域）与支付（K 域）都缺授权与协议（DEC-11），
   本轮交付的是框架 + SPI，故生产环境当前不存在任何 `ExternalTaskHandler` 实现。
   这一点在进度台账里写明，不算"已接入渠道"。
4. **管理 API / 死信重放**：`ExternalTaskService` 只到服务层，受控 REST/CLI、权限与复核归 S2-04。
   现在不开 HTTP 口子是有意的——在没有权限模型时先开一个能改资金状态的口子更危险。
5. **人工处置不写审计**：`last_error` 里记了处置人与原因，正式处置记录与前后状态快照归 S2-04。
6. **未做端到端的真实 HTTP 冒烟**：本轮无真实渠道可调；处理器 SPI 由可编程桩覆盖。
   等 L 域接入沙箱后必须补一次真实 4xx/5xx/超时的分类验证（否则分类逻辑只是"按约定"）。
