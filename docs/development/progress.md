# PMS 开发进度台账

> 版本：1.0；建立日期：2026-10-29（AEST）；最近更新：2026-10-30（R-20261030-06）。
>
> 台账规则：**每轮开发必须在 §4 追加一条 Round 记录，并同步 §1 快照与 [feature-catalog.md](./feature-catalog.md) 受影响行**。状态词只允许使用 [README.md](./README.md) §2 的 8 个值。
>
> 事实来源优先级：开发规划 §1.3（历史事实）→ 本文件（当前口径）→ feature-catalog（功能粒度）。

## 1. 当前快照

| 项 | 当前值 |
| --- | --- |
| 当前轮次 | `R-20261030-06`（S1-05 日期约束、占用排斥与维修互斥，DONE） |
| 当前阶段 | 阶段 1 进行中（S1-01 VERIFYING、S1-02—S1-05 DONE）；阶段 0 的 S0-05—S0-09 仍待完成 |
| 已通过门禁 | 无（G0 未开审） |
| 后端工程状态 | 依赖按 ADR-011 收敛；组织/身份/房间/占用四域已落库（V1—V4 共 14 张表，复合外键 + CHECK + GiST 排斥）；**89 个测试全部通过（含 70 个真实 PostgreSQL 集成用例）**；订单命令、库存流水、幂等与支付仍未实现 |
| 前端工程状态 | `frontend-admin-pro/` 工程文件已就位；`npm ci` 受本机代理阻塞，见 [S3-01a 记录](../verification/S3-01a/2026-10-30-dependency-install.md) |
| 功能项完成度 | 约 12.5%（124 项，DONE 9 项，见 feature-catalog §1） |
| 下一轮目标 | ①`S1-06` 库存流水/审计/Outbox 原子写入；②`S1-07` 持久化幂等与提交不确定恢复 |

## 2. 任务状态总表（66 项 = 规划 65 项 + 变更追加 S3-01a）

### 阶段 0 需求与领域设计（门禁 G0）

| 任务 | 名称 | 优先级 | 估算 | 状态 | 备注 |
| --- | --- | --- | --- | --- | --- |
| S0-01 | 范围、角色与业务术语 | P0 | 2–3 | VERIFYING | 文档已交付，业务选项待确认 |
| S0-02 | 订单、占用与入住状态机 | P0 | 3–5 | VERIFYING | 状态机与 28 场景已交付，DEC 未冻结 |
| S0-03 | 技术栈收敛与兼容性验证 | P0 | 2–4 | DONE | 后端/前端构建证据齐备；前端部分已被 ADR-015 取代 |
| S0-04 | 交易不变量与数据模型 | P0 | 3–5 | VERIFYING | ER/字典/事务协议/30 场景已交付，未做数据库实测 |
| S0-05 | 日期、营业日与未分房销售规则 | P0 | 2–4 | TODO | 下一步主线（ADR-012/013） |
| S0-06 | OTA 准入与协议调查 | P0 | 2–3 | TODO | 与商务并行，不阻塞编码 |
| S0-07 | 支付与账务业务边界 | P0 | 2–4 | TODO | ADR-014 待出 |
| S0-08 | 容量、安全、SLO 与值守边界 | P0 | 2–4 | TODO | ADR-007/008 |
| S0-09 | 模块、契约、任务与设计基线审查 | P0 | 2–3 | TODO | G0 审查入口 |

### 阶段 1 数据库与交易内核（门禁 G1）

| 任务 | 名称 | 优先级 | 估算 | 状态 |
| --- | --- | --- | --- | --- |
| S1-01 | 最小工程、环境与 CI | P0 | 2–4 | VERIFYING | 依赖/配置/安全基线/错误响应/真实 PostgreSQL 集成测试/账号分权均已完成；**仅剩 CI 实跑未观测** |
| S1-02 | Flyway 与组织基础表 | P0 | 2–4 | DONE | V1 迁移 + 复合外键 + 账号分权 + 12 个 PG 集成用例；V1→V2 升级路径已在 R-20261030-04 中真实执行（同一库上增量应用 V2 成功） |
| S1-03 | 最小认证、授权和租户隔离 | P0 | 3–5 | DONE |
| S1-04 | 房型、实体房间与运营状态 | P0 | 2–4 | DONE |
| S1-05 | 日期约束、占用排斥与维修互斥 | P0 | 4–6 | DONE |
| S1-06 | 库存流水、审计和 Outbox 原子写入 | P0 | 3–5 | TODO |
| S1-07 | 持久化请求幂等与提交不确定恢复 | P0 | 3–5 | TODO |
| S1-08 | 原子锁房与订单创建 | P0 | 3–5 | TODO |
| S1-09 | 确认、取消与 No-show | P0 | 3–5 | TODO |
| S1-10 | 超时领取与幂等释放 | P0 | 2–4 | TODO |
| S1-11 | 交易 API、查询与权限闭环 | P0 | 2–4 | TODO |
| S1-12 | 派生房态、可售计算与重建 | P0 | 3–5 | TODO |
| S1-13 | 交易内核集成与并发验收 | P0 | 4–6 | TODO |

### 阶段 2 可靠性内核（门禁 G2）

