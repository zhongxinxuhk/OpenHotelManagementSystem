# S1-06 验证记录：库存流水、审计与 Outbox 原子写入

> 日期：2026-10-30（AEST）；轮次：`R-20261030-07`（进行中收尾）。
>
> 结论：**本任务通过**（在"不含幂等记录与发布器"的范围内）。一次占用写入同时产生占用、逐晚流水、审计与事件；
> 任一步失败四者一起回滚。未覆盖项见 §5。

## 1. 环境

| 项 | 值 |
| --- | --- |
| 迁移 | V5 journal baseline → V5.1 修正流水唯一键 → V5.2 引入 operation_id（三次均为增量升级，已真实执行） |
| 关键约束 | `uq_inventory_ledger_effect UNIQUE (tenant_id, hotel_id, operation_id, room_id, stay_date, effect_kind)` |
| 档案 | `integration`（真实库）/ `test`（无库，集成用例跳过） |

## 2. 门禁命令与实际输出

```bash
cd openbackupsaas && ./mvnw -o -B -ntp -q -DskipTests compile   # → 退出码 0

PMS_IT_DB=true ./mvnw -B -ntp test
# → Tests run: 97, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
#   本轮新增 8 个：JournalIntegrationTest

./mvnw -B -ntp test                     # 无数据库
# → 97 run / 78 skipped / 0 failure → BUILD SUCCESS

PMS_IT_DB=true ./mvnw -o -B -ntp verify
# → Tests run: 97, Failures: 0, Errors: 0 → BUILD SUCCESS
```

## 3. 验收项对照

| 规划 S1-06 验收项 | 证据 | 结论 |
| --- | --- | --- |
| 一笔多房多晚交易能写入多条合法流水 | `holdWritesOneLedgerRowPerNightPlusAuditAndEvent`（4 晚 → 4 行，delta 均为 -1，日期连续）；`blockWritesBlockKindLedger`（封房 2 晚 → 2 行 BLOCK） | 通过（本轮为单房多晚；多房路径等 S1-08 的原子锁房） |
| 重复同一业务效果不重复入账 | `duplicateEffectDoesNotDoublePost`（同一 `operation_id` 重复写同一晚 → 唯一键拒绝，行数不变） | 通过 |
| 注入任意写入失败时占用、流水、审计、Outbox 一起回滚 | `failureAfterBusinessWriteRollsBackEverything`（事务内先写后抛异常 → 四张表均为 0 行）；`conflictingHoldLeavesNoPartialFacts`（冲突失败不留流水与事件） | 通过 |
| 附加：释放是对冲而不是删历史 | `releaseWritesCompensatingRowsWithoutDeletingHistory`（4 行占用 + 4 行对冲，净效果 0，两个操作 id） | 通过 |
| 附加：审计主体来自可信身份 | `auditActorComesFromTrustedContextOtherwiseSystem`（有上下文记 USER + userId；无上下文记 SYSTEM）；`unregisteredAuditActionIsRejected` | 通过 |

## 4. 事件与审计模型

- 事件：`aggregate_type=room_occupancy`、聚合同一版本内 `event_ordinal` 从 1 递增、`schema_version=1`、载荷 jsonb（区间与 id，不含敏感字段）。
- 审计：动作名必须取自 `AuditActions` 目录（未登记直接拒绝），主体类型 USER/SYSTEM/TASK/CHANNEL。
- 只追加：三张表的 Mapper **只有 INSERT 与 SELECT**，没有 update/delete，也没有"改载荷"的方法。

## 5. 未覆盖（不得据此宣称通过）

| 项 | 原因 | 归属任务 |
| --- | --- | --- |
| `operation_id` 指向幂等记录的外键 | 幂等记录未落表 | S1-07 |
| 多房多晚的整单原子（同一操作多房间） | 锁房命令未实现 | S1-08 |
| Outbox 发布、租约、重试、死信 | 只写不发 | S2-01/S2-04 |
| Inbox 去重 | — | S2-02 |
| 投影重建与差异核对（`room_inventory_day`） | 投影未建 | S1-12 |
| 登录/登出/越权审计接入 identity | 本轮只覆盖占用类动作 | 随 S1-11/S3-02 |
| 事件顺序在并发提交下的严格性 | 需要并发压测 | S1-13 |

## 6. 本轮发现

1. **释放不能删行，所以唯一键必须带上方向。** 第一版 `(room, date, kind)` 会把"占用 → 释放 → 再占用"这个**合法业务循环**判成重复入账——等于房间一旦取消就再也卖不出去。最终收敛到字典给的形式 `(operation_id, room, date, kind)`：同一操作重复写被拒，不同操作的循环合法。
2. **`operation_id` 提前于 S1-07 出现是必要的**：没有它，唯一键无论怎么调都会在"防重复"与"允许取消后重售"之间二选一。S1-07 只需补外键，不用改模型。
3. **MyBatis 又是包装类型问题**：record 里用 `int` / `long` 会因 `NoSuchMethodException` 读不出来（S1-02 踩过同一坑），本轮在 `InventoryLedger.delta`、`OutboxEvent.aggregateVersion/eventOrdinal/schemaVersion/attemptCount` 上再次踩到，统一改包装类型。
4. **jsonb 也走文本绑定 + `::jsonb`**（与 daterange 同理）：驱动是 `runtime` 作用域，编译期没有 `PGobject`。
5. **原子性由调用方事务提供，不由 journal 组件提供**：`JournalService` 的方法只是"在同一个事务里追加事实"；调用方不开启事务就没人拦得住半截状态。这一点写在组件注释里，别指望组件自己兜底。
