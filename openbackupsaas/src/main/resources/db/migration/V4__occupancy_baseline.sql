-- =====================================================================
-- V4 占用基线：reservation / reservation_line / room_occupancy（S1-05，DB-B 后半）
--
-- 设计依据：docs/domain/data-dictionary.md §3—§4、data-model.md §5（S0-04 冻结候选）。
--
-- 三条不可妥协的事实：
--   1. **占用只有一个事实源**：预订占用（A）与封房/维修（B）写同一张表、
--      共用同一套有效状态谓词。两个可写日期来源必然漂移，也必然互相绕过。
--   2. **排斥由数据库保证**："直接连库插一行"也必须与走服务一样被拒绝；
--      应用层先查后插在并发下必然失败，不能当作正确性依据。
--   3. 区间只用规范半开 [in, out)：退房日与入住日相同即相邻，不冲突。
--
-- 本轮未加的列（不是遗漏，是依赖未到）：
--   - operation_id（幂等操作）等 S1-07 落表后再加，避免先建空约束再改；
--   - stay_session / stay_segment 属 S3-04 的入住事实，不在本轮；
--   - predecessor_id 与"SUPERSEDED 必有唯一后继"由服务层检查（S1-08），
--     普通 FK 无法表达"前驱必须已是终态"，此处不假装能。
-- =====================================================================

-- btree_gist：dataroom 的 = 与 daterange 的 && 需要 GiST 且包含 btree 操作符类。
-- btree_gist 是 PG 13+ 的"可信扩展"，数据库属主可安装（本机已验证：pms_migrator 可执行）。
-- 若目标环境要求超级用户安装，先由运维预装，本语句仍然是幂等的 IF NOT EXISTS。
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- 房间需要 (id, hotel_id) 复合唯一，供 room_occupancy 的复合外键引用，
-- 使"占用指向另一家门店的房间"在数据库层失败。
ALTER TABLE physical_room
    ADD CONSTRAINT uq_physical_room_id_hotel UNIQUE (id, hotel_id);

CREATE TABLE reservation (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL,
    hotel_id       uuid        NOT NULL,
    reservation_no text        NOT NULL,
    source_type    text        NOT NULL,
    status         text        NOT NULL DEFAULT 'PENDING',
    version        bigint      NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_reservation_hotel FOREIGN KEY (hotel_id, tenant_id) REFERENCES hotel (id, tenant_id),
    CONSTRAINT uq_reservation_no       UNIQUE (tenant_id, hotel_id, reservation_no),
    CONSTRAINT uq_reservation_id_tenant UNIQUE (id, tenant_id),
    -- 供 reservation_line 的 (reservation_id, hotel_id) 复合外键引用
    CONSTRAINT uq_reservation_id_hotel UNIQUE (id, hotel_id),
    CONSTRAINT ck_reservation_no_fmt   CHECK (reservation_no ~ '^[A-Za-z0-9][A-Za-z0-9-]{2,31}$'),
    CONSTRAINT ck_reservation_source   CHECK (source_type IN ('FRONTDESK', 'DIRECT', 'OTA', 'MINIPROGRAM')),
    CONSTRAINT ck_reservation_status   CHECK (status IN ('PENDING', 'CONFIRMED', 'CHECKED_IN',
                                                         'CHECKED_OUT', 'CANCELLED', 'EXPIRED'))
);

COMMENT ON TABLE reservation IS '订单（O）：一次预订的聚合根；状态由命令入口推进，不接受客户端直接设置';
COMMENT ON COLUMN reservation.source_type IS '订单来源；渠道订单由 L 域写入，不凭外部单号判定唯一性';
COMMENT ON COLUMN reservation.status IS 'S0-02 候选聚合状态。NO_SHOW 待 D-07/DEC-08 定案后追加（只需改 CHECK + 迁移，不改类型）';
COMMENT ON COLUMN reservation.version IS '每个有主单效果的命令递增一次（S1-08 起）';

CREATE INDEX idx_reservation_hotel ON reservation (tenant_id, hotel_id, status, created_at);

CREATE TABLE reservation_line (
    id                    uuid      PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid      NOT NULL,
    hotel_id              uuid      NOT NULL,
    reservation_id        uuid      NOT NULL,
    line_no               integer   NOT NULL,
    requested_room_type_id uuid     NOT NULL,
    planned_range         daterange NOT NULL,
    status                text      NOT NULL DEFAULT 'PENDING',
    version               bigint    NOT NULL DEFAULT 0,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_reservation_line_reservation FOREIGN KEY (reservation_id, hotel_id)
        REFERENCES reservation (id, hotel_id),
    -- 复合外键：房型必须与订单行同店
    CONSTRAINT fk_reservation_line_room_type FOREIGN KEY (requested_room_type_id, hotel_id)
        REFERENCES room_type (id, hotel_id),
    CONSTRAINT uq_reservation_line_no   UNIQUE (tenant_id, hotel_id, reservation_id, line_no),
    CONSTRAINT uq_reservation_line_id_tenant UNIQUE (id, tenant_id),
    -- 供 room_occupancy 的 (line_id, reservation_id, hotel_id) 复合外键引用
    CONSTRAINT uq_reservation_line_ref UNIQUE (id, reservation_id, hotel_id),
    CONSTRAINT ck_reservation_line_no  CHECK (line_no > 0),
    CONSTRAINT ck_reservation_line_status CHECK (status IN ('PENDING', 'CONFIRMED', 'CHECKED_IN',
                                                            'CHECKED_OUT', 'CANCELLED', 'EXPIRED', 'NO_SHOW')),
    -- 与占用表同一套区间规则：非空、有界、规范半开、下界 < 上界
    CONSTRAINT ck_reservation_line_range CHECK (
        NOT isempty(planned_range)
        AND NOT lower_inf(planned_range)
        AND NOT upper_inf(planned_range)
        AND lower_inc(planned_range)
        AND NOT upper_inc(planned_range)
        AND lower(planned_range) < upper(planned_range)
    )
);

