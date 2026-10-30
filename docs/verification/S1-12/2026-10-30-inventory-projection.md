# S1-12 派生房态、可售计算与重建 —— 验证记录

- 日期：2026-10-30（AEST）
- 轮次：`R-20261030-14`
- 环境：macOS 本地（JDK 21 / PostgreSQL 17）+ GitHub Actions `ubuntu-24.04`
- 结论：**通过**。本地 `clean verify` 173 测试 / 0 失败；真实 CI [run 41021523251](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41021523251) 9 步全绿（`tests=173 skipped=0`）；V7 迁移已真实应用（`flyway_schema_history` 最新版本 `7`）。

---

## 1. 交付内容

### 1.1 表与权限（V7）

| 对象 | 说明 |
| --- | --- |
| `room_inventory_day` | 房间 × 住宿日的房态投影。主键 `(tenant, hotel, room, stay_date)`；`reservation_unavailable` / `block_unavailable` 两个标记**互斥**（CHECK）；`source_room_version` 记录算它时的房间版本；`rebuilt_at` 记录重算时间 |
| `idx_room_inventory_day_date` | `(tenant, hotel, stay_date)`，供按日批量读 |
| `inventory:read` / `inventory:write` | 查询与重建分开授权：能看房态 ≠ 能重建派生数据 |

### 1.2 接口

| 接口 | 权限 | 说明 |
| --- | --- | --- |
| `GET /api/v1/hotels/{hotelId}/availability` | `inventory:read` | 每日余量（total / sold / blocked / available）+ 整段可售房间数，一次返回 |
| `POST /api/v1/hotels/{hotelId}/inventory/rebuild` | `inventory:write` | 重建窗口内投影，返回删除/写入行数 |
| `GET /api/v1/hotels/{hotelId}/inventory/reconcile` | `inventory:read` | 三方对账报告 |

### 1.3 五个关键取舍

**① 可售余量永远从权威记录算，不读投影。**
投影会滞后（重建是运维动作）。用滞后投影回答"能不能卖"，会出现"页面说可售、下单被排斥约束拒绝"——在客人眼里就是系统出错。权威记录永远自洽，从它算的答案永远与写入一致；窗口已限制 62 天，这点计算代价远小于解释一次错卖。

**② 投影表仍然存在，但只作可重建的读模型。**
它服务日历/报表这类"批量读同一时段"的场景，并给对账提供可比对对象。可随时整段清空重建（有用例直接验证"清空后重建结果完全一致"）。

**③ 整段可售按区间交集判定，不是逐日最小值。**
```
8/1：01 房封、02 房空   → 每日余量 1
8/2：01 房空、02 房封   → 每日余量 1
但没有任何一间房能连住两晚 → 整段不可售
```
用每日最小值会得出"还剩 1 间"，下单必然撞冲突。`bookableRooms` 为 0 或房型缺失时，界面必须显示不可售。

**④ 对账核三方，不是两方。**
权威（`room_occupancy`）vs 投影（`room_inventory_day`）vs 流水（`inventory_ledger`）。只比"投影 vs 重算"会漏掉一类问题：重算逻辑与投影按同一个错误口径算，两边一致但都错。流水是写入时独立记下的事实，它与占用对不上说明记账链路有问题。

**⑤ 期望投影只有一段 SQL。**
重建与对账共用同一个 `<sql id="expectedProjection">` 片段。若各写一套，重建结果永远对得上，对账就成了自证清白。

## 2. 门禁命令与实际输出

