# S1-07 验证记录：持久化幂等与提交不确定恢复

> 日期：2026-10-30（AEST）；轮次：`R-20261030-08`。
>
> 结论：**本任务通过**（在"尚未接入真实锁房命令"的范围内）。四条验收均有真实 PostgreSQL 证据；未覆盖项见 §5。

## 1. 环境

| 项 | 值 |
| --- | --- |
| 迁移 | V6 idempotency baseline（V5.2 → V6 增量升级已真实执行） |
| 关键约束 | `uq_idempotency_scope UNIQUE (tenant_id, hotel_id, caller_namespace, command_name, idempotency_key)`；`fk_inventory_ledger_operation`（流水必须指向真实操作） |
| 档案 | `integration`（真实库）/ `test`（无库，集成用例跳过） |

## 2. 门禁命令与实际输出

```bash
cd openbackupsaas && ./mvnw -o -B -ntp -q -DskipTests compile   # → 退出码 0

PMS_IT_DB=true ./mvnw -B -ntp test
# → Tests run: 105, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
#   本轮新增 8 个：IdempotencyIntegrationTest

./mvnw -B -ntp test                     # 无数据库
# → 105 run / 86 skipped / 0 failure → BUILD SUCCESS

PMS_IT_DB=true ./mvnw -o -B -ntp verify
# → Tests run: 105, Failures: 0, Errors: 0 → BUILD SUCCESS（15.5s）
```

## 3. 验收项对照

| 规划 S1-07 验收项 | 证据 | 结论 |
| --- | --- | --- |
| 同键同内容重试返回同一业务结果 | `sameKeySamePayloadReplaysOriginalResult`（第二次 `replayed=true`、`resultRef` 相同、占用仍 1 行、流水仍 3 行） | 通过 |
| 同键不同内容拒绝 | `sameKeyDifferentPayloadIsRejected`（改区间后同键 → 409，且不产生任何业务效果） | 通过 |
| 并发同键只产生一次效果 | `concurrentSameKeyProducesOneEffectOnly`（两线程同键，恰好一个真正执行，占用 1 行） | 通过 |
| 提交成功但响应丢失可恢复结果 | `committedButResponseLostCanBeRecovered`（按 operationId 查回 SUCCEEDED 与结果引用；用原键重试得到同一引用、业务未再执行） | 通过 |
| 保留期不得早于合法重试窗口 | Mapper 无 DELETE 语句；保留期与清理策略留 S6-03 定案 | 部分（无清理即不会误删；策略未定） |
| 附加：摘要规范化 | `payloadFieldOrderDoesNotBreakTheDigest`（字段顺序不同摘要相同，内容不同摘要不同） | 通过 |
| 附加：流水必须指向真实操作 | `ledgerOperationMustReferenceARealOperation`（虚构 operation_id 的直插被外键拒绝） | 通过 |
| 附加：跨租户不可见 | `operationLookupIsTenantScoped`（别的租户查 → 404） | 通过 |

## 4. 时间线与状态机

```
事务开始
  → INSERT idempotency_record (IN_PROGRESS)   ← 唯一键占位，并发第二个必然失败
  → 业务：占用 + 逐晚流水 + 审计 + Outbox
  → UPDATE ... SUCCEEDED (result_ref)         ← 与业务同一事务提交
提交
```

- 提交成功但响应丢失 → 记录已是 SUCCEEDED，重试能查回同一个结果引用。
- 业务失败回滚 → IN_PROGRESS 行随之消失，重试从零开始，**不会有"记录说在做、业务其实没做"的谎言**。
- 因此**不在 catch 里补写 FAILED**：那时事务必然回滚，补写也留不下。终态失败只对"确定无副作用"的拒绝使用（`recordRejected`）。

## 5. 未覆盖（不得据此宣称通过）

| 项 | 原因 | 归属任务 |
| --- | --- | --- |
| 真实命令（锁房/确认/取消）接入幂等 | 命令本身未实现，本轮用测试命令验证组件 | S1-08/S1-09 |
| 提交不确定窗口的故障注入（进程在提交瞬间崩溃） | 需要故障注入与恢复脚本 | S2-08 |
| 幂等记录清理与保留期 | 策略未定；目前不清理即不会误删 | S6-03 |
| Inbox 侧幂等 | — | S2-02 |
| 高并发下唯一键冲突的错误映射压测 | 需要并发压测 | S1-13 |

## 6. 本轮发现

1. **别靠"捕获唯一键异常"判断重复**：PostgreSQL 一旦语句报错就把整个事务标记为中止，后续查询全部报 `current transaction is aborted`。改为 `INSERT ... ON CONFLICT DO NOTHING` 看影响行数——既不用 savepoint，也不会污染事务。
2. **加外键前必须确认存量数据**：V6 给 `inventory_ledger.operation_id` 加外键时，本地一次性集群里还留着前几轮的流水行，迁移直接失败（表现为 Spring 上下文加载失败，很难一眼看出是迁移问题）。本地集群要先清理再跑，CI 是空库所以不受影响。
3. **包装类型坑第三次出现**：`IdempotencyRecord.hashVersion` 用 `int`，读取时 `NoSuchMethodException`。规则已经写在 §7 第 14 条，仍然漏掉——record 建好后应第一时间确认所有数值列都是包装类型。
4. **摘要必须规范化**：字段顺序不同要算出同一摘要，否则幂等会被序列化差异击穿；`hash_version` 让"换算法"自动变成"同键不同内容"而被拒绝，而不是静默放行。
5. **FAILED 的边界**：只有确定没有副作用的拒绝才能记 FAILED；执行中失败的要允许重试，记了反而会把合法重试挡掉。