| 任务 | 名称 | 优先级 | 估算 | 状态 |
| --- | --- | --- | --- | --- |
| S2-01 | Outbox 发布与 Worker 隔离 | P0 | 3–5 | TODO |
| S2-02 | Inbox 消费与版本保护 | P0 | 3–5 | TODO |
| S2-03 | 持久化任务与外部调用状态 | P0 | 3–5 | TODO |
| S2-04 | 死信、人工重放与审计修复 | P0 | 2–4 | TODO |
| S2-05 | 内部对账与派生修复 | P0 | 3–5 | TODO |
| S2-06 | 监控、链路和告警 | P0 | 3–5 | TODO |
| S2-07 | 最小部署、备份与恢复闭环 | P0 | 3–5 | TODO |
| S2-08 | 故障注入与可靠性验收 | P0 | 4–6 | TODO |

### 阶段 3 商家、集团与前台端（门禁 G3）

| 任务 | 名称 | 优先级 | 估算 | 状态 | 备注 |
| --- | --- | --- | --- | --- | --- |
| **S3-01a** | **前端工程骨架前置准备（Ant Design Pro）** | P1 | 2–3 | **BLOCKED** | **2026-10-29 变更追加（ADR-015）。工程文件已就位；`npm ci` 受本机代理阻塞（`ECONNREFUSED broker.sock`），构建未验证。允许在 G2 前准备，业务页面仍受 S3-01 约束** |
| S3-01 | 后台前端基础与契约接入 | P1 | 3–5 | TODO | 依赖 S2-08；骨架已由 S3-01a 先行 |
| S3-02 | 集团组织、员工与 RBAC 管理 | P0 | 3–5 | TODO | |
| S3-03 | 房态日历与预订工作台 | P1 | 5–8 | TODO | |
| S3-04 | 排房、入住与离店 | P0 | 4–6 | TODO | |
| S3-05 | 续住、换房和部分操作 | P0 | 4–7 | TODO | 部分操作受 DEC-04 阻塞 |
| S3-06 | 维修、停用与清洁工作流 | P0 | 2–4 | TODO | |
| S3-07 | 价格日历、价规与报价快照 | P0 | 4–6 | TODO | 税费口径受 DEC-09 阻塞 |
| S3-08 | 基础账务、收款与押金 | P0 | 4–7 | TODO | |
| S3-09 | 审计、经营报表、夜审与异步导出 | P1 | 5–8 | TODO | 报表口径受 DEC-07 阻塞 |
| S3-10 | 员工端端到端与业务验收 | P0 | 3–5 | TODO | |

### 阶段 4 用户端与支付（门禁 G4）

| 任务 | 名称 | 优先级 | 估算 | 状态 |
| --- | --- | --- | --- | --- |
| S4-01 | 住客身份与预订公共 API | P0 | 3–5 | TODO |
| S4-02 | H5 预订流程 | P1 | 4–7 | TODO |
| S4-03 | 微信小程序预订流程 | P1 | 5–8 | TODO |
| S4-04 | 支付创建、查询与回调 | P0 | 4–7 | TODO |
| S4-05 | 支付、取消和锁房超时协调 | P0 | 3–5 | TODO |
| S4-06 | 退款、支付对账与账务联动 | P0 | 4–7 | TODO |
| S4-07 | 两端支付联调与隐私检查 | P1 | 3–5 | TODO |
| S4-08 | 支付与自有渠道门禁 | P0 | 3–5 | TODO |

### 阶段 5 首个 OTA 渠道（门禁 G5）

| 任务 | 名称 | 优先级 | 估算 | 状态 |
| --- | --- | --- | --- | --- |
| S5-01 | 渠道账户、适配器与安全边界 | P0 | 3–5 | BLOCKED（授权未到位） |
| S5-02 | 门店、房型与价格计划映射 | P0 | 2–4 | TODO |
| S5-03 | 订单接收、确认与更新 | P0 | 4–7 | TODO |
| S5-04 | 取消、变更与乱序通知 | P0 | 3–5 | TODO |
| S5-05 | 库存与价格可靠推送 | P0 | 3–6 | TODO |
| S5-06 | 渠道对账与人工补偿 | P0 | 3–5 | TODO |
| S5-07 | 模拟器、契约与渠道联合验收 | P0 | 4–7 | TODO |
| S5-08 | 首渠道试运行方案与开关演练 | P1 | 2–4 | TODO |

### 阶段 6 规模化与生产验收（门禁 G6）

| 任务 | 名称 | 优先级 | 估算 | 状态 |
| --- | --- | --- | --- | --- |
| S6-01 | 容量基线、负载模型与调优 | P0 | 4–7 | TODO |
| S6-02 | 生产拓扑与数据库高可用 | P0 | 4–7 | TODO |
| S6-03 | 异地备份、归档与 PITR | P0 | 3–5 | TODO |
| S6-04 | 故障切换、网络分区与 RPO/RTO 验证 | P0 | 4–7 | TODO |
| S6-05 | 安全、隐私与供应链验收 | P0 | 4–7 | TODO |
| S6-06 | 发布、迁移兼容与回滚演练 | P0 | 3–5 | TODO |
| S6-07 | 数据初始化、门店培训与运营准备 | P1 | 3–5 | TODO |
| S6-08 | 生产门禁与分批上线 | P0 | 3–5 | TODO |
| S6-09 | 稳定性观察与阶段复盘 | P0 | 2–4 | TODO |

### 汇总

