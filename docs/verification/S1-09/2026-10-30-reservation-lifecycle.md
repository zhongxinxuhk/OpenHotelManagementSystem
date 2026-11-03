# S1-09 确认、取消与 No-show —— 验证记录

- 日期：2026-10-30（AEST）
- 轮次：`R-20261030-10`
- 环境：macOS / JDK 21 / PostgreSQL 17（`scripts/dev-db.sh`，端口 55432，应用账号 `pms_app`、迁移账号 `pms_migrator`）
- 结论：**通过**（后端 121 测试 / 0 失败；前端 `tsc` 与 `biome:lint` 通过，`build` 因模板悬挂引用失败，见 §4）

---

## 1. 门禁命令与实际输出

| # | 命令 | 结果 |
| --- | --- | --- |
| 1 | `./mvnw -o -q -DskipTests compile`（`openbackupsaas/`） | 退出码 0 |
| 2 | `PMS_IT_DB=true ./mvnw -B -ntp test` | 第一次 `Tests run: 121, Failures: 0, Errors: 11` → **BUILD FAILURE**（原因见 §3.1）；清理脏库后重跑 → `Tests run: 121, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS** |
| 3 | `PMS_IT_DB=true ./mvnw -o -B -ntp verify` | 首次 `ReservationStateIntegrationTest.concurrentConfirmAndCancelOnlyApplyLegalTransition` 1 error（原因见 §3.2）；修正后 → `Tests run: 121, Failures: 0, Errors: 0` → **BUILD SUCCESS** |
| 4 | `./mvnw -B -ntp test`（无库） | `Tests run: 121, Failures: 0, Errors: 0, Skipped: 98` → **BUILD SUCCESS**（98 个需库用例真跳过，不伪通过） |
| 5 | `psql -c "SELECT version, success FROM flyway_schema_history"` | `1 / 2 / 3 / 4 / 5 / 5.1 / 5.2 / 6 / 6.1 / 6.2 / 6.3 / 6.4` 全部 `t` |
| 6 | `npm run tsc`（`frontend-admin-pro/`） | 退出码 0，无输出 |
| 7 | `npm run biome:lint` | `Checked 254 files in 40ms. No fixes applied. Found 2 warnings.` 退出码 0 |
| 8 | `npm run build` | **失败**：`./src/pages/Welcome.tsx:5:1 Module not found: Can't resolve '@root/docs/cheatsheet.zh-CN.md'` |

## 2. 本轮新增用例

`ReservationStateIntegrationTest`（真实 PostgreSQL，7 例）

| 用例 | 断言 |
| --- | --- |
| `confirmMovesPendingToConfirmedAndKeepsOccupancy` | 占用 HELD→CONFIRMED（仍有效、未释放）；聚合版本 0→1；确认审计已写 |
| `cancelReleasesOccupancyAndRoomBecomesAvailable` | 占用 →RELEASED；该房间重新出现在 `findAvailableRooms` |
| `repeatedCancelDoesNotDoubleRelease` | 三个不同幂等键连续取消，占用仍只有 1 行且为 RELEASED |
| `cancelAfterConfirmDoesNotReviveOnLateConfirm` | 取消后迟到的确认抛 `ConflictException`，状态仍 CANCELLED |
| `checkedInOrderCannotBeReleasedByNormalCancel` | 已入住订单经普通取消接口被拒，占用不被释放 |
| `noShowRequiresConfirmedAndReleasesOccupancy` | CONFIRMED→NO_SHOW 且占用释放；对 PENDING 调用被拒 |
| `concurrentConfirmAndCancelOnlyApplyLegalTransition` | 并发确认/取消至多一个被拒，终态恒为 CANCELLED + 1 条 RELEASED 占用 |

`ReservationTransitionsTest`（无库纯函数，4 例）：`confirmRequiresAllPending`、`noShowRequiresAllConfirmed`、`cancelRejectsCheckedInButAllowsPendingOrConfirmed`、`emptyLinesAreRejected`。

## 3. 失败与处置（不掩盖）

### 3.1 首次 `test` 的 11 个 error 与业务代码无关

报错形如 `insert or update on table "app_user" violates foreign key constraint "fk_app_user_tenant"`、
`Failed to execute SQL script statement #8 of db/integration-cleanup.sql: DELETE FROM physical_room`。

定位：`db/integration-cleanup.sql` 是按依赖顺序的 `DELETE` 序列，任一条失败即整脚本中止；
上一会话被强制中断时，集成测试已提交的数据留在库里，导致后续每个测试类的前置清理连带失败，
产出 11 个与被测逻辑无关的外键错误。手动以 `pms_app` 执行同一清理脚本（18 条全部成功）后重跑即全绿。

**没有为此改动任何业务代码**。转入 S1-13：清理脚本改为幂等且失败可定位，并在测试基类断言清理后为空。

### 3.2 并发用例的时序不确定

原实现对两个 `Future` 都直接 `get()`，隐含假定"两个命令都必须成功"。实际谁先拿到聚合根行锁由调度决定：
cancel 抢先时 confirm 必被终态守卫拒绝——这是**正确行为**，是测试写法错了。
改为 `awaitAllowingConflict`（成功计 0，被 409 拒绝计 1，其他异常原样抛出），
再断言"至多一个被拒 + 最终状态 CANCELLED + 仅 1 条 RELEASED 占用"。修正后连跑两次全绿。

### 3.3 前端 `build` 失败（本轮不修，归 S3-01a）

`src/pages/Welcome.tsx` 引用了 R-20261029-01 清理模板时删除的 `frontend-admin-pro/docs/cheatsheet.*.md`。
该页是 Ant Design Pro 模板自带的营销页（"欢迎使用 Ant Design Pro V6 🎉"），既造成构建红灯，
也违反「前端页面禁止自述式营销文本」。正确修法是替换为「今日工作台」，不是把文档补回来。

## 4. 未覆盖项

- 超时释放（S1-10）、订单列表/详情（S1-11）、派生房态与可售计算（S1-12）
- 三个生命周期端点的 HTTP 403/404 专用 MockMvc 用例（控制器接线只经上下文加载隐含验证）
- No-show 是否计费（DEC-08 未定案；本轮只交付状态迁移与占用释放）
- 部分履约 / 按房履约（DEC-04 仍 OPEN，本轮只做整单统一迁移）
- 前端生产构建（见 §3.3）
