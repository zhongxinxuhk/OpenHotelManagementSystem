-- =====================================================================
-- V7 房态派生投影与可售查询权限（S1-12）
--
-- 为什么要有这张表，以及它为什么不是权威：
--   可售与房态的正确性只由 room_occupancy 的有效状态 + GiST 排斥约束决定。
--   room_inventory_day 是从权威记录**算出来的读模型**：可以随时整段清空重算，
--   可以落后于权威，也可以被丢弃。任何"能不能卖"的裁决都不许读它。
--
-- 为什么按"房间 × 住宿日"而不是"房型 × 住宿日"：
--   实体房分配是首期唯一的分房方式（DEC-05 未分房配额未定案，V3 明确不加配额字段）。
--   按房间存，才能回答"哪间房这晚被占/被维修"，房型余量由房间行聚合得出；
--   反过来按房型存，会丢掉"每晚有余房但没有一间连续可用"这个判断依据。
--
-- 为什么 reservation_unavailable 与 block_unavailable 要分开两列且互斥：
--   两者扣减可售的原因不同（一个是卖掉了，一个是修不了），报表与日历要分别展示；
--   而同一房间同一晚不可能既被预订又被维修——room_occupancy 的排斥约束已经保证，
--   投影表用 CHECK 把这个不变量再表达一次，防止重建逻辑被改坏后悄悄出现双重扣减。
-- =====================================================================

CREATE TABLE room_inventory_day (
    tenant_id               uuid        NOT NULL,
    hotel_id                uuid        NOT NULL,
    room_id                 uuid        NOT NULL,
    stay_date               date        NOT NULL,
    reservation_unavailable boolean     NOT NULL DEFAULT false,
    block_unavailable       boolean     NOT NULL DEFAULT false,
    -- 这一行是基于 physical_room 的哪个版本算出来的：房间停用/改房型后，
    -- 旧版本算出的投影不得覆盖新版本的结论（不可倒退水位）。
    source_room_version     bigint      NOT NULL,
    rebuilt_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_room_inventory_day PRIMARY KEY (tenant_id, hotel_id, room_id, stay_date),
    CONSTRAINT fk_room_inventory_day_room FOREIGN KEY (room_id, hotel_id)
        REFERENCES physical_room (id, hotel_id),
    CONSTRAINT ck_room_inventory_day_mutex
        CHECK (NOT (reservation_unavailable AND block_unavailable)),
    CONSTRAINT ck_room_inventory_day_version CHECK (source_room_version >= 0)
);

COMMENT ON TABLE room_inventory_day IS
    '房态派生投影（非权威）：由 room_occupancy 重算得出，可整段清空重建；不用于成交裁决（ADR-001）';
COMMENT ON COLUMN room_inventory_day.reservation_unavailable IS
    '该房该晚被预订占用（HELD/CONFIRMED/CHECKED_IN）';
COMMENT ON COLUMN room_inventory_day.block_unavailable IS
    '该房该晚被维修/封房占用（BLOCKED）';
COMMENT ON COLUMN room_inventory_day.source_room_version IS
    '投影依据的 physical_room.version，用于判断投影是否落后于房间变更';
COMMENT ON CONSTRAINT ck_room_inventory_day_mutex ON room_inventory_day IS
    '同一房间同一晚不可能既被预订又被维修：与 room_occupancy 的排斥约束一致';

CREATE INDEX idx_room_inventory_day_date
    ON room_inventory_day (tenant_id, hotel_id, stay_date);

-- 权益点：可售查询与投影重建分开授权。
-- 查询是前台与渠道的日常操作；重建是运维动作，会整段改写派生数据，必须单独授权。
INSERT INTO permission (code, name, description) VALUES
    ('inventory:read',  '读取房态与可售', '每日余量、整段可售判断与差异报告'),
    ('inventory:write', '重建房态投影',   '从权威记录重建 room_inventory_day（运维动作）')
ON CONFLICT (code) DO NOTHING;
