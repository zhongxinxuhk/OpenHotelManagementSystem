# S2-02 Inbox 消费与版本保护：验证记录

> 日期：2026-10-30（AEST）；轮次：`R-20261030-17`；任务：S2-02（P0）；状态：**DONE**
> 依据：[规划 §S2-02](../企业级%20PMS%20开发任务规划.md)、[ADR-004](../adr/ADR-004-durable-idempotency.md)、S2-01 发布器
> 代码：`inbox/{domain,mapper,service,messaging}`、`db/migration/V9`

## 1. 交付物

| 交付项 | 落点 | 说明 |
| --- | --- | --- |
| 去重台账 | `inbox_event`（V9） | 主键 `(consumer_name, event_id)` **就是**去重键；四种结论文档化在 CHECK 里 |
| 位点 | `inbox_consumer_position`（V9） | 按（消费者, 聚合）记 `(last_version, last_ordinal)`，重复/旧事件/缺口都靠它判定 |
| 消费者模板 | `InboxConsumer` + `InboxProcessor` | 业务只写 `apply`；去重、版本判定、位点推进、失败回滚由模板负责 |
| 重放 | `InboxIntegrityService.redriveParked` | 缺口补齐后按聚合内顺序追上；逐条独立事务 |
| 首个真实消费者 | `RoomProjectionConsumer` | 订单事件驱动 `room_inventory_day` 整段重算（幂等，超 62 天自动分块） |
| 消息入口 | `InboxListener` + `InboxMessagingConfig` | 队列/交换机/绑定声明；解析失败与业务失败的处理路径分离 |

## 2. 三条关键设计决定

### 2.1 认领必须写在应用之前，且同一事务

先 `INSERT ... ON CONFLICT DO NOTHING` 认领，再执行 `apply`，最后推进位点，三步一个事务。

- **认领在前**：并发处理同一事件时，唯一键让后到者拿到 0 行直接退出，只有一个人应用。
  反过来（先应用后认领）两个并发消费者会各应用一次——测试 `concurrentDuplicateDeliveryAppliesOnce`
  就是压这条。
- **同事务**：任何一步单独提交都会产生错误中间态——只提交认领 → 消息被标记处理过但业务没发生（**永久丢失**）；
  只提交业务 → 重投时再执行一次；只提交位点 → 旧事件被当成新事件。
- **业务失败必须抛出**：异常让整个事务回滚，台账与位点都不留痕，消息由容器重投。
  吞掉异常当作"处理过"，是这类系统里最贵的一行代码。

### 2.2 首次见到某个聚合：接管当前版本，不判缺口

这是本轮补上的一个真实缺陷。初版严格按"版本 > 位点 + 1 即缺口"判定，结果：
**新消费者上线时，存量聚合的第一条事件版本是 5、是 3、是 7——全部被判成缺口挂起，永久卡死。**

历史事件从来不在总线上，等 v1 重投那一天不会来。所以规则是：**没有位点时以收到的版本为起点**。
这是唯一一次"不按顺序"的接受，之后仍严格按位点判定。这条规则写进了类注释，
否则下一个人会把它当 bug 改回去。

### 2.3 缺口 ≠ 丢弃，也不等于猜着应用

`PARKED_GAP` 是唯一"未结案"状态（`settled_at IS NULL` 由 CHECK 强制），意味着它必须能被重放：

- 缺口事件的消息体**整条存下来**（`payload`），否则补齐后无从重放——把"挂起"做成"丢弃"；
- 位点停在缺口前，后续事件继续挂起，形成队列；
- 补齐后 `redriveParked` 按 `(aggregate_id, version, ordinal)` 顺序追上。

## 3. 门禁实测

```
PMS_IT_DB=true PMS_IT_RABBIT=true ./mvnw -o -B -ntp clean verify
→ Tests run: 211, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS
```

