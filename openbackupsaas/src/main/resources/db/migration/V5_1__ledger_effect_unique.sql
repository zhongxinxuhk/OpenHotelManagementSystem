-- =====================================================================
-- V5_1 修正流水唯一键（S1-06 追加）
--
-- 为什么改：V5 的唯一键是 (tenant_id, hotel_id, room_id, stay_date, effect_kind)，
-- 但"释放"必须写一条 delta = +1 的**对冲行**而不是删掉占用行（历史事实不能消失）。
-- 同一 (房间, 日期, 种类) 上既有 -1 又有 +1，原唯一键会把合法的释放也拒绝掉。
--
-- 处理方式：唯一键加入 delta。这样"重复的占用"与"重复的释放"都被拒绝，
-- 而"占用 + 释放"这一对合法组合得以共存。
--
-- 已应用的 V5 不改写（迁移规则），因此用 5.1 追加修正。
-- S1-07 落 operation_id 后，唯一键会收敛为"操作级"唯一，届时另开迁移并重新验证。
-- =====================================================================

ALTER TABLE inventory_ledger DROP CONSTRAINT uq_inventory_ledger_effect;

ALTER TABLE inventory_ledger
    ADD CONSTRAINT uq_inventory_ledger_effect
    UNIQUE (tenant_id, hotel_id, room_id, stay_date, effect_kind, delta);

COMMENT ON CONSTRAINT uq_inventory_ledger_effect ON inventory_ledger IS
    '同一房间日的同一种效果：占用(-1)与释放(+1)各至多一条，重复入账被拒绝';
