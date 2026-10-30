# S2-05 内部对账与派生修复 — 验证记录

- 任务：S2-05（R-20261030-20）；功能项 M-05
- 日期：2026-10-30（AEST）
- 分支：`feat/stage1-identity-room-occupancy`；提交 `0eb2cad`
- 结论：**通过**。差异可被发现且带身份；重复扫描不新建工单；事实类差异无自动修复路径；对账不写任何业务行。

## 1. 交付物

| 组件 | 落点 | 说明 |
| --- | --- | --- |
| 迁移 V12 | `reconcile_run` / `reconcile_finding` / `reconcile_watermark` | 部分唯一索引 `uq_reconcile_finding_open` 保证同（门店, 类型, 目标）只有一张 OPEN 工单；CHECK 保证只有非 OPEN 才有结算时间、IGNORED 必须带原因、REPAIRED 只可能是 DERIVED |
| 差异分级 | `FindingSeverity`（DERIVED / FACT / BACKLOG）+ `FindingKind`（8 类） | 分级与修复动作写在同一处；**静态块断言非派生类不得声明修复动作**，加错在类加载时失败 |
| 修复白名单 | `RepairAction` 只有 `REBUILD_PROJECTION` 与 `NONE` 两个取值 | "没有别的修复手段"是刻意的：对账只允许按权威重算派生数据 |
| 扫描 | `ReconcileMapper`（4 组只读差异查询）+ `ReconcileService.scan` | 投影与流水复用 S1-12 的同一段 SQL 与同一个 `ProjectionDiff` |
| 修复 | `ReconcileService.repairDerived` | 重建窗口投影后**重新比对**，只把确实消失的差异标 REPAIRED |
| 调度 | `ReconcileScheduler`（Worker 角色） | 从水位往后扫固定天数；水位落后过多时抬到今天，历史缺口留给人工补扫 |
| 接口 | `ReconcileController` 6 端点 | 读/操作权限分层；操作者取自服务端会话；事实类修复 409 |
| 共用比对 | `inventory.domain.ProjectionDiff`（新抽出） | `InventoryService.reconcile` 同步重构为复用它，消灭两套口径 |

## 2. 三条关键设计决定

### 2.1 事实类差异没有修复路径（类型级保证）

对账能发现的 8 类差异分三级：DERIVED（投影，可重建）、FACT（流水/订单/占用互相矛盾，只能人判断）、BACKLOG（积压，修数据没用）。"能不能自动修"不在运行时查配置表，而是枚举常量自带的，并在静态块里断言非派生类不得声明修复动作——加错在类加载时直接失败。

这不是过度设计：对账系统最危险的形态就是"发现矛盾后顺手改一致"。订单与占用状态矛盾时，可能订单错也可能占用错；自动改掉任何一边都会销毁"哪边错了"的证据。

### 2.2 修复结果必须复核

自动修复不做"重建完就全部标已修复"：重建后**重新比对一次**，只把确实消失的差异标 REPAIRED。如果某条差异的原因不是投影滞后（比如比对口径本身有 bug），它会依然存在、工单保留、日志里记 warn。直接全标修复会对运维说谎，而且下一轮扫描又会新建工单，看起来像"修了又坏"。

### 2.3 退场必须限定扫描范围

SUPERSEDED（差异消失自动退场）只作用于 `stay_date` 落在本次扫描窗口内的工单。否则"只扫最近 7 天"的一轮会把 30 天窗口里的老问题全部自动关闭——它们其实还在。这条有专门用例 + 反向验证（见 §4）。

## 3. 门禁实测

| 项 | 结果 |
| --- | --- |
| `PMS_IT_DB=true PMS_IT_RABBIT=true ./mvnw clean verify` | **257 tests / 0 failure**（231 → 257：集成 15 + HTTP 11） |
| 无库无 Broker | 257 run / **232 skipped** / 0 failure（真跳过） |
| 迁移 | `flyway_schema_history` 最新 `12`，真实应用 |
| 接口契约 | 基线更新为 **35 path / 38 schema**（新增 6 个 reconcile 端点 + 4 schema）；`OpenApiContractTest` 先红（精确列出 11 处差异）后绿 |
| 真实 CI | [run 41063043028](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41063043028) @ `0eb2cad` → **success**，`tests=257 skipped=0 failures=0`（含 RabbitMQ 冒烟 3 例真实执行） |

## 4. 反向验证（两条，都动了被测的守卫）

