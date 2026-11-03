-- =====================================================================
-- V5_2 流水引入操作标识（S1-06 追加）
--
-- 为什么必须加：V5_1 的唯一键是 (房间, 日期, 效果种类, delta)，
-- 但"占用 → 释放 → 再占用"是**合法业务循环**（取消后重新预订同一晚）。
-- 没有操作标识时，第二次占用会因为已存在一条 -1 行而被拒绝——
-- 这不是重复入账，是正常业务；把它拒掉等于房间一旦取消就再也卖不出去。
--
-- 做法：与数据字典 §6 一致，唯一键改为
--   (tenant_id, hotel_id, operation_id, room_id, stay_date, effect_kind)
-- 一次命令（一次占用/一次释放）对应一个 operation_id，循环自然成立，
-- 而"同一操作重复写同一晚"仍被拒绝——这正是要防的重复入账。
--
-- S1-07 落 idempotency_record 后，operation_id 会加上指向幂等记录的外键。
-- 已应用的 V5 / V5_1 不改写，用 5.2 追加修正。
-- =====================================================================

ALTER TABLE inventory_ledger
    ADD COLUMN operation_id uuid NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE inventory_ledger DROP CONSTRAINT uq_inventory_ledger_effect;

ALTER TABLE inventory_ledger
    ADD CONSTRAINT uq_inventory_ledger_effect
    UNIQUE (tenant_id, hotel_id, operation_id, room_id, stay_date, effect_kind);

COMMENT ON COLUMN inventory_ledger.operation_id IS '一次命令的操作标识；S1-07 后指向幂等记录（ADR-004）';
COMMENT ON CONSTRAINT uq_inventory_ledger_effect ON inventory_ledger IS
    '同一操作对同一房间同一天的同一种效果至多一条；不同操作的占用/释放循环合法';

CREATE INDEX idx_inventory_ledger_operation ON inventory_ledger (tenant_id, hotel_id, operation_id);
