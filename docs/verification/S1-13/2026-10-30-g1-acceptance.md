# G1 交易内核门禁验收记录（S1-13）

- 日期：2026-10-30（AEST）
- 轮次：`R-20261030-15`
- 环境：macOS 本地（JDK 21 / PostgreSQL 17）+ GitHub Actions `ubuntu-24.04`
- 结论：**技术条件满足，建议开审 G1**。S1-01—S1-13 全部 DONE；T01—T07、T09、适用 T14 全部通过；本地与 CI 均 183 测试 / 0 失败 / 0 跳过。

> 开审不等于放行：阶段 0 的 S0-05—S0-09 仍为 TODO、DEC-01—DEC-16 未冻结（见 §6 遗留风险）。
> 本记录只回答"交易内核自身是否可靠"，不回答"业务规则是否定案"。

---

## 1. 门禁证据

| # | 命令 | 结果 |
| --- | --- | --- |
| 1 | `PMS_IT_DB=true ./mvnw -B -ntp clean verify` | `Tests run: 183, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**（173 → 183） |
| 2 | `./mvnw -B -ntp test`（无库） | 183 run / 158 skipped / 0 failure → BUILD SUCCESS |
| 3 | CI `backend` @ `5539d38` | [run 41025391729](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41025391729) **success**，9 步全绿，job 84 秒，`Tests run: 183 / 0 failure / 0 skipped`，断言 `tests=183 skipped=0` |

## 2. 场景覆盖表（规划 §7 测试矩阵，G1 部分）

| 用例 | 场景 | 证据（测试类#方法） | 核验结果 |
| --- | --- | --- | --- |
| T01 | 同房同区间并发抢订 | `TransactionKernelAcceptanceTest#t01_...TenRounds`；`ReservationCommandIntegrationTest#concurrentHotspotLeavesExactlyOneWinner`；`RoomOccupancyIntegrationTest#concurrentOverlappingHoldsLeaveExactlyOneWinner` | 8 并发 × 10 轮，每轮恰好 1 成功 / 7 被 409 拒绝；六方一致（订单 1 / 行 1 / 占用 1 / 流水 3×(-1) / 审计 1 / 事件 1 / 幂等 SUCCEEDED 指向本单） |
| T02 | 相邻日期、空/无界/非法范围 | `RoomOccupancyIntegrationTest#adjacentRangesAreAllowed`、`#invalidRangesRejectedByDatabase`；`ReservationCommandIntegrationTest#reversedDateRangeIsRejected` | 相邻半开区间共存；空/无界/反转区间被数据库 CHECK 拒绝；退房日接下一单可售（S1-12 也覆盖） |
| T03 | 多房多晚仅一个资源冲突 | `TransactionKernelAcceptanceTest#t03_...RollsBackWholeTransaction`；`ReservationCommandIntegrationTest#anyLineUnavailableRollsBackWholeOrder` | 需求 2 间只剩 1 间 → 整单 409；订单/行/占用/流水/审计均无多余行，无残留 `IN_PROGRESS` 幂等记录 |
| T04 | 同键同内容 / 同键不同内容 | `IdempotencyIntegrationTest#sameKeySamePayloadReplaysOriginalResult`、`#sameKeyDifferentPayloadIsRejected`、`#concurrentSameKeyProducesOneEffectOnly` | 同键重放返回原结果引用；内容不同拒绝；并发同键只产生一次效果 |
| T05 | 提交成功但响应/连接丢失 | `TransactionKernelAcceptanceTest#t05_...WithoutSecondBooking`；`IdempotencyIntegrationTest#committedButResponseLostCanBeRecovered` | 丢弃响应后同键重发拿回同一订单 id；不产生第二张订单；同键不同内容被拒且原单不变 |
| T06 | 确认、取消、过期任务同时执行 | `TransactionKernelAcceptanceTest#t06_...OneConsistentFinalState`；`ReservationStateIntegrationTest#concurrentConfirmAndCancelOnlyApplyLegalTransition`；`ReservationExpiryIntegrationTest#concurrentConfirmAndExpiryKeepJointStateConsistent` | 3 并发 × 5 轮，终态唯一（CONFIRMED/CANCELLED/EXPIRED）；订单↔占用按映射一致；释放恰好覆盖 3 晚且只写一次；确认时不写释放流水 |
| T07 | 维修创建与锁房同时提交 | `RoomOccupancyIntegrationTest#reservationAndMaintenanceCannotHoldTheSameRange`、`#concurrentOverlappingHoldsLeaveExactlyOneWinner`、`#directDatabaseInsertCannotBypassExclusion` | 同一区间不能同时维修与预订；**裸 SQL 直插同样被 GiST 排斥拒绝**——绕不过应用层不是靠自觉 |
| T09 | 各晚有空房但没有连续可用同一房 | `InventoryIntegrationTest#cannotPromiseWholeStayWhenNoRoomIsContiguous` | 每晚余量 ≥1 但 `bookableRooms = 0`；可售判断不用每日最小值 |
| T14 | 改 tenant_id / 对象 ID、批量混入、连接池复用 | `CrossTenantRegressionTest`（6 例）；`AuthIntegrationTest`（跨租户 id、撤权、线程复用） | 换订单 id → 404 且响应不含对方单号；跨租户门店 → 404（不是 403）；同租户超范围 → 403；同租户双店都在范围内时仍看不到别店订单；请求体伪造 `tenantId`/`hotelId` 不改变归属；线程复用后无残留上下文 |

