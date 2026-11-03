# S2-04 死信、人工重放与审计修复：验证记录

> 日期：2026-10-30（AEST）；轮次：`R-20261030-19`；任务：S2-04（P0）；状态：**DONE**
> 依据：[规划 §S2-04](../企业级%20PMS%20开发任务规划.md)、S2-02（Inbox）、S2-03（外部任务）
> 代码：`ops/{domain,mapper,service,web}`、`inbox/service/InboxMessageDispatcher`、`db/migration/V11`

## 1. 交付物

| 交付项 | 落点 | 说明 |
| --- | --- | --- |
| 死信表 | `dead_letter`（V11） | 原始报文 + 失败原因，状态 PENDING / REPLAYED / DISMISSED |
| 处置留痕 | `ops_intervention`（V11） | 目标、动作、操作者、复核人、原因、**前后状态** |
| 统一分发器 | `InboxMessageDispatcher` | 监听器与死信重放走同一条解析与分发路径 |
| 处置服务 | `OpsService` | 待处置概览、死信重放/忽略、外部任务重放/人工结案 |
| 受控接口 | `OpsController` | 8 个端点；`ops:read` / `ops:operate` 分层 |
| 权限点 | V11 | `ops:read`、`ops:operate` |
| 操作手册 | [死信与重放运行手册](../operations/dead-letter-runbook.md) | 判据、步骤、回滚与升级路径 |

## 2. 三条关键设计决定

### 2.1 状态变更与留痕必须同一事务

`OpsService` 的每个处置方法都是 `@Transactional`，理由只有一个：
**只改状态不留痕，事后无法追责；只留痕不改状态，台账就是在说谎。**
两者必须同生共死，否则这条审计链在最需要它的时候是断的。

留痕里存**前后状态**而不是只存动作。"我重放了"证明不了这次处置是必要且正确的；
"处置前是 DEAD（尝试 2/2），处置后是 PENDING（上限 +3）"才能。

### 2.2 重放走原对象，不新造

| 维度 | 做法 | 理由 |
| --- | --- | --- |
| 幂等键 | **原样保留** | 外部系统按它去重；换键等于允许对方再执行一次 |
| 尝试计数 | **不重置** | `external_task_attempt` 主键是 `(task_id, attempt_no)`，重置会让新尝试与历史行冲突，且"被重放了几次"从此看不出来 |
| 重试预算 | 延长 `max_attempts`（+3，可配） | 既保留历史，又给出新的机会 |

只允许 `DEAD` / `MANUAL_REVIEW` 重放：`SUCCEEDED` 重放会把已完成的再发一次，
`RUNNING` 重放会与正在执行的实例打架。第二次重放会被状态条件挡下（返回 409）。

### 2.3 高风险处置要两个人

`pms.ops.high-risk-task-types`（默认空）里的任务类型（资金/库存类）重放时要求 `reviewer`，
且**不得与操作者相同**。单人可完成的复核不是复核，只是一次点击。

操作者来自服务端会话（`TenantContextHolder`），不接受客户端自报——否则留痕里的"谁干的"
就变成了一句自述，审计价值归零。

## 3. 门禁实测

```
PMS_IT_DB=true PMS_IT_RABBIT=true ./mvnw -o -B -ntp clean verify
→ Tests run: 231, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
```

| 项 | 结果 |
| --- | --- |
| 全量（真实 PostgreSQL + 真实 RabbitMQ） | **231 / 0 / 0 / 0**（上轮 222；新增 9） |
| 无库无 Broker | 231 run / **206 skipped** / 0 failure |
| 迁移 | `flyway_schema_history` 最新 `11` |
| 接口契约 | `OpenApiContractTest` 通过；基线更新为 **29 path / 33 schema**（新增 8 个 ops 端点） |
| 真实 CI | [run 41060133953](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41060133953) @ `beb19ca` → **success**，106 秒，`tests=231 skipped=0 failures=0` |

## 4. 反向验证

把双人复核的分支临时关掉（`if (false && isHighRisk(...))`）后，高风险用例立即失败：

```
AssertionError: Status expected:<409> but was:<200>
  OpsApiTest.highRiskReplayNeedsDistinctReviewer:207
```

即"资金类任务无复核也能重放成功"——恢复后 9 例全绿。

## 5. 逐条对照验收

| 验收要求 | 对应用例 | 结果 |
| --- | --- | --- |
| 无权限人员不能重放 | `pendingRequiresReadPermission`（403）、`readPermissionCannotOperate`（只读也不能重放） | ✅ |
| 重放保留原操作幂等键 | `replayKeepsIdempotencyKeyExtendsBudgetAndCannotRepeat`（键不变、上限 +3、二次重放 409） | ✅ |
| 多次重放不重复释放/扣款 | 同上 + 死信重放的状态条件（`deadLetterReplayAppliesAndLeavesFullTrail` 第二次 409） | ✅ |
| 每次处置有操作者、原因和前后状态 | `deadLetterReplayAppliesAndLeavesFullTrail` 校验 operatorName / reason / beforeState / afterState | ✅ |
| 高风险修复的复核机制 | `highRiskReplayNeedsDistinctReviewer`（无复核 409、自审 409、他人复核 200 且留痕含复核人） | ✅ |
| 跨租户不可处置 | `crossTenantTaskIsNotFound`（404，不泄露存在性） | ✅ |
| 不可重试的报文落库而非丢弃 | `deadLetterReplayOfBrokenBodyStaysPending`（重放仍失败则保持 PENDING，可再次重放） | ✅ |

## 6. 本轮踩到的坑

**MyBatis 传 `null` 参数时 PostgreSQL 无法推断类型**，`settleDeadLetter` 的
`CASE WHEN #{detail} IS NULL` 直接 500（`could not determine data type of parameter $3`）。
显式加 `jdbcType=VARCHAR` 后正常。这类错误在测试里表现为"接口 500、日志无业务信息"，
排查成本高，值得记住。

## 7. 未覆盖与边界

1. **差异工单**（对账差异开单）未做：它依赖 S2-05 的内部对账产出，这里不先造空壳。
2. **Inbox 挂起事件的重放未接入 HTTP**：`InboxIntegrityService.redriveParked` 已在 S2-02 提供并被测试覆盖，
   但本轮只把"待处置数量"暴露在 `/ops/pending`；管理入口与权限复核随 S2-05 一起设计更合适
   （挂起事件的处置语义与"补数据"强相关）。
3. **前端页面**：阶段 3 接入，本轮只到 API。
4. **死信没有自动过期**：`PENDING` 会一直留着。是否需要保留期策略（例如 90 天后归档）
   属运维策略，未定；当前用 `received_at` 排序，靠人工清理。
5. **复核只是"两人"，不是"两级审批"**：没有把复核做成待办流（第二人主动确认），
   是操作者在提交时填写复核人。真正的双人工作流需要通知与待办，属阶段 3。
