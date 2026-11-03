-- =====================================================================
-- V6_2 订单 No-show 终态（S1-09）
--
-- 设计依据：V4 对 reservation.status 的 CHECK 注释已预留
--   "NO_SHOW 待 D-07/DEC-08 定案后追加（只需改 CHECK + 迁移，不改类型）"
-- S1-09 落地 No-show 命令，需要把 NO_SHOW 写进订单聚合状态，故在此扩展 CHECK。
-- 注意：reservation_line.status 与 room_occupancy 早已支持 NO_SHOW（终态行），
--       本迁移只动 reservation 这一张表的 CHECK，不新增列、不破坏既有数据。
-- =====================================================================

ALTER TABLE reservation
    DROP CONSTRAINT ck_reservation_status,
    ADD CONSTRAINT ck_reservation_status CHECK (
        status IN ('PENDING', 'CONFIRMED', 'CHECKED_IN', 'CHECKED_OUT', 'CANCELLED', 'EXPIRED', 'NO_SHOW')
    );

COMMENT ON COLUMN reservation.status IS 'S0-02 候选聚合状态。NO_SHOW 由 S1-09 启用（仅扩 CHECK，计费规则待 DEC-08）；行级 NO_SHOW 于 V4 已允许';
