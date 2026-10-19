# PMS 核心数据字典与约束目录

> 版本：0.1；2026-10-19；S0-04 候选。配套：[数据模型](./data-model.md)、[事务协议](./transaction-boundaries.md)、[验收矩阵](./data-model-scenarios.md)。未生成或运行正式迁移。

## 1. 通用约定

`NN` 表示 NOT NULL，`?` 表示可空；未标问号的业务字段均为 NN。实体 `id bigint` 为主键；除租户根外均带 tenant_id。门店表带 hotel_id，提供父键需要的完整归属唯一键。`created_at/updated_at timestamptz` 为记录时间，`version bigint >= 0` 为业务并发版本；无更新语义的流水/事件只存创建时间。实际入住时间使用 timestamptz，销售日期使用 date/daterange，营业日单独保存。

所有 status/kind/枚举代码都必须有显式合法值 CHECK，不能把“VARCHAR 可以存任意字符串”当作扩展策略。候选枚举变化需要迁移与旧新应用兼容检查。外键默认限制删除；外键引用端按实际查询建立 B-tree 索引，不能假定数据库自动为所有引用端建立索引。跨表条件不放进普通 CHECK。[PostgreSQL 约束](https://www.postgresql.org/docs/17/ddl-constraints.html)。

UUID 使用应用的标准生成器，不嵌入个人信息；操作/事件 UUID 只作标识，不代表时间或提交顺序。代码值、哈希、外部标识的最大长度在 S0-09/渠道合同冻结后设定并对超长输入拒绝；不默默截断。

## 2. 组织与资源

| 表 | 主要字段 | 唯一 / 外键 / 检查 | 查询索引与实施说明 |
| --- | --- | --- | --- |
| tenant | id；tenant_code text；status；created_at；version | tenant_code 全局唯一 | 业务停用不删除；租户与经营主体关系待 DEC-01 |
| hotel_group | id；tenant_id；group_code；name；status；version | UQ(tenant_id,id)、UQ(tenant_id,group_code)；FK tenant | 仅租户内集团；品牌不是授权实体 |
| hotel | id；tenant_id；group_id?；hotel_code；timezone；status；version | UQ(tenant_id,id)、UQ(tenant_id,hotel_code)；FK(tenant_id,group_id)→group | timezone 必须为经服务验证的 IANA 标识；不默认服务器时区；营业日规则版本留 S0-05 |
| room_type | id；tenant_id；hotel_id；code；capacity integer；status；version | UQ(tenant_id,hotel_id,id/code) 分别定义；FK→hotel；capacity>0 | 容量/售卖政策修改不能无声改写成交快照 |
| physical_room | id；tenant_id；hotel_id；room_type_id；room_no；operational_status；cleaning_status；availability_version bigint；version | UQ(tenant_id,hotel_id,id)、UQ(tenant_id,hotel_id,room_no)；复合 FK→room_type；版本>=0 | 索引(tenant_id,hotel_id,room_type_id,operational_status,id)；此行是房间资源锁载体 |

表中 `UQ(...,id/code)` 是两条独立唯一键的简写，不是 SQL 语法。operational_status 区分启停，cleaning_status 区分清洁条件；维修日期只在统一占用表表达，避免第二个可写日期来源。禁售/启用和清洁变更均遵守资源锁并增加 availability_version。房间有业务引用后，租户、门店及历史分类不能直接重写；必要更正保留映射历史并单独审查。

## 3. 订单与履约

### 3.1 reservation（O）

| 字段 | 类型/可空 | 用途与保障 |
| --- | --- | --- |
| id / tenant_id / hotel_id | bigint NN | 主键及归属；UQ(tenant_id,hotel_id,id)，FK→hotel |
| reservation_no | text NN | UQ(tenant_id,hotel_id,reservation_no)；面向业务的订单号 |
| source_type / source_ref | text NN / text? | 来源类型与可信引用；不能单凭 source_ref 判定 OTA 唯一性 |
| status | text NN | 状态机 §5 候选聚合状态；禁止客户端直接设置 |
| line_count / 七类状态计数 | integer NN | >=0；line_count>0；计数之和等于 line_count；跨 L 实际值由事务校验 |
| version | bigint NN | 每个有主单效果的命令递增一次；同一事务多事件可共享版本 |
| policy_snapshot_id / quote_snapshot_id | UUID? | 预留版本化政策/报价引用；报价阶段启用后按入口要求必填；缺报价不能当零价 |
| created_operation_id | UUID NN | FK 同归属幂等操作；同一创建操作至多一个 O |
| created_at / updated_at | timestamptz NN | 记录时刻，不替代住宿日或营业日 |

索引：`(tenant_id,hotel_id,status,created_at,id)` 与业务查询所需订单号唯一索引；不为所有组合预建索引。金额、币种、税费分摊的物理列由 S0-07 明确，不在此虚构默认值。

### 3.2 reservation_line（L）

| 字段 | 类型/可空 | 用途与保障 |
| --- | --- | --- |
| id / tenant_id / hotel_id / reservation_id | bigint NN | 复合 FK→O；UQ(tenant_id,hotel_id,id) 及 UQ(tenant_id,hotel_id,reservation_id,id) |
| line_no | integer NN | >0；UQ(tenant_id,hotel_id,reservation_id,line_no) |
| requested_room_type_id | bigint NN | 同门店复合 FK；成交要求与实际分配房型分开 |
| planned_range | daterange NN | 有界、有限、非空、规范半开区间；改变时保存原历史/审计；与有效 A 的计划语义同事务核对 |
| status | text NN | PENDING/CONFIRMED/CHECKED_IN/CHECKED_OUT/CANCELLED/EXPIRED/NO_SHOW |
| version / policy_snapshot_id / quote_snapshot_id | bigint NN / UUID? / UUID? | 子项版本与当前快照引用；快照变更不覆盖旧内容 |
| created_at / updated_at | timestamptz NN | 追踪记录 |

一个 L 表示一间需求，不设可变 quantity。按 `(tenant_id,hotel_id,reservation_id,id)` 排序锁定。当前有效 A 从占用表查询；不增加可漂移的 current 指针。换房后 planned_range 可继续代表完整购买区间，而当前 A 只表达切换后区间，二者不能简单要求永久相等；按命令和历史分段核对覆盖关系。

## 4. room_occupancy（统一 A/B）

| 字段 | 类型/可空 | 约束与含义 |
| --- | --- | --- |
| id / tenant_id / hotel_id / room_id | bigint NN | 复合 FK→physical_room；UQ(tenant_id,hotel_id,id) |
| kind | text NN | RESERVATION 或 BLOCK |
| reservation_id / line_id | bigint? | RESERVATION 必填，BLOCK 必空；完整复合 FK→L；不存在部分空引用 |
| stay_range | daterange NN | 使用数据模型 §5 的有效区间检查 |
| status | text NN | A：HELD/CONFIRMED/CHECKED_IN/RELEASED/EXPIRED/COMPLETED/SUPERSEDED；B：BLOCKED/UNBLOCKED；按 kind 检查 |
| expires_at | timestamptz? | A.HELD 必填，A 后续保留曾有截止点；B 必空；不会随时钟自行释放 |
| predecessor_id | bigint? | A 替换版本引用；复合 FK(tenant_id,hotel_id,line_id,predecessor_id)→同 L 的 A；父端 UQ(tenant_id,hotel_id,line_id,id)；非空 predecessor_id 唯一；B 必空 |
| reason_code / reason_note | text NN / text? | 版本变化或封房理由；备注不含非必要住客资料 |
| created_operation_id / last_operation_id | UUID NN | 同归属 FK→幂等操作；创建与最后合法状态变更可追踪 |
| version / created_at / updated_at | bigint NN / timestamptz NN | 状态改变递增版本；历史资源字段不覆盖 |

关键约束：统一有效集合的 GiST 排斥；按 L 的有效 A 部分唯一；predecessor_id!=id；kind 与状态/引用组合检查。自 FK 不自动禁止更长环，也不证明前驱已 SUPERSEDED，这些由唯一命令入口检查。只有 A 的完整历史可作为后继链，禁止跨 L 链接。

查询索引：`(tenant_id,hotel_id,line_id,id)` 查占用历史；HELD 部分索引 `(expires_at,reservation_id)` 仅用于发现过期候选；按房间的 GiST 供区间冲突。过期任务不能先锁此表再倒序获取 O 锁。

BLOCK 的提前结束只改变状态和可释放窗口，不改原区间。按日封房不能承诺精确表示小时维修；紧急在住维修先进入运营协调。工单业务若需要多个状态，由 S3-06 补充独立元数据，引用同一封房事实。

## 5. 入住会话与实际分段

| 表 | 字段 | 数据库约束 | 服务端额外保障 |
| --- | --- | --- | --- |
| stay_session | id；tenant_id/hotel_id；reservation_id/line_id；status(IN_HOUSE/CLOSED)；started_at；ended_at?；version；operation 引用 | 同归属 FK→L；UQ(tenant_id,hotel_id,line_id)；UQ(tenant_id,hotel_id,line_id,id)；IN_HOUSE 时结束为空，CLOSED 时结束非空且>=开始 | 创建时 L/A 符合入住；关闭与 L/A/G 同事务；每次履约仅一个会话 |
| stay_segment | id；tenant_id/hotel_id；line_id/session_id；room_id；initial_occupancy_id；status(ACTIVE/TRANSFERRED/CLOSED)；started_at；ended_at?；predecessor_segment_id?；operation 引用 | 同 L 复合 FK→S、初始 A；房间复合 FK；前驱同 S FK且唯一；ACTIVE 结束为空，终态结束非空且>=开始；活动 S 唯一与活动 room 唯一 | 初始 A 的 kind/房间匹配；前驱不得环/跨会话；同一次换房旧结束=新开始；历史分段不得重叠，不能修改旧时刻隐藏冲突 |

G 的父键另提供 `UQ(tenant_id,hotel_id,session_id,id)`，供同会话前驱引用。两个活动部分唯一索引分别为 `(tenant_id,hotel_id,session_id)` 和 `(tenant_id,hotel_id,room_id)`，谓词均为 `status='ACTIVE'`。只保证“至多一个”，S.IN_HOUSE “恰好一个”及 L/A/S/G 状态一致性由事务检查。

创建 G 的 initial_occupancy_id 指向当时 A，续住可能创建后继 A 而保持 G；查询当前 A 必须走 L。实际时刻允许零时长纠错历史，但须有原因；普通入住/换房不能伪造时刻来掩盖实际重叠。住客及证件不复制到 G，最小住客档案与访问策略留 S0-08/S3-04。

## 6. 操作、流水、审计与事件

| 表 | 主要字段 | 唯一 / 引用 / 保留规则 |
| --- | --- | --- |
| idempotency_record | operation_id UUID PK；tenant_id/hotel_id；caller_namespace；command_name；idempotency_key；hash_version；request_hash；status；result_code；result_ref?；response_snapshot jsonb?；created_at/completed_at? | UQ(tenant_id,hotel_id,caller_namespace,command_name,idempotency_key) 全部非空；另 UQ(tenant_id,hotel_id,operation_id) 供 FK；数据库内短操作事务化完成；完整协议见 ADR-004 |
| inventory_ledger | id；tenant_id/hotel_id；operation_id；room_id；stay_date；effect_kind；delta smallint；reason_code；effective_from_date；policy_version；created_at | UQ(tenant_id,hotel_id,operation_id,room_id,stay_date,effect_kind)；delta IN(-1,1)；effect_kind IN(RESERVATION,BLOCK)；复合 FK→操作、房间；只追加，不覆盖历史 |
| audit_log | id UUID；tenant_id/hotel_id；operation_id；action；actor_type/actor_id；target_type/target_id；before_version?/after_version?；change_summary jsonb；occurred_at/created_at | FK→操作；一次命令可多条审计；目标是多态引用，不能声称一个 FK 已约束所有类型；服务校验目标和归属，敏感原文不写入 |
| outbox_event | event_id UUID PK；tenant_id/hotel_id；operation_id；aggregate_type/id/version；event_ordinal integer；event_type；schema_version；payload jsonb；occurred_at；published_at?；attempt_count；next_attempt_at?；lease_token?/lease_until? | UQ(tenant_id,hotel_id,aggregate_type,aggregate_id,aggregate_version,event_ordinal)；ordinal>=1；操作 FK；同版本多事件合法；载荷一旦提交不改写，只更新投递元数据 |
| inbox_event | tenant_id/hotel_id；consumer_name；event_id UUID；payload_hash；processed_at；result_ref? | PK(tenant_id,hotel_id,consumer_name,event_id)；本地效果和已处理记录同事务；外部未认证通知先经过渠道/支付适配器，不直接充当内部事件 |
| room_inventory_day | tenant_id/hotel_id/room_id/stay_date；reservation_unavailable bool；block_unavailable bool；source_room_version bigint；rebuilt_at | PK(tenant_id,hotel_id,room_id,stay_date)；房间 FK；同一日互斥标记不能同时为 true；来源版本不可倒退；不存资金和住客资料 |

operation_id 关联不能用全局唯一代替授权。内部后台操作也通过可信 namespace 形成操作记录；不接受客户端自行冒用系统 namespace。查询索引：流水按 `(tenant_id,hotel_id,room_id,stay_date,id)`；审计按归属/目标/时间；Outbox 对未发布事件的 `next_attempt_at,event_id` 建部分索引。重复的版本事件由 ordinal 区分；跨不同 aggregate_type/id 分别维护版本，房间版本不能冒充 O.version。

Inbox 去重行只在处理提交后可见。若实际业务调用跨事务，第一段只能确认“已接收并创建可靠任务”，任务效果另行幂等；不能提前写 processed_at 再丢失业务效果。Outbox 至少一次发布，Broker 确认后写 published_at；中间崩溃允许重复，由消费者去重。

## 7. 后续领域的最小契约

| 领域 | 本阶段固定的边界 | 后续必须补齐 |
| --- | --- | --- |
| quote/policy snapshot | 不可变版本引用，原单保留历史；无报价不默认零价 | S0-07/S3-07 金额精度、币种、舍入、税费和分摊；金额不得用二进制浮点 |
| payment/refund | 真实资金事实独立于住宿终态；关联 O、操作及可靠任务 | 服务商/商户/业务单唯一作用域、退款上限、账项与对账；不能以任意 provider_ref 全局去重 |
| ota_order_mapping | 关联可信渠道账户、外部订单与本地 O；归属一致 | S0-06 按真实协议定 channel_account + property + external_order_id 等唯一作用域 |
| integration_task | 外部动作在核心事务外执行；请求摘要/业务键稳定；尝试历史可追踪 | S2 的租约、重试、未知结果、人工处置及状态机 |
| identity/guest | 当前授权先于读取对象或幂等结果；住客与付款人分开 | 用户身份、员工范围、住客最小字段和加密/删除规则 |

## 8. 保留、索引与迁移约束

幂等成功摘要、事件去重和外部映射的保留不能短于可重放窗口，具体期限须结合业务、渠道和隐私政策定案。清除详细响应/个人信息后保留最小去重墓碑或拒绝过旧重放；不能删除幂等键后把旧支付/订单通知作为新业务。日志清理、业务更正和依法删除须分别设计，不因保留引用而无限存储个人原文。

本轮不做分区。分区可能改变唯一/排斥约束的可用范围，后续必须重新证明全房间互斥，不得按日期分区后只在各分区内防重。可重建投影与事实表采用不同备份优先级；事实、流水、审计和事件不能只保留其中一部分。
