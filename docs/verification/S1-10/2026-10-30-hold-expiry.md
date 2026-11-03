# S1-10 超时领取与幂等释放 —— 验证记录

- 日期：2026-10-30（AEST）
- 轮次：`R-20261030-11`
- 环境：macOS / JDK 21 / PostgreSQL 17（`scripts/dev-db.sh`，端口 55432，应用账号 `pms_app`、迁移账号 `pms_migrator`）
- 结论：**通过**（后端 132 测试 / 0 失败，其中 109 个需真实 PostgreSQL；运行态调度冒烟成功）

---

## 1. 门禁命令与实际输出

| # | 命令 | 结果 |
| --- | --- | --- |
| 1 | `./mvnw -o -q -DskipTests compile`（`openbackupsaas/`） | 退出码 0 |
| 2 | `PMS_IT_DB=true ./mvnw -o -B -ntp test -Dtest=ReservationExpiryIntegrationTest` | `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**（首版 10 例） |
| 3 | 同上（补 `workerSkipsRowsAlreadyClaimedByAnotherWorker` 后） | `Tests run: 11, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS** |
| 4 | `PMS_IT_DB=true ./mvnw -o -B -ntp verify` | `Tests run: 132, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**（本轮新增 11 例，上一轮 121） |
| 5 | `./mvnw -B -ntp test`（无库） | `Tests run: 132, Failures: 0, Errors: 0, Skipped: 109` → **BUILD SUCCESS**（需库用例真跳过，不伪通过） |
| 6 | `psql -c "select version, success from flyway_schema_history"` | `1 / 2 / 3 / 4 / 5 / 5.1 / 5.2 / 6 / 6.1 / 6.2 / 6.3 / 6.4` 共 12 行全部 `t`；**本轮无新迁移**（见 §4.3） |

## 2. 本轮新增用例

`ReservationExpiryIntegrationTest`（真实 PostgreSQL，11 例）

| 用例 | 对应验收点 | 断言 |
| --- | --- | --- |
| `overdueHoldStillBlocksRoomUntilStatusMigrates` | 验收 4 | `expires_at` 已过但状态仍 HELD → 房间**不可售**；释放后订单/行/占用三方均 EXPIRED、房间可售、两晚各一条 +1 对冲流水、审计 `reservation.expire` 的 `actor_type = SYSTEM` |
| `notYetDueHoldIsLeftAlone` | 基本 | 未到期订单不被扫描（scanned = 0），状态与占用不变，无到期审计 |
| `multiRoomReservationExpiresOnlyWhenEveryHoldIsOverdue` | 整单语义 | 只让一条 HELD 到期时**不释放**（否则会连未到期的房间一起放出去）；全部到期后才整单迁移 2 条占用 |
| `twoWorkersScanningSameBatchReleaseExactlyOnce` | 验收 1 | 两 Worker 用 `CyclicBarrier` 同时起跑，合计释放恰为 1；占用量始终 1 行；+1 流水只有一组（2 条）；审计 1 条 |
| `workerSkipsRowsAlreadyClaimedByAnotherWorker` | 验收 1（精确） | A 领取后停在事务里不提交，B 必须**立刻跳过**（scanned = 0，不阻塞）；A 回滚（模拟崩溃）后订单仍 PENDING，下一轮可正常释放 |
| `concurrentConfirmAndExpiryKeepJointStateConsistent` | 验收 2 | 确认与到期并发后终态 ∈ {CONFIRMED, EXPIRED}，且**订单状态 = 占用状态**；占用量恒为 1 行、行恒为 1 条 |
| `confirmedReservationIsNotTouchedByExpiryScan` | 边界 | 已确认单即使时间过点也不被扫描（占用已是 CONFIRMED，不匹配 HELD） |
| `cancelledReservationIsNotExpiredAgain` | 边界 | 已取消单（占用 RELEASED）不被二次处理 |
| `interruptedBatchLeavesNoHalfStateAndNextRoundRecovers` | 验收 3 | 外层事务 `setRollbackOnly` 模拟"处理到一半进程挂掉"：订单仍 PENDING、占用仍 HELD、无 +1 流水、无到期审计、无 `reservation.expire` 幂等记录；随后重跑可正常释放 |
| `secondRoundWithNoDueWorkDoesNothing` | 幂等 | 已释放的单不会被二次处理（占用 1 行、审计 1 条不变） |
| `batchSizeCapsWorkPerRound` | 批次限制 | 3 单到期，`batch(2)` 释放 2，再 `batch(2)` 释放 1，第三次 scanned = 0 |

### 2.1 反向验证（证明用例不是摆设）

只跑 `workerSkipsRowsAlreadyClaimedByAnotherWorker`，临时把 SQL 里的 `FOR UPDATE OF r SKIP LOCKED` 改成 `FOR UPDATE OF r`：

```
[ERROR] Tests run: 1, Failures: 0, Errors: 1 ... Time elapsed: 23.35 s <<< FAILURE!
java.util.concurrent.ExecutionException: org.opentest4j.AssertionFailedError: 并发协调超时
```

即：没有 `SKIP LOCKED` 时 B 会阻塞在 A 的行锁上 20 秒 → 用例失败。这证明该用例真的在验证领取语义，而不是"两个线程先后跑一遍也能过"。验证后已恢复关键字（`grep` 确认第 58 行为 `FOR UPDATE OF r SKIP LOCKED`）。

## 3. 运行态冒烟（真实调度器闭环）

- 启动：`./mvnw -o -B -ntp spring-boot:run`，`PMS_HOLD_EXPIRY_SCAN_INTERVAL=PT3S`、`PMS_HOLD_EXPIRY_INITIAL_DELAY=PT2S`；profile 回落为 `local`（日志：`No active profile set, falling back to 1 default profile: "local"`）；`Started openFrameworksApplication in 2.569 seconds`
- 造数：把一条已完成订单用 SQL 重置为 `PENDING` + 占用 `HELD` + `expires_at = now() - interval '1 hour'`
- 观测：启动后 2.1 s 首次扫描 → 日志 `到期承接扫描：领取 1 单，释放 1 单，跳过 0 单`；**4 秒内**轮询到 `reservation.status = EXPIRED`，占用 `EXPIRED`
- 落库核对：
  - `audit_log`：`action = reservation.expire`、`actor_type = SYSTEM`、`reason = HOLD_TIMEOUT`、`releasedOccupancies = 1`
  - `outbox_event`：`ReservationExpired`、`aggregate_type = reservation`、`payload.to = EXPIRED`
  - `idempotency_record`：`command_name = reservation.expire`、`status = SUCCEEDED`、`result_ref` 非空

## 4. 失败、歧义与处置（不掩盖）

### 4.1 首版扫描 SQL 用不上 V4 建的索引

最初写成"遍历 PENDING 单 + 相关子查询 EXISTS 占用到期"，`EXPLAIN` 显示规划器改走 `idx_room_occupancy_room`，V4 专门为这个任务建的 `idx_room_occupancy_expiring`（`(expires_at, reservation_id) WHERE kind='RESERVATION' AND status='HELD'`）完全没被使用——扫描量与 PENDING 单总量成正比，而不是与"过期未释放的占用数"成正比。

改为"占用驱动 + 订单定位"（派生表 `SELECT DISTINCT o.reservation_id ... WHERE expires_at <= now()`，再 JOIN 订单）：

```
Limit
  -> LockRows
       -> Index Only Scan using idx_room_occupancy_expiring on room_occupancy o
            Index Cond: (expires_at <= now())