| 项 | 结果 |
| --- | --- |
| 全量（真实 PostgreSQL + 真实 RabbitMQ） | **211 / 0 / 0 / 0**（上轮 199；新增 12 = 集成 11 + 端到端 1） |
| 无库无 Broker | 211 run / **186 skipped** / 0 failure |
| 迁移 | `flyway_schema_history` 最新 `9`，真实应用 |
| 真实 CI | [run 41034639792](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41034639792) @ `ad5e7e1` → **success**，9 步全绿（99 秒），`tests=211 skipped=0 failures=0`；`InboxListenerRabbitTest` 在 CI 中真实执行（未被跳过） |

## 4. 反向验证

**去掉 `ON CONFLICT (consumer_name, event_id) DO NOTHING`** 后重跑去重用例，立即失败：

```
org.springframework.dao.DuplicateKeyException:
ERROR: duplicate key value violates unique constraint "pk_inbox_event"
```

说明去重路径确实被覆盖，不是"用例恰好没触发重复"。恢复后 11 例全绿。

## 5. 真实 Broker 端到端（不只是处理器单测）

`InboxListenerRabbitTest`（`PMS_IT_RABBIT=true`）：

1. 建门店/房型/房间并下一张真实订单 → 产生真实 Outbox 事件；
2. 用真实发布器把事件发到 RabbitMQ；
3. 轮询等待监听器消费（不 sleep 拍脑袋的时长）；
4. 断言：`inbox_event.outcome = PROCESSED`、台账租户 = 消息体租户、
   `room_inventory_day` 两晚各一行被标记占用、挂起数为 0。

这条链路验证的是「接线」：交换机/队列绑定、消息解析、容器投递、消费者落库。
处理器再正确，绑定错了也不会有一条消息进来。

## 6. 本轮踩到的两个坑

### 6.1 topic 交换机的 `*` 必须独占一个词

初版绑定键写 `Reservation*`，**绑定声明成功、消息全部不可路由**：

```
EventPublishException: 消息不可路由（replyCode=312，routingKey=ReservationCreated）
交换机 pms.events 上没有匹配的队列
```

topic 语义里 `*` 只能作为独立词匹配一段，`Reservation*` 是字面词。当前路由键就是事件类型
（单单词），只能逐个列出。这个坑的隐蔽之处：`rabbitmqctl list_bindings` 里绑定明明白白存在，
只有发布时才会暴露。

### 6.2 声明拓扑与启动容器有竞态

`amqpAdmin.initialize()` 显式声明一次即可消除。生产侧的对应事实是：**发布方只声明交换机**，
如果在消费方绑定建立前发布，`mandatory` 会把它判为不可路由并退避重试——不丢消息，但会有一小段抖动。
这是可接受的启动顺序代价，记录在此以免被当成 bug 排查。

## 7. 未覆盖与边界

1. **死信与人工重放**（M-04 / S2-04）：解析失败当前是"记 ERROR 后丢弃"，没有落死信队列。
2. **重试分级与退避**：业务失败一律抛出交给容器默认策略（立即重投），没有次数上限与延迟重试。
   持续失败会形成热循环——S2-04 必须补。
3. **消费者自身指标**：没有暴露"处理耗时/挂起数/重复率"，M-06 统一接。
4. **跨租户隔离的强制**：台账里的租户来自消息体，未与 `tenant` 表做外键校验（见 V9 注释：
   台账生命周期必须长于聚合）。若消息体被伪造，需要发布侧的可信通道保证——属安全加固项。
5. **投影消费者是全窗口重算**：一个订单事件重算该门店该窗口的全部房间，量大时成本可观。
   增量维护属 S2-05（派生修复与对账）。
6. **同一事件多个消费者**：`InboxProcessor` 按消费者分别记账，但监听器对同一条消息会遍历所有
   订阅者——若其中一个抛出，整条消息重投，已成功的消费者会被记为"重复"并跳过，语义正确但会多一次空转。