| # | 命令 | 结果 |
| --- | --- | --- |
| 1 | `PMS_IT_DB=true ./mvnw -B -ntp test -Dtest=InventoryIntegrationTest` | `Tests run: 13, Failures: 0, Errors: 0` → BUILD SUCCESS |
| 2 | `PMS_IT_DB=true ./mvnw -B -ntp test -Dtest=InventoryApiTest` | `Tests run: 6, Failures: 0, Errors: 0` → BUILD SUCCESS |
| 3 | `PMS_IT_DB=true ./mvnw -B -ntp clean verify` | `Tests run: 173, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**（154 → 173） |
| 4 | `./mvnw -B -ntp test`（无库） | 173 run / **148 skipped** / 0 failure → BUILD SUCCESS |
| 5 | `psql … flyway_schema_history` | 最新版本 `7`，V7 迁移成功 |
| 6 | CI `backend` @ `b499d88` | **success**，9 步全绿，`Tests run: 173 / 0 failure / 0 skipped`，断言 `tests=173 skipped=0` |

### 2.1 新增用例（19 个）

`InventoryIntegrationTest`（真实 PostgreSQL，13 例）

| 用例 | 对应验收点 |
| --- | --- |
| `dailyAvailabilityCountsSoldRoomsPerNight` | 3 间房占 1 间 2 晚 → 每晚 total 3 / sold 1 / available 2 |
| `blockedRoomIsDeductedOnceAndNotDoubleCountedWithReservation` | **维修不重复扣减**：1 间封 2 晚 + 1 间被订 2 晚 → 每晚 sold 1 / blocked 1 / available 1 |
| `inactiveRoomIsNotPartOfSupply` | 停用房不构成供给 |
| `cannotPromiseWholeStayWhenNoRoomIsContiguous` | **不能承诺整段**：每晚都有余房但无连续可用房 → bookable 为 0 |
| `bookableCountsRoomsFreeForTheWholeWindow` | 3 间占 1 → 整段可售 2 |
| `adjacentStayOnCheckoutDayIsStillBookable` | 半开区间：退房日当天可接下一单 |
| `windowLongerThanLimitIsRejected` | 63 天被拒、62 天允许（边界） |
| `rebuildProducesConsistentProjection` | 重建后对账洁净；3 房 × 2 晚 = 6 行 |
| `rebuildIsIdempotent` | 重复重建行数一致，投影不膨胀 |
| `projectionCanBeDiscardedAndRebuiltIdentically` | **清空派生数据后重建，结果逐行一致** |
| `reconcileDetectsStaleAndMissingAndOrphanRows` | 缺失 / 标记过期 / 多余行三种差异分别识别 |
| `reconcileDetectsLedgerMismatch` | 造一条"没有占用的占用流水" → 报 `LEDGER` 差异 |
| `otherHotelRoomsDoNotAffectAvailability` | 同租户另一门店的房间不计入本店余量 |

`InventoryApiTest`（HTTP，6 例）：`read` 权限不足 → 403；正常查询 → 每晚行数/整段可售正确；窗口超限 → 400 + `outcome=REJECTED`；只有 `read` 不能 rebuild → 403；rebuild 后 reconcile 四个计数为 0；跨租户门店 → 404。

## 3. 失败、歧义与处置（不掩盖）

### 3.1 清理脚本又漏了新表 —— 同一个坑第三次

`room_inventory_day` 有指向 `physical_room` 的外键，但 `integration-cleanup.sql` 没加它的删除语句，于是 `DELETE FROM physical_room` 直接撞外键，**7 个用例连跑都没跑到**（全是 `ScriptStatementFailed`）。

这是 R-20261030-10「本轮发现 1」记过的问题（清理脚本按依赖顺序 DELETE，漏一张表就整片报错），第三次复现。已补上 `DELETE FROM room_inventory_day;` 并更新脚本头部的顺序说明。**真正的修法（幂等且失败可定位）仍在 S1-13**，本轮只做了最小修复。

### 3.2 MyBatis 构造器映射：原始类型要用 `_boolean`

`ExpectedProjectionRow` 的字段是 `boolean`（原始类型），XML 写 `javaType="boolean"` 时 MyBatis 会按包装类 `Boolean` 去找构造器：

```
NoSuchMethodException: ExpectedProjectionRow.<init>(UUID, LocalDate, Boolean, Boolean, Long)
```

改用原始类型别名 `javaType="_boolean"` 后正常。而 `LedgerMismatch.occupied` 是包装类 `Boolean`，必须写 `java.lang.Boolean`——**同一份 XML 里两种写法并存**，因为它们对应的 record 字段类型不同。这条与 R-20261030-02 记的「BIGINT → java.lang.Long」是同一类坑：构造器映射要求类型精确匹配。

### 3.3 测试自己撞了排斥约束

`rebuildProducesConsistentProjection` 原本封第一间房（`rooms.get(0)`），但自动选房按房号升序取第一间可用房，第一间正是被订单占用的那间 → `Conflict: 房间在所选日期已被占用或封锁`。

这其实是排斥约束在正确工作。改封第三间房，并在注释里写明原因，避免下次有人"顺手"改回去。

### 3.4 `clean` 不是 record 组件，不会出现在 JSON 里

`ReconcileReport.clean()` 是计算方法而非组件，Jackson 只序列化 record 组件，所以响应里没有 `clean` 字段（测试首跑因此 `PathNotFoundException`）。

处置：不为了一个便利字段引入注解依赖，改为让客户端按四个差异计数判断（missing / stale / orphan / ledger）。这样反而更有用——出问题时能立刻知道是"没建"、"过期"、"多余"还是"记账不对"。已在 Javadoc 与接口注释里写明。

### 3.5 契约变更走完了完整流程

新增 3 个接口必然让契约测试红。先让它红并读差异（精确列出 9 处：3 个 operation + 6 个 schema），再按 `README §3.2` 导出新基线（21 path / 28 schema），最后验证转绿。**这次变更是有意识的**，已写进本轮进度条目。

## 4. 未做（明确边界）

1. **逐房房态标记接口（E-05 `GET /room-calendar`）**：本轮给的是"房型 × 日"聚合余量，房态日历要的是"每间房每晚的占用/维修/清洁标记"，属 S3-03 的页面数据契约，本轮不猜它的形状。
2. **维修类流水的对账**：`findLedgerMismatches` 只核 `RESERVATION` 类。混在一起会让人分不清"是卖错了还是修错了"，留 S2-05。
3. **投影的增量维护**：现在只有"整段重建"。写入路径（建单/确认/取消/超时/封房）不同步更新投影——因为查询不依赖它，同步更新只会把写入路径变复杂而不改变任何对外行为。等 S3-03 页面确实需要低延迟批量读时再评估。
4. **导出与长窗口**：62 天上限之外的需求走 S3-09 异步导出。
5. **投影水位对外暴露**：`source_room_version` 已落库，但没有接口返回"这份投影有多新"。等有消费方（日历页面）需要展示新鲜度时再加。
