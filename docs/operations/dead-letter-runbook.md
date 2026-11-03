# 死信与人工重放：操作手册

> 适用版本：V11（S2-04）起；读者：值班运维 / 技术支持
> 相关实现：[OpsService](../../openbackupsaas/src/main/java/cn/sec/hn/openbackupsaas/ops/service/OpsService.java)、[OpsController](../../openbackupsaas/src/main/java/cn/sec/hn/openbackupsaas/ops/web/OpsController.java)

## 1. 先看什么

```
GET /api/v1/ops/pending          # 待处置概览（死信 / 卡住的外部任务 / 挂起事件）
```

| 字段 | 含义 | 通常意味着 |
| --- | --- | --- |
| `deadLetters` | 接不下来的消息（解析失败） | 生产/消费两侧的事件格式不一致，或消费者升级后旧报文不兼容 |
| `stuckTasks` | `DEAD`（次数耗尽）或 `MANUAL_REVIEW`（结果未知且无法查询） | 对方渠道长时间不可用，或出现了"可能已生效"的调用 |
| `parkedInbox` | 版本缺口挂起的事件 | 中间那条事件丢了（发布侧或队列问题） |

> 数字不为 0 不等于要立刻动手。**先判断是哪一类**，三类的处置完全不同。

## 2. 处置前的三条铁律

1. **不要直接改库。** 直接 `UPDATE` 会绕过留痕，事后没人能证明这次改动的依据。
   所有处置都走接口，接口会在同一事务里写 `ops_intervention`。
2. **先看原因再动手。** 死信里存的是原始报文与失败原因；外部任务里有 `last_error`（含错误分类）。
   原因不明时动手，等于把问题从"看得见"改成"看不见"。
3. **不确定就升级。** 资金/库存类任务（`pms.ops.high-risk-task-types`）必须两人；
   拿不准时找业务负责人，而不是"先重放试试"。

## 3. 死信（`deadLetters`）

### 3.1 查看

```
GET /api/v1/ops/dead-letters?limit=50
```

返回原始报文（`messageBody`）与失败原因（`error`），按接收时间升序。

### 3.2 重放

```
POST /api/v1/ops/dead-letters/{id}/replay?reason=<至少 4 个字的原因>
```

- 重放会用**原始报文**重新走一遍解析与分发，与正常消息走同一条代码路径。
- 成功 → 状态转为 `REPLAYED`，从待处置列表消失。
- 仍然失败 → 状态保持 `PENDING`（以后再修），接口返回 `409` 并在留痕里记下这次尝试。
  **这不是错误**，说明报文本身还是接不下。

### 3.3 忽略

```
POST /api/v1/ops/dead-letters/{id}/dismiss?reason=<为什么不必处理>
```

用于确认"这条报文无需处理"（例如：上游已确认放弃、历史脏数据）。
`reason` 是唯一的判断依据，写清楚——半年后没人能替你作证。

## 4. 卡住的外部任务（`stuckTasks`）

### 4.1 先分辨两类

| 状态 | 含义 | 处置方向 |
| --- | --- | --- |
| `DEAD` | 次数耗尽（可能只是对方短暂不可用） | 通常**可以直接重放** |
| `MANUAL_REVIEW` | 结果未知且渠道不支持查询（可能已生效） | **必须先核对**，再决定重放还是结案 |

`MANUAL_REVIEW` 绝不能"重放试试"：它意味着上一次请求可能已经到达对方并被受理。

### 4.2 核对后结案

```
POST /api/v1/ops/external-tasks/{id}/resolve?succeeded=true&resultRef=<对方单号>&reason=<依据>
```

### 4.3 重放

```
POST /api/v1/ops/external-tasks/{id}/replay?reason=<依据>
# 高风险类型（资金/库存）需追加：
&reviewer=<第二位操作者>
```

- 幂等键不变，次数上限 +3（`pms.ops.replay-extra-attempts`）——不是从头开始计数。
- 只接受 `DEAD` / `MANUAL_REVIEW`；已 `SUCCEEDED` 或正在 `RUNNING` 的会返回 `409`。
- 高风险任务缺复核人、或复核人与操作者相同 → `409`。

## 5. 处置之后怎么确认

```
GET /api/v1/ops/interventions?limit=20
```

检查：`operatorName` 是不是你、`reason` 是否写清、`beforeState`/`afterState` 是否与你预期一致。
若 `afterState` 与预期不符，说明状态在这期间被别的流程改过——先查清再动手。

## 6. 常见情形速查

| 现象 | 先做什么 | 不要做什么 |
| --- | --- | --- |
| 死信突然批量增长 | 看 `source` 与 `error` 是否同一原因；回到发布侧查事件格式 | 逐条重放（同样的报文会同样失败） |
| 某渠道任务全部 `DEAD` | 查渠道连通性与 `last_error`；确认对方恢复后再批量重放 | 在对方未恢复时反复重放（会被熔断挡下，也浪费时间） |
| `MANUAL_REVIEW` 变多 | 说明该渠道的"未知结果"在增加，检查超时配置与对方响应时间 | 直接重放 |
| 重放后仍是 `PENDING` 不动 | 看是否被限流/熔断（`last_error` 会写明） | 反复调用重放接口 |
| 挂起事件（`parkedInbox`）不为 0 | 查缺失版本对应的发布记录是否成功 | 手工补写 inbox 表 |

## 7. 升级路径

- 涉及金额/库存的处置，且核对结果与对方不一致 → **升级到业务负责人**，不要自行判定。
- 需要改动历史数据（例如对方确认已扣款而本地无记录）→ 走数据修复流程，不在本手册范围。
- 怀疑事件格式存在系统性偏差 → 记录一条升级单，附上死信原始报文。
