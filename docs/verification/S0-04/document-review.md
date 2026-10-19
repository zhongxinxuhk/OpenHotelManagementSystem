# S0-04 文档核对记录

> 2026-10-19；结论：候选设计已交付，任务状态 VERIFYING；数据库测试 NOT_RUN。

## 1. 交付范围

- [数据模型](../../domain/data-model.md)：对象映射、ER、统一互斥、版本历史、流水与投影、14 条不变量保障点。
- [数据字典](../../domain/data-dictionary.md)：字段、可空性、复合外键、唯一/排斥、查询索引、历史与保留边界。
- [事务协议](../../domain/transaction-boundaries.md)：事务图、发布消费时序图、锁层级、16 类命令路径、故障恢复。
- [验收矩阵](../../domain/data-model-scenarios.md)：DM-01—DM-30，全为 SPECIFIED / NOT_RUN。
- [ADR-001](../../adr/ADR-001-inventory-authority.md)、[ADR-002](../../adr/ADR-002-room-exclusion.md)、[ADR-003](../../adr/ADR-003-atomic-command-transaction.md)、[ADR-004](../../adr/ADR-004-durable-idempotency.md)、[ADR-006](../../adr/ADR-006-cache-is-not-inventory-authority.md)：将原架构原则细化为实施候选。

## 2. 核对项目与证据边界

本次使用本地脚本检查 Markdown 本地文件链接、代码围栏闭合、表格列数、DM 编号唯一且连续、INV-01—INV-14 保障表覆盖、16 项 DEC 保持 OPEN，以及 JSON/UTF-8 可读性；使用 git diff --check 检查已跟踪文件差异。机器结果见 [review-checks.json](./review-checks.json)。

人工交叉核对重点：

1. 原 reservation_room/room_block 命名调整有明确来源与替代说明，不能在两套表重复维护事实。
2. 统一约束同时包含预订与维修；租户/门店/订单/子项复合归属链没有用单列 FK 冒充完整保护。
3. 部分唯一只能保证“至多一个”，合法状态的“必须存在一个”另有事务检查；FK 不能冒充权限检查。
4. A 后继版本、G 初始占用引用和当前 A 查询不同；续住不会破坏实际入住历史。
5. 房晚流水允许一操作多行，只记录净差；入住/确认不再扣库存；未来释放不冲销历史消费。
6. 维修与过期任务遵守统一锁序；主库未知结果、Outbox/Inbox 重复及同版本多个事件有对应协议。
7. 迁移按外键拓扑排序，operation 基础表在 O/A 引用前创建；逻辑批次不误作现成 Flyway 版本。

PostgreSQL 技术依据按 17 文档核对，链接位于具体设计段落。Mermaid 完成文本结构核对，未做渲染器或图片视觉验收；SQL 片段不是完整建表脚本，没有执行语法、扩展权限或约束实测。

## 3. 真实工程保护

本轮只编辑 docs 下的设计与交接资料。对 S0-03 保存的 37 个源码/配置文件 SHA-256 清单再次核对，结果见 review-checks.json；不重复运行与本轮文档改动无关的 Maven/npm 构建。原有工作区改动保留，未提交或推送 Git。

## 4. 未关闭事项

DEC-01—DEC-16 仍为 OPEN；物理模型需随相关业务选择冻结。未验证真实 PostgreSQL 的排斥/唯一/FK、事务隔离、双会话锁竞争、迁移/恢复、业务代码、资金与渠道协议。S0-04 未标记 DONE，G0/G1 未放行。

下一步 S0-05 细化时区、营业日、到期检查点、换房/退房库存生效窗口及未分房配额分支，并反馈模型；正式实现按阶段 1/3 执行 DM 场景并保存独立运行证据。