| 状态 | 任务数 |
| --- | --- |
| DONE | 5（S0-03、S1-02—S1-05） |
| VERIFYING | 4（S0-01、S0-02、S0-04、S1-01） |
| BLOCKED | 2（S3-01a 环境阻塞、S5-01 渠道授权） |
| TODO | 55 |
| 合计 | 66 |

## 3. 阶段门禁状态

| 门禁 | 状态 | 已具备 | 缺口 |
| --- | --- | --- | --- |
| G0 设计 | 未开审 | S0-01/02/03/04 交付物 | S0-05—S0-09；DEC 未冻结；ADR-005/007/008/009/010/012/013/014 未写 |
| G1 交易 | 未开始 | — | S1 全部；T01—T07/T09 并发证据 |
| G2 可靠性 | 未开始 | — | S2 全部；T08/T10—T13 |
| G3 员工闭环 | 未开始 | 前端骨架（S3-01a） | S3-01 起全部；T14—T16 |
| G4 自有渠道 | 未开始 | — | S4 全部；T17—T19 |
| G5 首 OTA | 未开始 | — | 渠道授权（DEC-11）与 S5 全部 |
| G6 生产 | 未开始 | — | S6 全部；T23—T28 |

## 4. 开发轮次记录（按轮追加，最新在上）

### 模板

```markdown
### R-YYYYMMDD-NN <一句话目标>

- 状态：DOING / VERIFYING / DONE
- 范围（本轮只做这些）：
- 涉及功能项（FT-*）/ 任务（S*-*）：
- 变更文件：
- 验证：命令 → 结果 → 未覆盖项
- 本轮发现（不夹带实现，转入下轮）：
- 回写确认：feature-catalog ✓ / §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：
```

---

### R-20261030-06 S1-05 日期约束、占用排斥与维修互斥

- 状态：DONE
- 范围（本轮只做这些）：
  1. Flyway V4（DB-B 后半）：`reservation` / `reservation_line` / `room_occupancy`（预订与封房统一表，`kind=RESERVATION|BLOCK`）；`btree_gist`；`physical_room(id, hotel_id)` 复合唯一（供房间复合外键）。
  2. 区间约束：非空、有界、规范半开 `[in,out)`、下界 < 上界；`kind` 与引用/状态/截止的组合检查。
  3. **有效占用 GiST 排斥**：同房间有效占用区间互斥（含维修），相邻区间允许；按订单行的"有效占用至多一条"部分唯一。
  4. 跨表互斥协议：预订与维修写同一张占用表、同一有效状态谓词；统一锁序（先订单 → 再按 id 升序锁房间 → 再写占用），落为 `OccupancyService` + `ReservationMapper.lockRooms`。
  5. 错误到业务口径的映射：约束名 → 可读冲突原因（409），不泄露 SQL。
  6. 测试：`RoomOccupancyIntegrationTest`（真实 PostgreSQL）13 例，含**直接数据库插入**绕过验证与**并发**冲突验证。
- 本轮明确不做：锁房/确认/取消命令与接口（S1-08/S1-09）、超时释放 Worker（S1-10）、库存流水与审计（S1-06）、幂等记录（S1-07，故占用表暂缺 `operation_id` 外键）、房态投影（S1-12）、维修工单与 API（S3-06）、入住会话与分段（S3-04）。
- 涉及功能项（FT-*）/ 任务（S*-*）：C-05；S1-05（并落 D-02/D-03 所需的占用事实基础）
- 变更文件：
  - 新增迁移：`src/main/resources/db/migration/V4__occupancy_baseline.sql`
  - 新增模块：`reservation/domain/{StayRange,Reservation,ReservationLine,RoomOccupancy,ReservationStatus,LineStatus,ReservationSourceType,OccupancyKind,OccupancyStatus}.java`、`reservation/mapper/ReservationMapper.java`、`reservation/service/{OccupancyService,OccupancyConflictTranslator}.java`
  - 新增 SQL：`resources/mapper/reservation/ReservationMapper.xml`
  - 新增共享设施：`shared/mybatis/DateRangeTypeHandler.java`（daterange ↔ StayRange）
  - 新增测试：`reservation/RoomOccupancyIntegrationTest.java`
  - 修改：`shared/config/MyBatisConfig.java`（注册 daterange 处理器）、`test/support/NoDatabaseStubConfig.java`（`ReservationMapper` 桩）、`test/resources/db/integration-cleanup.sql`（新表清理顺序）