**规划 §8.3 的否决条件逐条核对**：重复占用（无）、半提交（无）、重复释放（无）、租户越权（无）——四条均有对应用例直接覆盖。

## 3. 这轮新增的核验方式：六方一致

以往每个测试只看自己那一块（占用对不对、流水对不对）。这轮在 T01/T03/T05 里加了统一核验 `assertKernelConsistent`，**一次成功交易必须在六处指向同一结论**：

| 表 | 期望 |
| --- | --- |
| `reservation` / `reservation_line` | 各 1 行，状态与场景一致 |
| `room_occupancy` | 恰好 1 条 `HELD`（或终态后的 1 条 `RELEASED`） |
| `inventory_ledger` | 每晚 1 条 `-1`，净值 = −晚数；释放后每晚 1 条 `+1` |
| `audit_log` | `reservation.create` 恰好 1 条，目标指向本单 |
| `outbox_event` | `ReservationCreated` 恰好 1 条，聚合版本一致 |
| `idempotency_record` | `SUCCEEDED` 且 `result_ref` 指向本单 |

为什么值得单列：订单存在但流水少写一晚、或事件写了两条，在可售与对账上就是另一个事实；单表断言发现不了这种"部分正确"。

## 4. 清理机制重写（拖了三轮的问题）

**问题史**：`integration-cleanup.sql` 是一串手写 `DELETE`。新增表忘记加一行，整批用例就会拿脏库跑，而报错只说"第 N 条语句失败"。这件事在 R-20261030-10、-13、-14 三次复现，其中一次让 7 个用例**连跑都没跑起来**。

**现在的机制**（`support/DatabaseCleaner`）：

1. 从 `pg_constraint` 读外键依赖（不是 `information_schema.constraint_column_usage`——实测它对复合外键匹配不到，返回 0 行，会让排序退化成表名字母序）；
2. 按"没有剩余表引用它"的顺序逐个删除（child 先于 parent）；
3. 逐表捕获异常并**点名是哪张表**；
4. 删完断言全库为空——漏清理从"静默污染"变成"当场失败"。

15 个集成测试类改为继承 `PostgresIntegrationTest`，全部删除 `@Sql`。旧脚本保留但标记废弃（历史记录仍有引用）。

**反向验证**：临时用迁移账号建 `leak_probe` 表并插入 1 行，跑一个用例 → 测试照常通过，且该表被清空（0 行），**全程没有改动任何脚本**。这正是要证明的：以后加表不需要再交一次学费。

## 5. 本轮发现的错误（不掩盖）

### 5.1 拓扑方向写反，先删了 `tenant`

第一版按"该表不依赖任何剩余表就删"排序，结果 `tenant` 不引用任何人，被第一个删掉，而 `hotel` 还在引用它 → `fk_hotel_tenant` 违反，87 个用例连锁报错。

正确条件与"它依赖谁"无关，而是"**还有谁引用它**"：没有剩余子表才能删。这个错误值得记下来——拓扑排序的方向感在"引用"与"被引用"之间极易搞反，而症状（外键违反）看起来像数据问题，不像排序问题。

