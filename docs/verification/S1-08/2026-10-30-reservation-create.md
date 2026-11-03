# S1-08 原子锁房与订单创建 — 验证记录

**日期**：2026-10-30（AEST） · **轮次**：R-20261030-09 · **状态**：DONE

## 做了什么

把"建单 + 自动选房 + 写 HELD 占用"收进**一个短事务**，并对客户端幂等。实现落点：

- `reservation/service/ReservationCommandService`：命令入口。
- `reservation/domain/ReservationConflictException`：结构化冲突（哪一行 / 房型 / 日期 / 需要 / 可用间数），不携带房间 id（避免库存探测）。
- `reservation/web/ReservationController`：`POST /api/v1/hotels/{hotelId}/reservations`（`reservation:write`，经 `HotelAccess` + `X-Caller-Namespace`）。
- `reservation/web/{CreateReservationRequest,ReservationView}`：请求 / 响应投影。
- `OccupancyService.writeHeldOccupancy`（包级）：命令级占用写入——**不**自己锁房间、**不**自己管幂等，锁序与幂等由命令层统一负责。
- 迁移 `V6_1__reservation_permissions.sql`：`reservation:write` / `reservation:read` 权限点。

## 设计要点

1. **原子性**：多房多晚要么全成、要么全败。任一房型在所选区间挑不到可用房间，整单在业务层抛结构化冲突（409），事务回滚，不留"部分房间被占"的半截状态。
2. **统一锁序（S0-04）**：先插订单（聚合根）→ 按房间 id 升序一次性锁**全部**本次要用的房间 → 再写任何占用。锁在写占用之前拿齐且顺序固定，避免两个并发建单交叉持锁死锁。
3. **锁后重核**：所选房间若在"选中 → 加锁"之间被别人抢走，锁定后重查 `findAvailableRooms` 必然查不到，转结构化冲突，而不是放任到 EXCLUDE 约束才报含糊错误。
4. **幂等**：经 `IdempotencyService.execute`；同一作用域键（含客户端幂等键）+ 同一内容只产生一次效果。提交成功但响应丢失时，重试拿回**同一个订单 id**（resultRef）。
5. **占用流水的 operation_id**：指向本次命令的幂等记录（不每条占用各建一条），与 S1-06 / S1-07 的模型一致。

## 验证命令与结果

| 命令 | 结果 |
|---|---|
| `PMS_IT_DB=true ./mvnw -o -B verify` | **110 tests / 0 failure / BUILD SUCCESS（12.2s）**，本轮新增 5 个 |
| `./mvnw -o -B test`（无库） | 110 run / **91 skipped** / 0 failure（上下文加载成功，控制器接线隐含通过） |
| `flyway_schema_history` | V1—V6.1 全 success（V6.1 于本轮增量应用） |

## 关键用例（`ReservationCommandIntegrationTest`，真实 PostgreSQL）

- **多房多晚原子**：2 间房 × 3 晚 → 1 订单、2 订单行、2 占用、6 流水行、≥1 事件、≥2 审计。
- **任一冲突整单回滚**：房型 A 有 2 间、房型 B 0 间 → 第二行不满足 → 抛 `ReservationConflictException`（明细指向房型 B、可用 0 间），reservation / line / occupancy / ledger / audit 全部 0 行。
- **幂等重放**：同一键第二次调用拿回同一订单 id，库里仍只有 1 订单、2 占用。
- **并发热点只有一个赢家**：仅 1 间房、两个并发建单 → 恰好 1 个成功、1 个结构化冲突，库里仅 1 订单、1 占用，不死锁、不双卖。
- **日期非法**：`StayRange.of(OUT, IN)` 与 `StayRange.of(IN, IN)` 均被 `StayRange` 构造拒绝。

## 本轮发现 / 待下轮

- `OccupancyService.hold`（独立占房路径）重构为委托 `writeHeldOccupancy` + 自带系统级幂等记录；S1-05 的 `RoomOccupancyIntegrationTest` 不受影响（签名未变）。
- 建单的"订单创建"审计改用新动作 `reservation.create`（不再复用 `occupancy.hold`）。
- **未覆盖（转下轮）**：确认 / 取消 / No-show（S1-09）、超时释放 Worker（S1-10）、可售查询与投影（S1-12）、订单列表 / 详情接口（S1-11）、HTTP 层 403/404 的 MockMvc 测试（控制器接线已通过上下文加载隐含验证；`HotelAccess` 与 `@PreAuthorize` 路径分别在 S1-04 / S1-03 已覆盖，专用 MockMvc 测试随 S1-11 建立）。
- 客户端命名空间经 `X-Caller-Namespace` 头进入幂等作用域，避免 A 客户端的键被 B 客户端重放顶替。