- 验证（本轮实际执行，详见 [S1-05 记录](../verification/S1-05/2026-10-30-occupancy-baseline.md)）：
  - `./mvnw -o -q -DskipTests compile` → 退出码 0
  - `PMS_IT_DB=true ./mvnw -B -ntp test` → `Tests run: 89, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**（本轮新增 13 个）
  - 无库：`./mvnw -B -ntp test` → 89 run / **70 skipped** / 0 failure → BUILD SUCCESS
  - `PMS_IT_DB=true ./mvnw -o -B -ntp verify` → 89 tests / 0 failure → **BUILD SUCCESS**（14.0s）
  - 数据库核对：`flyway_schema_history` V1—V4 全 success（V4 于 02:12 增量应用）；`ex_room_occupancy_effective` 定义与迁移一致；`btree_gist` 存在且属主为 `pms_migrator`
  - 关键用例：相邻区间共存、重叠被拒、预订与维修互斥、RELEASED/UNBLOCKED 后可再售、**裸 SQL 直插仍被拒**、并发两事务恰好一个成功且最终 1 行
  - 未覆盖：订单命令（S1-07—S1-09）、超时释放（S1-10）、库存流水与审计（S1-06）、房态投影（S1-12）、维修工单（S3-06）、入住分段（S3-04）、死锁注入与热点压测（S1-13/S2-08）
- 本轮发现（已处理 / 转下轮）：
  1. **`btree_gist` 不需要超级用户**：它是 PG 13+ 可信扩展，数据库属主（`pms_migrator`）即可安装，本机已验证；迁移保留 `IF NOT EXISTS`。
  2. **PG 把 `daterange` 规范化为 `[)`**，所以"闭区间"存不进去——相邻可续住是类型自带的保证，不是 CHECK 的功劳；反向区间在建区间时就报错。
  3. **`UUID.compareTo` 与 PostgreSQL 的 uuid 排序不一致**（前者按 mostSigBits 有符号比较），本轮测试就踩到：用 `compareTo` 断言 `ORDER BY id` 升序会在高位为 1 的 uuid 上得出相反结论。跨层排序（锁序、分页游标）不要用 `UUID.compareTo`。
  4. 驱动是 `runtime` 作用域，编译期拿不到 `PGobject`/`PSQLException`；故 daterange 走"文本绑定 + SQL 侧 `::daterange`"，约束名用正则从消息里取（只取约束名）。
  5. 新增约束必须同步 `OccupancyConflictTranslator` 的映射表，否则冲突会退回通用文案。
- 回写确认：feature-catalog ✓（C-05 → VERIFYING 75%）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：`S1-06` 库存流水、审计与 Outbox 原子写入

---

### R-20261030-05 S1-04 房型、实体房间与运营状态

- 状态：DONE
- 范围（本轮只做这些）：
  1. Flyway V3（DB-B 前半）：`room_type` / `physical_room`；同店编码唯一、房间→房型用 `(room_type_id, hotel_id)` 复合外键（跨店归属在库层失败）、房型→门店复合外键；新增权限点 `room:read` / `room:write`。
  2. room 模块：domain / mapper(XML) / service，写入前置校验与状态规则。
  3. HTTP（首个带数据范围校验的业务接口）：房型 创建/列表/详情/启停；房间 创建/列表/详情/属性修改/启停。全部要求 `room:read`/`room:write`，且先解析租户再校验门店范围。
  4. 状态规则：房型下仍有 ACTIVE 房间时拒绝停用房型（409）；房间停用只改状态不删行；清洁状态列落地但**不开放写入口**（流转归 S3-06），以此保证"清洁变化不直接改写库存事实"。
  5. 测试：`RoomIntegrationTest`（真实 PostgreSQL）+ `RoomApiTest`（MockMvc 走完整过滤链，含 403/404/跨店拒绝）。
- 本轮明确不做：维修区间 `room_block`（S1-05）、清洁状态流转与房务任务（S3-06）、房态可售聚合（S1-12）、"有有效预订时停用房型/房间"的处置流程（依赖 S1-05 占用表，本轮无法验证）。
- 涉及功能项（FT-*）/ 任务（S*-*）：C-01、C-02、C-03、C-04；S1-04
- 变更文件：
  - 新增迁移：`src/main/resources/db/migration/V3__room_baseline.sql`
  - 新增模块：`room/domain/{RoomType,PhysicalRoom,RoomStatus,HousekeepingStatus}.java`、`room/mapper/RoomMapper.java`、`room/service/RoomService.java`
  - 新增接口层：`room/web/{RoomTypeController,RoomController,RoomTypeView,RoomView,CreateRoomTypeRequest,CreateRoomRequest,UpdateRoomRequest,RoomTypeStatusRequest,RoomStatusRequest}.java`
  - 新增共享设施：`shared/security/HotelAccess.java`（租户 → 404 → 403 的固定顺序，后续 S1-05/S1-11 复用）
  - 新增 SQL：`resources/mapper/room/RoomMapper.xml`
  - 新增测试：`room/{RoomIntegrationTest,RoomApiTest}.java`
  - 修改：`test/support/NoDatabaseStubConfig.java`（`RoomMapper` 桩）、`test/resources/db/integration-cleanup.sql`（新增表清理顺序）
- 验证（本轮实际执行，详见 [S1-04 记录](../verification/S1-04/2026-10-30-room-baseline.md)）：
  - `./mvnw -o -q -DskipTests compile` → 退出码 0
  - `PMS_IT_DB=true ./mvnw -B -ntp test` → `Tests run: 76, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**（本轮新增 24 个：RoomIntegrationTest 15 / RoomApiTest 9）
  - 无库：`./mvnw -B -ntp test` → 76 run / **57 skipped** / 0 failure → BUILD SUCCESS
  - `PMS_IT_DB=true ./mvnw -o -B -ntp verify` → 76 tests / 0 failure → **BUILD SUCCESS**（14.0s）
  - 运行态冒烟（`local` 档案，18082，真实 HTTP）：建房型 201（`DLX-King`→`dlx-king`）、建房间 201（`housekeepingStatus=DIRTY`）、按楼层筛选 200、有在用房间时停用房型 **409**、同租户不在范围门店 **403**、不存在门店 **404**；`flyway_schema_history` V1/V2/V3 全 success（V2→V3 增量升级路径已真实执行）
  - 未覆盖：有有效预订时的停用处置（S1-05）、维修区间（S1-05）、清洁流转（S3-06）、房态聚合（S1-12）、前端页面（S3-01a 阻塞）、并发同房号创建（S1-13）
