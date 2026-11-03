# S1-05 验证记录：日期约束、占用排斥与维修互斥

> 日期：2026-10-30（AEST）；轮次：`R-20261030-06`。
>
> 结论：**本任务通过**（在"不含订单命令与幂等记录"的范围内）。占用排斥由数据库保证，且**直接连库插入同样被拒绝**；
> 未覆盖项见 §5，不计入通过范围。

## 1. 环境

| 项 | 值 |
| --- | --- |
| PostgreSQL | 17（独立一次性集群，端口 55432）；`btree_gist` 已装载（由 `pms_migrator` 安装，属主 `pms_migrator`） |
| 迁移 | V1 → V2 → V3 → **V4 occupancy baseline**（V3→V4 增量升级于 02:12 真实执行） |
| 关键约束 | `ex_room_occupancy_effective`： `EXCLUDE USING gist (room_id WITH =, stay_range WITH &&) WHERE status IN ('HELD','CONFIRMED','CHECKED_IN','BLOCKED')` |
| 档案 | `integration`（真实库）/ `test`（无库，集成用例跳过） |

## 2. 门禁命令与实际输出

```bash
cd openbackupsaas && ./mvnw -o -B -ntp -q -DskipTests compile   # → 退出码 0

PMS_IT_DB=true ./mvnw -B -ntp test
# → Tests run: 89, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
#   本轮新增 13 个：RoomOccupancyIntegrationTest

./mvnw -B -ntp test                     # 无数据库
# → 89 run / 70 skipped / 0 failure → BUILD SUCCESS

PMS_IT_DB=true ./mvnw -o -B -ntp verify
# → Tests run: 89, Failures: 0, Errors: 0 → BUILD SUCCESS（14.0s）
```

## 3. 验收项对照

| 规划 S1-05 验收项 | 证据 | 结论 |
| --- | --- | --- |
| 同房重叠有效占用被数据库拒绝 | `overlappingStayIsRejectedAndTranslatedToBusinessConflict`（冲突文案"房间在所选日期已被占用"）、`invalidRangesRejectedByDatabase` | 通过 |
| 相邻区间允许 | `adjacentRangesAreAllowed`（退房日 2026-03-05 = 入住日 2026-03-05，两单共存） | 通过 |
| 归属错误失败 | `occupancyCannotPointToRoomOfAnotherHotel`：服务层 404；绕过服务用 Mapper 直插 → `fk_room_occupancy_room` 拒绝 | 通过（两层各验一次） |
| 预订与维修同时提交时不能同时获得冲突区间 | `reservationAndMaintenanceCannotHoldTheSameRange`（HELD 后再 BLOCK 重叠区间被拒）；`concurrentOverlappingHoldsLeaveExactlyOneWinner`（两事务并发抢同一间房，恰好一个成功，库里只剩 1 行） | 通过 |
| 直接数据库插入也不能绕过占用排斥 | `directDatabaseInsertCannotBypassExclusion`（`JdbcTemplate` 裸 SQL 插入重叠区间 → `DataIntegrityViolationException`） | 通过 |
| 终态释放后可再售（附加） | `releasedOccupancyFreesTheDates`（RELEASED 后同区间再占成功，且历史行保留）、`unblockedMaintenanceFreesTheDates` | 通过 |

## 4. 锁序与互斥协议

统一锁序（落在 `OccupancyService` + `ReservationMapper.lockRooms`）：

1. 先锁聚合根 `reservation`（`FOR UPDATE`）；
2. 再按房间 id **升序**锁 `physical_room`（`ORDER BY id ... FOR UPDATE`，传入顺序无关）；
3. 最后写 `room_occupancy`。

跨表互斥的落点是**同一张表 + 同一个有效状态谓词**：预订占用与封房/维修都写 `room_occupancy`，
共用 `ex_room_occupancy_effective`，因此不存在"两张表各自查一遍就都通过"的缝隙。

## 5. 未覆盖（不得据此宣称通过）

| 项 | 原因 | 归属任务 |
| --- | --- | --- |
| 锁房/确认/取消命令与接口 | 需要幂等记录、库存流水、审计、Outbox 一起提交 | S1-07—S1-09 |
| 超时释放 Worker | HELD 超过 `expires_at` **不会**自动释放，需事务化 ExpireHold | S1-10 |
| `operation_id` 外键 | 幂等记录未落表，本轮未建该列 | S1-07 |
| 库存流水与审计 | 占用事实有了，流水与审计没有 | S1-06 |
| 房态投影/可售查询 | 依赖 `room_inventory_day` | S1-12 |
| 维修工单与 API、多状态工单 | 只落封房事实 | S3-06 |
| 入住会话与实际分段（`stay_session` / `stay_segment`） | 属入住事实 | S3-04 |
| 死锁注入、锁等待超时、热点竞争 | 需要并发压测与故障注入 | S1-13、S2-08 |
| 分区后的互斥证明 | 本轮不分区；分区会改变唯一/排斥约束的可用范围 | S6 |

## 6. 本轮发现

1. **`btree_gist` 不需要超级用户。** 它是 PG 13+ 的"可信扩展"，数据库属主（`pms_migrator`）即可 `CREATE EXTENSION`，本机已验证。迁移里保留了 `IF NOT EXISTS`，生产若要求运维预装也照常幂等。
2. **PostgreSQL 把 `daterange` 的边界规范化成 `[)`**，所以"闭区间"根本存不进去——"相邻可续住"是**类型自带的保证**，不是我们的 CHECK 起作用。反向区间（`lower > upper`）在建区间时就报错，也进不到 CHECK。
3. **`UUID.compareTo` 与 PostgreSQL 的 uuid 排序不一致**：前者按 `mostSigBits` 有符号比较，后者按字节。测试里用 `UUID.compareTo` 断言"`ORDER BY id` 升序"会在高位为 1 的 uuid 上得出相反结论（本轮实际踩到）。涉及跨层排序（锁序、分页游标）时不要用它。
4. **驱动是 `runtime` 作用域，编译期拿不到 `PGobject` / `PSQLException`。** 因此 daterange 走"文本绑定 + SQL 侧 `::daterange` 显式转换"，约束名用正则从错误消息里取（只取约束名，其余丢弃）——既保持驱动不进编译类路径，也避免回显键值。
5. 占用冲突对外文案由 `OccupancyConflictTranslator` 按**约束名**映射，新增约束必须同步那张表，否则会退回通用"数据冲突"。完整错误码体系仍在 S1-11 冻结。