### 5.2 联合状态核对不能用字符串相等

T06 首跑在 `CANCELLED vs RELEASED` 上失败。**这不是缺陷**：订单状态机用 `CANCELLED`，占用状态机用 `RELEASED`，两者本来就不同名。

改成显式映射（CONFIRMED→CONFIRMED、CANCELLED→RELEASED、EXPIRED→EXPIRED）后通过。这条正好落在 S1-09 交付的"联合状态核对规则"上：**规则必须写成映射，而不是"两边相等"**——后者会把正确结果判成错误，比漏测更危险（会诱导人去改本来对的东西）。

### 5.3 JUnit 5 的条件注解不会被继承（本轮引入又修掉的回归）

把 15 个测试类的 `@EnabledIfEnvironmentVariable(named = "PMS_IT_DB", matches = "true")`
上收到基类时，条件**失效了**：`@EnabledIf*` 系列没有 `@Inherited`，写在基类上等于没写。
表现是"无库环境下 13 个需要数据库的用例仍然执行并连上了本机数据库"——
在有库的机器上完全看不出问题，只有刻意在无库环境跑才会暴露。

修法：自定义 `IntegrationDbEnabledCondition`（`ExecutionCondition`），通过基类上的
`@ExtendWith` 注册；`@ExtendWith` 是可继承的。

修复后双向验证：

| 环境 | 结果 |
| --- | --- |
| 未设 `PMS_IT_DB` | `Tests run: 183, Failures: 0, Errors: 0, Skipped: 158`（158 个需库用例真跳过） |
| `PMS_IT_DB=true` | `Tests run: 183, Failures: 0, Errors: 0, Skipped: 0` |

这条值得记的原因：**"跳过"的失效是静默的**。条件不生效时没有任何报错，只会在缺少数据库的
环境里变成一堆连接失败；而如果那台机器恰好有库，测试会照常通过，问题永远不会被发现。
凡是"环境开关"，都要在两个环境下各验一次。

## 6. 未覆盖与遗留风险

### 6.1 转后续门禁的矩阵项

| 用例 | 内容 | 归属 |
| --- | --- | --- |
| T08 | 多 Worker 领取后崩溃/超时重领 | G2（S2-03）。S1-10 已覆盖"领取后崩溃、下一轮重新领取"的等价场景 |
| T10 | 清空投影、重建时有新交易 | G2（S2-05）。S1-12 已覆盖"清空后重建一致"，但**重建与新交易并发**未测 |
| T11 / T12 / T13 | Outbox 发送前后退出、消费不 ACK、旧事件与版本缺口 | G2（S2-01/S2-02/S2-08）。发布器尚未实现 |

### 6.2 遗留风险（不影响 G1 判定，但影响后续返工成本）

1. **阶段 0 未完成**：S0-05（营业日与未分房销售）、S0-06（OTA）、S0-07（支付）、S0-08（容量与 SLO）、S0-09（G0 审查）仍为 TODO；DEC-01—DEC-16 未冻结。其中 DEC-04（部分履约）与 DEC-08（No-show 计费）会直接改写状态机与账务，届时 S1-09/S1-10 需要返工。
2. **无性能基线**：本轮只做正确性与并发安全，没测吞吐与 P95。热点交易的性能 SLO 在 S6-01（T23），届时才能判断"能扛多少并发"。
3. **并发规模有限**：T01 用 8 线程、T06 用 3 线程，验证的是"竞争下的正确性"，不是"高并发下的退化行为"。真正的容量验证需要压测环境。
4. **未做故障注入**：进程被杀、网络分区、数据库主备切换属 S2-08（T24），本轮没有模拟。

## 7. 复现方式

```bash
# 起一次性 PostgreSQL（若未起）
cd openbackupsaas && ./scripts/dev-db.sh start

# 全量（含 158 个需真实数据库的用例）
cd openbackupsaas && PMS_IT_DB=true ./mvnw -B -ntp clean verify

# 只跑本轮新增的两组验收
cd openbackupsaas && PMS_IT_DB=true ./mvnw -B -ntp test -Dtest='TransactionKernelAcceptanceTest,CrossTenantRegressionTest'
```

并发用例的输入完全固定（固定延迟、固定轮次、固定线程数），不依赖随机种子；T01 连续 10 轮、T06 连续 5 轮全绿才算通过。