- 本轮发现（已处理 / 转下轮）：
  1. 跨店房型在服务层返回 **404** 而不是 400：按 `(tenant, hotel)` 查不到即视为不存在，避免确认"该房型存在于另一门店"。已在测试固定该语义。
  2. **`target/classes` 曾被 IDEA 并发清空**（上一轮改了 `modules.xml`，IDEA 重新导入项目），导致 76 个用例全部 `NoClassDefFoundError`。门禁与 IDE 构建不要同时跑；失败先看 `target/classes` 是否为空。
  3. 房间属性更新是"null 表示不变"的补丁语义，因此**不能把备注清空成 null**；补丁语义待 S1-11 定义。
  4. **设计分歧待裁决**：`docs/domain/data-model.md` §4 规定业务实体用 `bigint` 主键，而 V1/V2 已落 `uuid`。本轮沿用 `uuid` 保持单一 id 体系，未擅自改体系；分歧登记到 G0（S0-09）审查，阶段 1 结束前必须统一（越晚迁移成本越高）。
  5. 清洁状态"不提供写入口"是本轮实现解耦的手段，不是最终形态；S3-06 接入时必须同时保证它不写入库存事实。
- 回写确认：feature-catalog ✓（C-02 → DONE；C-01/C-03/C-04 → VERIFYING 75%）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：`S1-05` 日期约束、占用排斥与维修互斥（DB-B 后半，含 `room_block` 与 `btree_gist` 权限验证）。

---

### R-20261030-04 S1-03 最小认证、授权与租户隔离

- 状态：DONE
- 范围（本轮只做这些）：
  1. Flyway V2（DB-A 剩余）：`app_user` / `role` / `permission` / `role_permission` / `user_role` / `user_hotel_scope` / `user_session`，全部带 `tenant_id` 与 `(id, tenant_id)` 复合唯一，跨表一律复合外键。
  2. 认证：**不透明会话令牌**（Bearer，32 字节随机，库内只存 SHA-256 哈希），登录 / 登出 / 当前身份 / 改密 四个接口。
  3. 授权：RBAC 权限点（`资源:动作`）+ `@PreAuthorize`；门店数据范围校验（`Authz.requireHotel` / `requireAllHotels` 全有或全无）。
  4. 请求上下文：过滤器从**会话身份**推导租户，写入 `TenantContext`，请求结束必须清理；后台任务用显式租户上下文（B-07）。
  5. 集成测试（真实 PostgreSQL）：伪造 tenant_id、跨店 ID、批量混入他租户对象、撤权后旧会话、线程复用不串租户。
  6. ADR-016：令牌形态、令牌存储、是否使用 RLS、CSRF 前提。
- 本轮明确不做：员工/角色 CRUD 接口（S3-02）、组织读写 HTTP 接口（S1-11）、审计落库（S1-06 / B-05）、A-05 平台运维身份（DEC-02 阻塞）、B-08 越权回归集（S1-13）。
- 涉及功能项（FT-*）/ 任务（S*-*）：A-04、B-01、B-02、B-03、B-04、B-06、B-07；S1-03
- 变更文件：
  - 新增迁移：`src/main/resources/db/migration/V2__identity_baseline.sql`
  - 新增安全基座：`shared/security/{TenantContext,TenantContextHolder,PmsPrincipal,SessionTokens,Authz}.java`、`shared/error/{UnauthenticatedException,NotFoundException}.java`
  - 新增身份模块：`identity/domain/{UserStatus,AppUser,Role,UserSession,SessionView}.java`、`identity/mapper/IdentityMapper.java`、`identity/service/AuthenticationService.java`
  - 新增接口层：`identity/web/{AuthController,IdentityController,BearerSessionAuthenticationFilter,LoginRequest,LoginResponse,ChangePasswordRequest,UserView}.java`
  - 新增 SQL：`resources/mapper/identity/IdentityMapper.xml`
  - 新增测试：`identity/{AuthIntegrationTest,AuthApiTest}.java`、`shared/security/{SessionTokensTest,TenantContextHolderTest}.java`、`support/IdentitySeed.java`
  - 修改：`shared/config/SecurityConfig.java`（会话过滤器 + BCrypt）、`shared/web/GlobalExceptionHandler.java`（401/403/404）、`application.properties`（`pms.security.session-ttl`）、`test/resources/db/integration-cleanup.sql`（新表清理顺序）、`test/support/NoDatabaseStubConfig.java`（`IdentityMapper` 桩）
  - 新增文档：`docs/adr/ADR-016-authn-authz-tenant-isolation.md`、`docs/verification/S1-03/2026-10-30-authn-authz.md`
