-- =====================================================================
-- V3 房型与实体房基线：room_type / physical_room（S1-04，迁移批次 DB-B 前半）
--
-- 约定延续 V1/V2：
--   1. 门店业务表统一带 (tenant_id, hotel_id)：既要能按门店分摊查询，
--      也要能被"同店"复合外键引用。
--   2. 房间 → 房型使用 (room_type_id, hotel_id) 复合外键，
--      使"把 A 店的房间挂到 B 店的房型"在数据库层直接失败，不是靠应用校验。
--   3. 状态与清洁状态是**两个独立字段**：停用属于运营事实，清洁属于房务事实。
--      本迁移不提供清洁状态的写入口（流转归 S3-06），先保证它不被业务写路径覆盖。
--   4. 维修区间（room_block）不在本迁移：它必须与预订占用共用互斥协议（ADR-002），归 S1-05。
--   5. 删除一律 RESTRICT：房间有业务引用后不允许物理删除，只能停用。
--
-- 待办提示：
--   - DEC-05（未分房配额）未定案，因此房型上**不加**任何"可售数量/配额"字段；
--     首期按实体房分配，配额模型定案后另开迁移。
--   - 床位/面积等展示属性未建模：无业务规则依赖它们，先不加避免空列。
-- =====================================================================

CREATE TABLE room_type (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL,
    hotel_id          uuid        NOT NULL,
    code              text        NOT NULL,
    name              text        NOT NULL,
    capacity_adults   smallint    NOT NULL DEFAULT 2,
    capacity_children smallint    NOT NULL DEFAULT 0,
    bed_type          text,
    status            text        NOT NULL DEFAULT 'ACTIVE',
    version           bigint      NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_room_type_hotel FOREIGN KEY (hotel_id, tenant_id) REFERENCES hotel (id, tenant_id),
    CONSTRAINT uq_room_type_code           UNIQUE (tenant_id, hotel_id, code),
    CONSTRAINT uq_room_type_id_tenant      UNIQUE (id, tenant_id),
    -- 供 physical_room 的 (room_type_id, hotel_id) 复合外键引用
    CONSTRAINT uq_room_type_id_hotel       UNIQUE (id, hotel_id),
    CONSTRAINT ck_room_type_code_fmt       CHECK (code ~ '^[a-z0-9][a-z0-9-]{1,31}$'),
    CONSTRAINT ck_room_type_name_blank     CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_room_type_capacity       CHECK (capacity_adults BETWEEN 1 AND 20
                                                  AND capacity_children BETWEEN 0 AND 20),
    CONSTRAINT ck_room_type_bed_type       CHECK (bed_type IS NULL OR length(btrim(bed_type)) BETWEEN 1 AND 32),
    CONSTRAINT ck_room_type_status         CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

COMMENT ON TABLE room_type IS '房型：门店内的销售单位；编码同店唯一，跨店可重名';
COMMENT ON COLUMN room_type.bed_type IS '床型展示值（如 KING / TWIN），不做枚举约束：取值为运营字典，不影响库存事实';
COMMENT ON COLUMN room_type.capacity_adults IS '成人数上限，供可售校验使用（校验在 S1-05 之后的预订路径实现）';
COMMENT ON COLUMN room_type.status IS 'ACTIVE 可售；INACTIVE 停售（存量业务仍可查）';

CREATE INDEX idx_room_type_hotel ON room_type (tenant_id, hotel_id);

CREATE TABLE physical_room (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid        NOT NULL,
    hotel_id           uuid        NOT NULL,
    room_type_id       uuid        NOT NULL,
    code               text        NOT NULL,
    floor              smallint,
    status             text        NOT NULL DEFAULT 'ACTIVE',
    housekeeping_status text       NOT NULL DEFAULT 'DIRTY',
    notes              text,
    version            bigint      NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_physical_room_hotel FOREIGN KEY (hotel_id, tenant_id) REFERENCES hotel (id, tenant_id),
    -- 复合外键：房型必须与房间同店，跨店归属在数据库层失败
    CONSTRAINT fk_physical_room_type_same_hotel FOREIGN KEY (room_type_id, hotel_id)
        REFERENCES room_type (id, hotel_id),
    CONSTRAINT uq_physical_room_code      UNIQUE (tenant_id, hotel_id, code),
    CONSTRAINT uq_physical_room_id_tenant UNIQUE (id, tenant_id),
    -- 房号允许数字与字母（常见 0801 / A-12），不强制小写
    CONSTRAINT ck_physical_room_code_fmt  CHECK (code ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,15}$'),
    CONSTRAINT ck_physical_room_floor     CHECK (floor IS NULL OR floor BETWEEN -10 AND 200),
    CONSTRAINT ck_physical_room_status    CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_physical_room_housekeeping CHECK (housekeeping_status IN ('CLEAN', 'DIRTY', 'INSPECTED'))
);

COMMENT ON TABLE physical_room IS '实体房间：可被占用的最小资源单位；房号同店唯一';
COMMENT ON COLUMN physical_room.code IS '房号，同店唯一；允许大写与连字符（0801 / A-12）';
COMMENT ON COLUMN physical_room.status IS '运营状态：ACTIVE 可排房；INACTIVE 停用（不删除，历史引用保留）';
COMMENT ON COLUMN physical_room.housekeeping_status IS '房务状态：CLEAN / DIRTY / INSPECTED。与可售、占用**无关**，不得因它改写库存事实；写入口归 S3-06';
COMMENT ON COLUMN physical_room.floor IS '楼层，可为空（园区/独栋等无楼层概念的场景）；不参与占用计算';

CREATE INDEX idx_physical_room_hotel ON physical_room (tenant_id, hotel_id);
CREATE INDEX idx_physical_room_type ON physical_room (room_type_id, hotel_id);

-- 新增权限点：房型与房间的读写。
-- 命名规则「资源:动作」由 ck_permission_code_fmt 强制；新增权限点必须同时更新
-- docs/development/feature-catalog.md 与代码里的 @PreAuthorize 常量，三处不一致时不算完成。
INSERT INTO permission (code, name, description) VALUES
    ('room:read',  '读取房型与房间', '查看房型、实体房间、楼层与状态'),
    ('room:write', '维护房型与房间', '创建房型与房间、启停、修改房间属性')
ON CONFLICT (code) DO NOTHING;