COMMENT ON TABLE reservation_line IS '订单行（L）：一间房需求，不设可变 quantity；实际分配由占用表表达';
COMMENT ON COLUMN reservation_line.planned_range IS '购买区间（半开 [in,out)）；与实际分配的区间不必永久相等，换房按命令与历史分段核对';
COMMENT ON COLUMN reservation_line.requested_room_type_id IS '要求房型；实际房型可不同（升级/换型需保留政策依据，S3-07）';

CREATE INDEX idx_reservation_line_reservation ON reservation_line (tenant_id, hotel_id, reservation_id);

CREATE TABLE room_occupancy (
    id             uuid      PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid      NOT NULL,
    hotel_id       uuid      NOT NULL,
    room_id        uuid      NOT NULL,
    kind           text      NOT NULL,
    reservation_id uuid,
    line_id        uuid,
    stay_range     daterange NOT NULL,
    status         text      NOT NULL,
    expires_at     timestamptz,
    reason_code    text      NOT NULL,
    version        bigint    NOT NULL DEFAULT 0,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_room_occupancy_room FOREIGN KEY (room_id, hotel_id)
        REFERENCES physical_room (id, hotel_id),
    -- 复合外键：占用必须指向同一订单、同一门店的订单行；不存在"部分空引用"
    CONSTRAINT fk_room_occupancy_line FOREIGN KEY (line_id, reservation_id, hotel_id)
        REFERENCES reservation_line (id, reservation_id, hotel_id),
    CONSTRAINT uq_room_occupancy_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT ck_room_occupancy_kind CHECK (
        (kind = 'RESERVATION'
            AND reservation_id IS NOT NULL
            AND line_id IS NOT NULL
            AND status IN ('HELD', 'CONFIRMED', 'CHECKED_IN', 'RELEASED', 'EXPIRED', 'COMPLETED', 'SUPERSEDED')
            -- HELD 必须有截止点；非 HELD 允许保留曾有截止点
            AND (status <> 'HELD' OR expires_at IS NOT NULL))
        OR
        (kind = 'BLOCK'
            AND reservation_id IS NULL
            AND line_id IS NULL
            AND expires_at IS NULL
            AND status IN ('BLOCKED', 'UNBLOCKED'))
    ),
    CONSTRAINT ck_room_occupancy_reason CHECK (length(btrim(reason_code)) > 0),
    CONSTRAINT ck_room_occupancy_range CHECK (
        NOT isempty(stay_range)
        AND NOT lower_inf(stay_range)
        AND NOT upper_inf(stay_range)
        AND lower_inc(stay_range)
        AND NOT upper_inc(stay_range)
        AND lower(stay_range) < upper(stay_range)
    ),
    -- 同房间有效占用区间互斥（预订与维修共用这一条；相邻区间因半开而允许）
    CONSTRAINT ex_room_occupancy_effective EXCLUDE USING gist (
        room_id WITH =,
        stay_range WITH &&
    ) WHERE (status IN ('HELD', 'CONFIRMED', 'CHECKED_IN', 'BLOCKED'))
);

COMMENT ON TABLE room_occupancy IS '房间占用事实（统一 A/B）：预订占用与封房/维修同表同谓词，是"可售"的唯一事实源';
COMMENT ON COLUMN room_occupancy.kind IS 'RESERVATION 订单占用；BLOCK 封房/维修。两者共享同一排斥约束';
COMMENT ON COLUMN room_occupancy.expires_at IS '仅 HELD 必填；超过截止并不自动释放，必须由事务化 ExpireHold 改写状态（S1-10）';
COMMENT ON CONSTRAINT ex_room_occupancy_effective ON room_occupancy IS '有效占用互斥：直接连库插入同样被拒绝；RELEASED/UNBLOCKED 等终态退出谓词后可再占';

-- 一个订单行至多一条有效占用：防止"同一间需求被占两次"
CREATE UNIQUE INDEX uq_room_occupancy_active_line
    ON room_occupancy (tenant_id, hotel_id, line_id)
    WHERE kind = 'RESERVATION' AND status IN ('HELD', 'CONFIRMED', 'CHECKED_IN');

CREATE INDEX idx_room_occupancy_line ON room_occupancy (tenant_id, hotel_id, line_id);
CREATE INDEX idx_room_occupancy_room ON room_occupancy (tenant_id, hotel_id, room_id);
-- 仅为"发现过期候选"服务，不参与可用性判定（索引谓词不使用 now()）
CREATE INDEX idx_room_occupancy_expiring ON room_occupancy (expires_at, reservation_id)
    WHERE kind = 'RESERVATION' AND status = 'HELD';