- 验证（本轮实际执行，详见 [S1-03 记录](../verification/S1-03/2026-10-30-authn-authz.md)）：
  - `./mvnw -o -q -DskipTests compile` → 退出码 0
  - `PMS_IT_DB=true ./mvnw -B -ntp test` → `Tests run: 52, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**（本轮新增 32 个：AuthIntegrationTest 15 / AuthApiTest 6 / SessionTokensTest 5 / TenantContextHolderTest 6）
  - 无库：`./mvnw -B -ntp test` → 52 run / **33 skipped** / 0 failure → BUILD SUCCESS（跳过而非伪通过）
  - `PMS_IT_DB=true ./mvnw -o -B -ntp verify` → 52 tests / 0 failure → **BUILD SUCCESS**（10.7s）
  - 运行态冒烟（`local` 档案，端口 18081，真实 HTTP）：health 200；无令牌 `/auth/me` 401；登录 200（43 字符令牌，+8h 过期）；带令牌 `/auth/me` 200；门店范围 200；不存在 userId 404；登出 204 后原令牌 401；会话表 `token_hash` 长度 64 且 `revoke_reason=LOGOUT`
  - 未覆盖：并发竞争（S1-13）、登录失败锁定/限流（S6-05）、审计落库（S1-06）、前端登录页（S3-01a 仍 BLOCKED）、会话过期行归档（S6-03）、CI 实跑
- 本轮发现（已处理）：
  1. `GlobalExceptionHandler` 的 `@ExceptionHandler(Exception.class)` 会把 `@PreAuthorize` 抛出的 `AccessDeniedException` 当未预期异常返回 **500**。已补 403/401/404 三个显式处理器——否则权限失败会被误报成服务故障。
  2. 过滤器若注册成 `@Component`，Boot 会自动挂到 Servlet 容器，与 `addFilterBefore` 叠加导致同一请求执行两次。改为在 `SecurityConfig` 内手动构造。
  3. `ck_user_session_expiry`（`expires_at > issued_at`）挡住了"只改过期时间"的测试写法；该约束正确（禁止签发即过期），测试改为同时前移 `issued_at`。
  4. `user_session.last_seen_at` 不逐请求更新：每个 GET 都会变成写事务。闲置超时定案前不实现。
  5. 每请求 3 次查询（会话联表 + 权限点 + 门店范围），先不加缓存，等 S2-06 的 P95 数据再定。
- 回写确认：feature-catalog ✓（A-04/B-04/B-06/B-07 → DONE；B-01/B-02/B-03 → VERIFYING 75%）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：`S1-04` 房型、实体房间与运营状态（DB-B 前半）。

---

### R-20261030-03 合并到主项目目录 + 命名同步（chore）

- 状态：DONE
- 范围：把工作树分支 `workbuddy/main-aed5b5c8` 合并进 `main`（主项目目录 `/Users/xuzhongxin/IdeaProjects/OpenHotelManagementSystem`）；启动类保留主目录的改名 `openFrameworksApplication`，并把 S1-01 的 `@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)` 与说明注释移植过去；测试类 `OpenbackupsaasApplicationTests` → `ApplicationContextTests`。
- 涉及功能项/任务：无（不改变任何功能项状态）
- 变更文件：`.gitignore`（忽略 `.DS_Store` 与 `.workbuddy/`）、`docs/development/backend-setup.md`（启动类名）、规划 §2 后端测试行、`ApplicationContextTests.java`（rename）
- 验证：
  - 合并后 `PMS_IT_DB=true ./mvnw -B -ntp test` → `Tests run: 20, Failures: 0, Errors: 0` → **BUILD SUCCESS**
  - 冲突仅 1 处（启动类 rename vs 注解改动），已人工合并，无 `<<<<<<<` 残留，旧文件已删除
- 本轮发现：主目录原有的未提交改名会挡住合并——此后凡在 IDE 里改动，建议先提交或告知，避免工作树分支与主目录各自演进。
- 回写确认：feature-catalog 无变化（一致）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：`S1-03` 最小认证/授权/租户隔离。

---

### R-20261030-02 S1-02 组织基线：Flyway V1 + 租户/集团/门店 + 账号分权

- 状态：DONE
- 范围：
  1. 本地开发数据库脚本（独立集群、可丢弃、幂等）：`scripts/dev-db.sh` + `scripts/db-bootstrap.sql`。
  2. Flyway 迁移 V1：`tenant` / `hotel_group` / `hotel`，含租户内编码唯一、`(id, tenant_id)` 复合唯一、跨租户复合外键、状态与编码 CHECK、注释即字典。
  3. 组织数据访问与服务：record 领域对象、`OrganizationMapper`（XML，查询强制带 tenantId）、`OrganizationService`（状态前置校验、编码/时区校验）。
  4. 统一错误补 409：`ConflictException` 与数据库约束冲突映射，均不泄露 SQL。
  5. 集成测试 12 例（真实 PostgreSQL），CI 增加 postgres 17 service + 角色引导步骤。
- 涉及功能项/任务：A-01、A-02、A-03、A-06；S1-02（S1-01 剩余项一并收口）
- 变更文件：
  - 新增：`openbackupsaas/scripts/dev-db.sh`、`scripts/db-bootstrap.sql`、`src/main/resources/db/migration/V1__org_baseline.sql`
  - 新增：`tenant/domain/{Tenant,TenantStatus,HotelGroup,Hotel,OrgStatus}.java`、`tenant/mapper/OrganizationMapper.java`、`tenant/service/OrganizationService.java`
  - 新增：`resources/mapper/tenant/OrganizationMapper.xml`、`shared/error/ConflictException.java`、`shared/config/{ClockConfig,MyBatisConfig}.java`、`shared/mybatis/UuidTypeHandler.java`
  - 新增：`application-integration.properties`、`src/test/resources/db/integration-cleanup.sql`、`tenant/OrganizationIntegrationTest.java`、`support/NoDatabaseStubConfig.java`
  - 修改：`application.properties`（迁移/MyBatis 公共项上移）、`application-local.properties`（Flyway 独立账号）、`GlobalExceptionHandler`（409）、`OpenbackupsaasApplicationTests`、`.github/workflows/backend.yml`、`db/migration/README.md`
- 验证（本轮实际执行，详见 [S1-02 记录](../verification/S1-02/2026-10-30-org-baseline.md)）：
  - `./scripts/dev-db.sh start` → PostgreSQL 17 就绪（55432），重复执行幂等
  - `PMS_IT_DB=true ./mvnw -B -ntp test` → `Tests run: 20, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS**
  - 无库时：`./mvnw -B -ntp test` → 20 run / 12 skipped / 0 failure → BUILD SUCCESS（跳过而非伪通过）
  - `psql` 核对：`flyway_schema_history` 1 行成功；4 张表 owner 均为 `pms_migrator`；`hotel` 有 `fk_hotel_group_same_tenant`（复合外键）、`uq_hotel_code`、4 个 CHECK；`pms_app` `can_create=f / can_insert=t / is_super=f`
  - 未执行：V1→V2 升级路径（暂无 V2）、并发冲突时机（S1-13）、CI 实跑
