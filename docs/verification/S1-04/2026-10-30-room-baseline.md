# S1-04 验证记录：房型、实体房间与运营状态

> 日期：2026-10-30（AEST）；轮次：`R-20261030-05`。
>
> 结论：**本任务通过**（在"无预订数据"的前提下）。房型/房间的归属一致性、编码唯一、启停规则与状态分离均有真实 PostgreSQL 证据；
> "已有有效预订时停用"的处置依赖 S1-05 的占用表，本轮**无法验证**，见 §5。

## 1. 环境

| 项 | 值 |
| --- | --- |
| Spring Boot | 4.1.1（Jackson 3） |
| PostgreSQL | 17，独立一次性集群（端口 55432，`scripts/dev-db.sh`） |
| 迁移版本 | V1 组织 → V2 身份 → **V3 房型与实体房（本轮新增）** |
| 档案 | `integration`（真实库，`PMS_IT_DB=true`）/ `test`（无库，集成用例跳过） |

## 2. 门禁命令与实际输出

```bash
cd openbackupsaas && ./mvnw -o -B -ntp -q -DskipTests compile
# → 退出码 0

PMS_IT_DB=true ./mvnw -B -ntp test
# → Tests run: 76, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
#   本轮新增 24 个：
#     RoomIntegrationTest  15
#     RoomApiTest           9

./mvnw -B -ntp test            # 无数据库
# → 76 run / 57 skipped / 0 failure → BUILD SUCCESS
#   57 = OrganizationIntegrationTest 12 + AuthIntegrationTest 15 + AuthApiTest 6
#        + RoomIntegrationTest 15 + RoomApiTest 9

PMS_IT_DB=true ./mvnw -o -B -ntp verify
# → Tests run: 76, Failures: 0, Errors: 0 → BUILD SUCCESS（14.0s）
```

## 3. 运行态冒烟（真实 HTTP，`local` 档案 18082）

一次性数据：租户 `smoke2`、两间门店（`smk-a` 在数据范围、`smk-b` 不在）、一个持有 `room:read`/`room:write` 的账号。

| # | 请求 | 期望 | 实际 |
| --- | --- | --- | --- |
| 1 | `POST /api/v1/hotels/{A}/room-types`（`DLX-King`） | 201 | `201`，`code=dlx-king`（已归一）、`status=ACTIVE` |
| 2 | `POST /api/v1/hotels/{A}/rooms`（`0801`, floor 8） | 201 | `201`，`housekeepingStatus=DIRTY` |
| 3 | `GET /api/v1/hotels/{A}/rooms?floor=8` | 200 | `200`，1 条 `0801` |
| 4 | 有在用房间时 `POST .../room-types/{id}/status {"INACTIVE"}` | 409 | `409` |
| 5 | `GET /api/v1/hotels/{B}/rooms`（同租户、不在范围） | 403 | `403` |
| 6 | `GET /api/v1/hotels/{随机 uuid}/rooms` | 404 | `404` |
| 7 | `flyway_schema_history` | V1/V2/V3 均 success | `1/2/3` 全部 `t`（V2→V3 增量升级路径已真实执行） |

冒烟数据已在结束后清除。

## 4. 验收项对照

| 规划 S1-04 验收项 | 证据 | 结论 |
| --- | --- | --- |
| 同店房号唯一 | `duplicateRoomCodeRejectedInSameHotelButAllowedAcrossHotels`（`uq_physical_room_code` 拒绝；另一门店同房号允许） | 通过 |
| 跨店房型归属错误被拒绝 | 服务层：`roomTypeOfAnotherHotelIsNotFoundInService`（404）；数据库层：`roomTypeOfAnotherHotelRejectedByDatabase`（`fk_physical_room_type_same_hotel` 触发 `DataIntegrityViolationException`） | 通过（两层各验一次） |
| 已有有效预订时停用/变更房型按规则拒绝或处置 | — | **未验证**：占用表在 S1-05 才出现，本轮没有可判定的数据。已实现的是"房型下仍有 ACTIVE 房间时拒绝停用房型"（`deactivatingRoomTypeBlockedWhileActiveRoomsRemain`），这是本轮能站住的那条规则 |
| 清洁变化不直接改写库存事实 | `housekeepingStatusIsIndependentFromOperationalStatus`（改属性/启停后仍为 DIRTY）；且 `RoomMapper.xml` 中**没有任何 UPDATE 涉及 `housekeeping_status`**，接口层也不接收该字段 | 通过（本轮以"不提供写入口"实现解耦） |
| 权限与数据范围（附加验收） | `missingRoomPermissionGets403`、`hotelOutsideDataScopeGets403`、`hotelOfAnotherTenantGets404NotForbidden` | 通过 |

## 5. 未覆盖（不得据此宣称通过）

| 项 | 原因 | 归属任务 |
| --- | --- | --- |
| 有有效预订时停用房间/房型 | 需要占用表与"处置流程"定义 | S1-05 / S1-08 |
| 维修区间与解除 | `room_block` 必须与预订共用互斥协议（ADR-002） | S1-05 |
| 清洁状态流转与房务任务 | 只落列，无写入口 | S3-06 |
| 房态可售聚合（维修/清洁/占用三类状态并存视图） | 依赖占用与投影 | S1-12 / S3-06 |
| 房型/房间页面（ProTable） | 前端依赖安装仍 BLOCKED | S3-01 / S3-02 |
| 并发下同房号并发创建 | 唯一约束是最终防线，并发时机会与错误映射需专门验证 | S1-13 |

## 6. 本轮发现

1. **跨店房型在服务层是 404 而不是 400。** 按 `(tenant, hotel)` 查不到即视为不存在，这比"查到再判断归属"更安全（后者会确认该房型存在于另一家门店）。已在测试里固定这个语义。
2. **`target/classes` 被 IDEA 并发清空过一次**：跑 Maven 期间 IDEA 重新导入项目（上一轮改了 `modules.xml`）导致 76 个用例全部 `NoClassDefFoundError`、`@SpringBootConfiguration` 找不到。重跑即恢复。**结论**：Maven 门禁与 IDEA 构建不要同时进行，失败先看 `target/classes` 是否为空再判断代码问题。
3. **`@ExceptionHandler(Exception.class)` 的兜底顺序问题**在 S1-03 已修，本轮新增的 404（`NotFoundException`）直接复用已有处理器，未再踩坑。
4. 房间属性更新用"null 表示不变"的补丁语义，因此**无法把备注清空成 null**。这是刻意保留的缺口，等 S1-11 定义补丁语义（`Optional`/JSON Merge Patch）再扩展，不在本轮临时约定。
5. 设计文档《data-model.md §4》规定业务实体用 `bigint` 主键，但 V1/V2 已落 `uuid`。本轮**沿用 `uuid`** 以保持单一 id 体系，未擅自换体系；该分歧登记到 G0 审查（S0-09）：要么修订设计文档，要么在阶段 1 结束前统一迁移（越晚成本越高）。
