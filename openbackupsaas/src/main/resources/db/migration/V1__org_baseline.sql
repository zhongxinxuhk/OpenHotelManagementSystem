-- =====================================================================
-- V1 组织基线：租户 / 集团 / 门店（S1-02，迁移批次 DB-A）
--
-- 约定：
--   1. 每张业务表都带 tenant_id；(id, tenant_id) 复合唯一用于支撑跨表复合外键，
--      使"把 A 租户的门店挂到 B 租户的集团下"在数据库层直接失败，而不是靠应用自觉。
--   2. 主键 uuid 由应用生成（DB 默认值仅兜底），避免依赖序列与自增语义。
--   3. updated_at 由应用写入时维护；不设触发器，避免隐式改写难以审计。
--   4. status 用 text + CHECK：加值不需要改类型，但新增值仍须同步迁移与本文件。
--   5. 时区与营业日规则待 S0-05（ADR-012）定案，本迁移只落 timezone 字段，
--      不预设营业日切分时间、不预设未分房配额（DEC-05 未定）。
--   6. 删除一律 RESTRICT：有业务引用的组织记录不能被静默删除。
-- =====================================================================

CREATE TABLE tenant (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    code       text        NOT NULL,
    name       text        NOT NULL,
    status     text        NOT NULL DEFAULT 'ACTIVE',
    version    bigint      NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_code       UNIQUE (code),
    CONSTRAINT ck_tenant_code_fmt   CHECK (code ~ '^[a-z0-9][a-z0-9-]{1,31}$'),
    CONSTRAINT ck_tenant_name_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_tenant_status     CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

COMMENT ON TABLE tenant IS '签约经营主体，数据隔离边界（scope §4.1）';
COMMENT ON COLUMN tenant.code IS '租户编码，全局唯一，小写字母/数字/连字符';
COMMENT ON COLUMN tenant.status IS 'ACTIVE 可用；SUSPENDED 停止写入；CLOSED 关停（只读）';
COMMENT ON COLUMN tenant.version IS '乐观锁版本，写入时 +1';

CREATE TABLE hotel_group (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL,
    code       text        NOT NULL,
    name       text        NOT NULL,
    status     text        NOT NULL DEFAULT 'ACTIVE',
    version    bigint      NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_hotel_group_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT uq_hotel_group_code      UNIQUE (tenant_id, code),
    CONSTRAINT uq_hotel_group_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT ck_hotel_group_code_fmt  CHECK (code ~ '^[a-z0-9][a-z0-9-]{1,31}$'),
    CONSTRAINT ck_hotel_group_name_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_hotel_group_status    CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

COMMENT ON TABLE hotel_group IS '可选组织层级：一个租户下可有多个集团';
COMMENT ON CONSTRAINT uq_hotel_group_code ON hotel_group IS '同一租户内集团编码唯一，跨租户可重名';

CREATE INDEX idx_hotel_group_tenant ON hotel_group (tenant_id);

CREATE TABLE hotel (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  uuid        NOT NULL,
    group_id   uuid,
    code       text        NOT NULL,
    name       text        NOT NULL,
    timezone   text        NOT NULL,
    status     text        NOT NULL DEFAULT 'ACTIVE',
    version    bigint      NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_hotel_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    -- 复合外键：集团必须属于同一租户，跨租户挂接在数据库层失败
    CONSTRAINT fk_hotel_group_same_tenant FOREIGN KEY (group_id, tenant_id)
        REFERENCES hotel_group (id, tenant_id),
    CONSTRAINT uq_hotel_code      UNIQUE (tenant_id, code),
    CONSTRAINT uq_hotel_id_tenant UNIQUE (id, tenant_id),
    CONSTRAINT ck_hotel_code_fmt  CHECK (code ~ '^[a-z0-9][a-z0-9-]{1,31}$'),
    CONSTRAINT ck_hotel_name_blank CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_hotel_timezone  CHECK (length(btrim(timezone)) > 0),
    CONSTRAINT ck_hotel_status    CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

COMMENT ON TABLE hotel IS '门店：实体房间、当地时区与日常运营归属';
COMMENT ON COLUMN hotel.timezone IS 'IANA 时区名（如 Australia/Sydney）；取值合法性由应用校验，S0-05 后接入营业日规则';
COMMENT ON COLUMN hotel.group_id IS '可为空：直属租户的门店不挂集团';

CREATE INDEX idx_hotel_tenant ON hotel (tenant_id);
CREATE INDEX idx_hotel_group  ON hotel (group_id) WHERE group_id IS NOT NULL;