- 本轮发现（转入后续轮次）：
  1. **MyBatis 3.5.19 不再内置 `UUIDTypeHandler`**（jar 内已无该类），uuid 列会抛 `No typehandler found for property null`。已加 `UuidTypeHandler` 并在 `MyBatisConfig` 全局注册，本地验证通过。
  2. 构造参数映射要求类型精确匹配：`BIGINT` → `java.lang.Long`，record 用 `long` 会 `NoSuchMethodException`。三个 record 的 `version` 已改为 `Long` 并注明原因。
  3. `test` 档案排除 MyBatis 后，服务类会因缺少 Mapper 失败；用 `NoDatabaseStubConfig`（调用即抛异常）补位，避免"假实现测业务"。
  4. 门店营业日字段故意未加：S0-05/ADR-012 未定案，先不加字段，等定案后追加迁移。
- 回写确认：feature-catalog ✓（A-01/A-02/A-03/A-06 → DONE）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：`S1-03` 最小认证/授权/租户隔离（DB-A 剩余：账号、角色、门店范围、撤权），或 `S1-04` 房型与实体房。

---

### R-20261030-01 S1-01 后端工程基线：依赖收敛 + 默认拒绝 + 统一错误 + CI

- 状态：VERIFYING（代码与本地测试已通过；真实 PostgreSQL 集成测试与账号分权未做，故不标 DONE）
- 范围：
  1. `pom.xml` 按 ADR-011 收敛（移除 Kafka/Cloud Function/Cloud BOM/WebServices/WebSocket，引入 validation/actuator/jdbc/flyway/mybatis 4.1.0/springdoc 3.1.1）。
  2. 分环境配置：公共 `application.properties` + `local`（需要 DB）+ `test`（排除 DataSource/Flyway/MyBatis），秘密全部走环境变量。
  3. 安全基线：默认拒绝、无任何可用登录方式、401/403 返回 ProblemDetail。
  4. 统一错误（RFC 9457）+ `X-Request-Id` 贯穿 MDC/响应头/错误体 + 结构化日志（UTC 单行）。
  5. 12 个领域模块包占位（含边界注释）+ `db/migration` 单源说明。
  6. CI：`.github/workflows/backend.yml`（JDK 21 + `./mvnw verify`）、`frontend.yml`（`npm ci` + tsc/lint/build）。
- 涉及功能项/任务：N-01、N-02；S1-01
- 变更文件：
  - 修改：`openbackupsaas/pom.xml`、`src/main/resources/application.properties`、`OpenbackupsaasApplication.java`、`src/test/.../OpenbackupsaasApplicationTests.java`
  - 新增：`application-local.properties`、`application-test.properties`、`logback-spring.xml`、`db/migration/README.md`
  - 新增：`shared/config/SecurityConfig.java`、`shared/config/OpenApiConfig.java`、`shared/web/RequestIdFilter.java`、`shared/web/GlobalExceptionHandler.java`
  - 新增：12 个模块 `package-info.java`、`RequestIdFilterTest`、`GlobalExceptionHandlerTest`
  - 新增：`.github/workflows/backend.yml`、`.github/workflows/frontend.yml`、`docs/development/backend-setup.md`
- 验证（本轮实际执行）：
  - `./mvnw -o -B test` → `Tests run: 8, Failures: 0, Errors: 0` → **BUILD SUCCESS**（3 个 RequestIdFilter 用例、3 个错误处理用例、2 个上下文用例）
  - `./mvnw -o -q -B -DskipTests compile` → 成功（新依赖离线可解析）
  - 运行态冒烟（`test` 档案，端口 18080）：`/actuator/health` → `200 {"groups":["liveness","readiness"],"status":"UP"}`；`/actuator/info` → `{"app":{"name":"pms","version":"0.0.1-SNAPSHOT"}}`；`/api/v1/reservations` → **401 + `application/problem+json`**，响应头与错误体 `requestId` 一致；`/v3/api-docs` → 404（test 档案关闭，符合预期）；启动耗时 1.48s
  - 未执行：真实 PostgreSQL 连接、Flyway 空库/升级、MyBatis SQL、集成测试基类、CI 实跑、前端 `npm ci`