| 去掉的东西 | 表现 | 结论 |
| --- | --- | --- |
| `supersedeStale` 的 `stay_date` 范围限定 | `windowOutsideScanRangeIsNotSuperseded` 立即失败：`expected: <1> but was: <0>`——扫描范围外的老问题被"自动关闭" | 范围限定是真实守卫，不是摆设 |
| `upsertFinding` 的 `ON CONFLICT ... DO UPDATE` | `repeatedRunReusesTheSameTicket` 立即 `DuplicateKeyException` | 去重靠部分唯一索引，用例真实覆盖了这条路径 |

## 5. 逐条对验收

| 验收点 | 用例 | 结果 |
| --- | --- | --- |
| 缺失投影可被发现 | `missingProjectionIsDetected` | ✓ DERIVED、repairable |
| 流水不符可被发现 | `ledgerMismatchIsDetectedAsFact` | ✓ FACT（幽灵流水需要先建幂等记录——真实系统里流水只能从命令入口产生，随便插不进来） |
| 有效订单无占用（超卖前兆） | `reservationWithoutEffectiveOccupancyIsDetected` | ✓ FACT |
| Outbox 超龄积压 | `outboxBacklogIsDetectedAsBacklogNotAsDataProblem` | ✓ 按门店一张工单 |
| 重复跑不重复建工单 | `repeatedRunReusesTheSameTicket` | ✓ occurrences 累加到 2，不新建 |
| 派生类自动修复 | `autoRepairFixesDerivedFindings` | ✓ 重建后投影补齐 2 间 × 3 晚 |
| 事实类不被自动修复 | `autoRepairLeavesFactFindingsOpenAndUntouched` | ✓ 占用仍 HELD，工单仍 OPEN |
| 不补造订单/不补写流水 | `reconcileNeverCreatesOrDeletesBusinessRows` | ✓ 订单/行/占用/流水四个计数前后一致 |
| 问题消失自动退场 | `problemDisappearingSupersedesTheTicket` | ✓ SUPERSEDED |
| 扫描范围外不退场 | `windowOutsideScanRangeIsNotSuperseded` | ✓ |
| 水位只进不退 | `watermarkAdvancesAndNeverGoesBackwards` | ✓ 重扫历史区间水位不动 |
| 事实类接口修复 409 | `factFindingCannotBeRepairedThroughApi` | ✓ 且工单保持 OPEN |
| 忽略必须带原因 | `ignoreRequiresReasonAndClosesTicket` | ✓ 空白原因 400 |
| 跨店 404 | `unknownOrCrossHotelTicketIsNotFound` | ✓ 同租户另一店看不到 |

HTTP 层（11 例）：无权限 403、只读不能触发扫描/修复、summary 回传水位与最近批次、severity 过滤、runs 最新在前、事实类修复 409、忽略需原因、跨租户门店 404、窗口超限 400。

## 6. 本轮踩坑

1. **MyBatis 的 OGNL 解析不到非组件方法**：`RawFinding.severity()` 是计算方法不是 record 组件，`#{f.severity}` 会报 no getter。改为服务层从 `kind()` 派生后显式传参——多一个形参，但值只有一个来源（枚举本身），不会漂移。
2. **"python 补丁静默失败"再次出现**：`s.replace(anchor, ...)` 的锚点带了多行注释文本，实际文件里是 `/**\n     * 积压统计。`，替换没发生也没有报错，编译时才暴露。教训：**补丁后必须 grep 验证目标符号存在**，不能只看脚本打印了成功。
3. **测试夹具直接 SQL 插流水撞上外键与 CHECK**：`inventory_ledger.operation_id` 有指向幂等记录的外键，SUCCEEDED 状态还要求 `completed_at` 与 `result_ref`。这反而是好事——幽灵流水在真实系统里只能从命令入口产生，测试顺着真实约束走，插入的数据也更可信。
4. **测试前置条件不能依赖被测接口本身**：HTTP 测试里"只读权限"的用例如果用 HTTP 去造数据会先撞 403。前置条件改走服务层，被测接口保持独立。

## 7. 未覆盖与遗留风险

- **差异工单的 HTTP 重放入口**（死信/外部任务已有）：Inbox 挂起事件的重放走 `InboxIntegrityService.redriveParked`（服务层已有、有测试），HTTP 入口随前端（阶段 3）一并设计。
- **对账批次调度粒度是全部门店串行**：门店数量大时应并行分片；当前门店数为个位数，不预先复杂化。
- **修单口径的"同版本第二条"**：`findStatusMismatches` 按占用行粒度报差异，换房（SUPERSEDED）已排除，但"同一订单多行部分状态不一致"的场景在 DEC-04 定案后可能需要按行细化。
- **指标与告警（M-06）**：对账自身还没有暴露 pending 数等指标，S2-06 接入。
- **维护类流水（BLOCK）的对账**：S1-12 即时对账就明确只核 RESERVATION 类，本轮维持这一边界。