```

`DISTINCT` 必须放在派生表里：外层一旦出现 `DISTINCT` 就与 `FOR UPDATE` 冲突（PG 直接报错）。改后 11 例全绿。

注：当前测试库表很小，规划器默认仍选 seq scan（0 成本更便宜）；上面的索引命中是在 `SET enable_seqscan=off` 下观测的，用来证明索引对该谓词**可用**，真实数据量下的选择留给 S6-01 容量测试。

### 4.2 `Limit` 在 `LockRows` 之上

`EXPLAIN` 的结构是 `Limit -> LockRows -> …`，即先取够 `LIMIT` 行再逐行尝试加锁。这意味着 `SKIP LOCKED` **跳过**已锁行后能继续向后取行补足批次，而不是"跳过就少了"。这是队列模式想要的行为；如果哪天计划变成 `LockRows -> Limit`，批大小就不再是"最多处理 N 单"而是"最多尝试 N 行"，需要在 S1-13 的并发验收里重新确认。

### 4.3 本轮没有新增迁移

V4 已经建了 `idx_room_occupancy_expiring` 与 `(status, created_at)` 上的订单索引，`ck_reservation_status` 也已包含 `EXPIRED`，`ck_audit_log_action` 在 V6_4 已放宽到 `[a-z][a-z_]*(\.[a-z][a-z_]*)+`（`reservation.expire` 通过）。因此不为了"每轮都有迁移"去改库。若 S1-13 实测扫描成为瓶颈，再加 partial index（`reservation (id) WHERE status = 'PENDING'`）。

### 4.4 环境变量 `PMS_SERVER_PORT` 在沙箱下未生效

冒烟时指定 `PMS_SERVER_PORT=18082`，实际监听 **55839**；改用 `-Dspring-boot.run.arguments=--server.port=18099` 明确生效（`Tomcat initialized with port 18099`）。这是启动方式与环境的问题，不是配置缺陷：`application.properties` 仍是 `server.port=${PMS_SERVER_PORT:8080}`，进程直接启动（`java -jar`）时环境变量正常工作。本轮验证不依赖 HTTP 端口（结论全部从数据库状态读出），记录在此以免下次误判为"端口配置丢了"。

### 4.5 已知边界（本轮不做）

1. **批内同生共死**：一次批处理是一个事务，批内任一单异常会让整批回滚（不是逐单回滚）。这是"领取锁必须持到写入完成"的必然结果；下一轮会重新领到这些行。若某单持续异常会形成整批空转，需在 S2-04 死信/重放里加"单条失败隔离"。
2. **只有 PENDING 单会被释放**：已确认单超时是否释放属业务规则（DEC-08），本轮不顺手放开。
3. **没有 HELD 占用的 PENDING 单不会被收口**：这类"僵尸聚合状态"不在本任务职责内，归 S2-05 内部对账。
4. 未覆盖：多实例真实部署（靠 `SKIP LOCKED` + 条件更新，逻辑上已由用例覆盖）、调度器与外部定时器（K8s CronJob）二选一的部署决策、到期通知与渠道同步（S2-01 之后）。
