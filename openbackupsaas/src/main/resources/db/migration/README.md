# 数据库迁移目录（唯一迁移源）

本目录是 **Flyway 的唯一正式迁移源**（ADR-009）。不允许在别处再维护第二份迁移或 DDL 脚本。

## 命名

```text
V<版本>__<小写下划线说明>.sql     例如 V1__org_baseline.sql、V2__room_and_occupancy.sql
R__<说明>.sql                    可重复迁移（视图/函数等幂等对象）
```

- 版本号按主干递增分配，**不复用、不回填、不改写已应用的迁移文件**。
- 一个迁移只做一件事，迁移内不写业务数据修正（数据修复走受控命令，见 S2-04）。
- 大表加索引/回填必须单独评估锁表与发布耗时（S6-06）。

## 批次规划

| 批次 | 内容 | 任务 |
| --- | --- | --- |
| DB-A | 租户、集团、门店、员工、角色与门店范围 | S1-02、S1-03 |
| DB-B | 房型、实体房、订单、占用（统一互斥） | S1-04、S1-05 |
| DB-C | 幂等记录、库存流水、Outbox、审计 | S1-06、S1-07 |
| DB-D | 房态投影、Inbox、任务与差异工单 | S1-12、S2-02—S2-05 |
| DB-E | 入住记录、日价格、报价快照、账项与收款 | S3-04—S3-09 |
| DB-F | 支付、退款、对账批次、住客身份 | S4-01、S4-04—S4-06 |
| DB-G | 渠道账户、映射、同步水位 | S5-01—S5-06 |

## 当前状态

| 版本 | 文件 | 内容 | 状态 |
| --- | --- | --- | --- |
| V1 | `V1__org_baseline.sql` | `tenant` / `hotel_group` / `hotel`；租户内编码唯一、`(id, tenant_id)` 复合唯一、跨租户复合外键、状态与编码 CHECK | 已应用于本地集群，12 个集成用例覆盖（见 [S1-02 记录](../../../../../docs/verification/S1-02/2026-10-30-org-baseline.md)） |
| V2 | `V2__identity_baseline.sql` | `app_user` / `role` / `permission` / `role_permission` / `user_role` / `user_hotel_scope` / `user_session`；口令与令牌只存派生值、跨租户授权与门店范围由复合外键拒绝、权限点字典 | 已应用于本地集群，21 个身份用例覆盖（见 [S1-03 记录](../../../../../docs/verification/S1-03/2026-10-30-authn-authz.md)） |
| V5 / 5.1 / 5.2 | `V5__journal_baseline.sql`、`V5_1__ledger_effect_unique.sql`、`V5_2__ledger_operation_id.sql` | `inventory_ledger` / `audit_log` / `outbox_event`；流水唯一键经两次修正收敛为 `(tenant_id, hotel_id, operation_id, room_id, stay_date, effect_kind)`（释放要写对冲行，因此不能只看房间日） | 已应用于本地集群，8 个流水用例覆盖（见 [S1-06 记录](../../../../../docs/verification/S1-06/2026-10-30-journal-baseline.md)） |
| V4 | `V4__occupancy_baseline.sql` | `reservation` / `reservation_line` / `room_occupancy`（统一 A/B）；`btree_gist`、`ex_room_occupancy_effective` GiST 排斥、半开区间 CHECK、`kind` 与引用一致性、`physical_room(id, hotel_id)` 复合唯一 | 已应用于本地集群，13 个占用用例覆盖（见 [S1-05 记录](../../../../../docs/verification/S1-05/2026-10-30-occupancy-baseline.md)） |
| V3 | `V3__room_baseline.sql` | `room_type` / `physical_room`；同店编码唯一、房间→房型用 `(room_type_id, hotel_id)` 复合外键（跨店归属在库层失败）、房型→门店复合外键、清洁状态与运营状态分列；新增权限点 `room:read` / `room:write` | 已应用于本地集群，24 个房间用例覆盖（见 [S1-04 记录](../../../../../docs/verification/S1-04/2026-10-30-room-baseline.md)） |

DB-A 已全部落地（V1 + V2）；DB-B 已落地（V3 房型房间 + V4 订单与占用排斥）；DB-C 前半已落地（V5 流水/审计/事件），`idempotency_record` 由 S1-07 补齐后加 `operation_id` 外键。`stay_session` / `stay_segment`（入住事实）属 DB-E，由 S3-04 落地。
**营业日切分时间与时区规则待 S0-05（ADR-012）定案后才加字段**，不要提前猜字段名。
V1→V2→V3 升级路径均在同一本地库上真实执行（V1 00:58 / V2 01:34 / V3 01:46 / V4 02:12 / V5—5.2 02:5x 增量应用成功）；空库一次应用全部版本也已验证。跨版本兼容与回滚演练仍待 S6-06。