- 本轮发现（转入后续轮次）：
  1. **Boot 4 = Jackson 3**：`spring.jackson.serialization.write-dates-as-timestamps` 会导致启动失败；注入需用 `tools.jackson.databind.ObjectMapper`。已写入 backend-setup §7。
  2. Boot 4 自动配置类包名迁移：`org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`、`org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration`；照抄 Boot 3 包名会静默失效。
  3. 已排除 `UserDetailsServiceAutoConfiguration`，避免启动日志出现"随机密码内存用户"被误读为已有登录。
  4. `springdoc` 仅靠属性开关控制，生产必须 `PMS_OPENAPI_ENABLED=false`；该开关的有效性尚未在 `local` 档案下实测（本地无 PG）。
- 回写确认：feature-catalog ✓（N-01/N-02 → VERIFYING）/ §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：`S1-02` Flyway 与组织基础表（DB-A）；同时补 `PostgresIntegrationTest` 基类 + CI service 容器，收口 S1-01。

---

### R-20261029-01 建立开发文档体系并把前端基线切换为 Ant Design Pro

- 状态：VERIFYING（文档与决策部分已完成；前端依赖安装转 BLOCKED，见「验证」）
- 范围：
  1. 新建开发执行文档体系（总入口、功能清单、进度台账、前端规范）。
  2. 决策：前端框架改为 Ant Design Pro 并深度使用，落 ADR-015。
  3. 落地 `frontend-admin-pro/` 工程骨架（Umi Max 4 / React 19 / antd 6 / pro-components 3），原 Vue 骨架冻结不删除。
  4. 长期约束入册：前端页面禁止自述式营销文本；每轮开发必须标记进度。
- 涉及功能项/任务：O-01（VERIFYING）、O-09、O-10；S3-01a（新增）、S0-03（前端部分被 ADR-015 取代）
- 变更文件：
  - 新增：`docs/development/README.md`、`feature-catalog.md`、`progress.md`、`frontend-antd-pro.md`、`docs/adr/ADR-015-frontend-ant-design-pro.md`
  - 新增：`frontend-admin-pro/`（Ant Design Pro 6.0.3 模板，去除 .github/.husky/docs/e2e 等仓库元文件，包名改为 `hotel-pms-admin`）
  - 修改：`docs/adr/ADR-011-technology-baseline.md`（前端行标为被 ADR-015 取代）、`docs/enterprise_hotel_pms_architecture.md`（§3.2 增补说明）、`docs/企业级酒店 PMS 开发任务规划.md`（§1.3、§2 前端行、§4.4 S3-01a、§5.1 目录）
- 验证（本轮实际执行，完整记录见 [S3-01a 报告](../verification/S3-01a/2026-10-30-dependency-install.md)）：
  - `npm ci`（frontend-admin-pro）→ **失败 ×6**，共同根因为本机代理在安装高峰断开：`connect ECONNREFUSED .../T/cbb-*/broker.sock`（errno -61）。已试：沙箱内/关闭隔离、`--prefer-offline`、`--offline`、`--maxsockets=3`、移至临时目录后重装。
  - 对照 `npm view react version` → `19.3.1`（registry 可达），确认故障只在大量并行下载阶段。
  - `npm run tsc` / `npm run biome:lint` / `npm run build` → **未执行**（依赖不完整，`node_modules/.bin` 不存在）。
  - 后端 `./mvnw -q -B verify` → **未执行**（本轮未改后端）。
  - 未覆盖：模板在 antd 6 / TS 7 下的编译与构建、页面渲染、demo 页清理后的悬挂引用检查。
- 本轮发现（转入后续轮次）：
  1. 模板自带 `Welcome` 页含产品介绍式文案，违反「禁止自述式营销文本」，须在 O-11 整体替换为「今日房态/待办」工作台。
  2. 启动脚本语义易混：`npm start` 带 mock，`npm run dev` 与 `start:no-mock` 均为 `MOCK=none`。联调与验收固定用 `start:no-mock`，避免 mock 数据掩盖契约错误。
  3. `frontend-engineering-vue/` 已被 ADR-015 停用，是否物理删除待确认（按非破坏原则先冻结）。
  4. **环境约束**：Ant Design Pro 全量依赖约 1.3–1.7 GB / 1200+ 包，本机 Agent 沙箱代理无法承载，今后前端安装类操作一律在宿主终端执行，Agent 侧只做静态检查与文档同步。
- 回写确认：feature-catalog ✓ / §1 快照 ✓ / 规划 §1.3 ✓
- 下一步：①宿主终端 `npm ci` → `tsc`/`biome:lint`/`build`，结果回填 S3-01a 报告 §5；②`S1-01` 后端基线（移除 Kafka/Cloud/SOAP/WebSocket，引入 validation/actuator/jdbc/flyway/mybatis/springdoc + CI）。

## 5. 统计口径说明

- 任务数 66 = 规划 65 项 + 变更追加 S3-01a（不重算原 200–342 人日总量，S3-01a 的 2–3 人日单列）。
- 「完成度」只用于趋势判断；对外排期仍以规划文档 §6 的人日区间和门禁为准。
- 阶段 0 的 VERIFYING 任务不计入 G0 通过；G0 仍需 S0-05—S0-09 完成并经 S0-09 审查。
