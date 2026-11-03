-- =====================================================================
-- V5 流水、审计与事件基线（S1-06，DB-C 前半）
--
-- 三张表的共同点：**只追加**。
--   - inventory_ledger 是库存效果事实，重复入账等于凭空多出一间房；
--   - audit_log 是关键动作记录，改旧记录等于抹掉证据；
--   - outbox_event 的载荷一旦提交不再改写，只更新投递元数据。
-- 因此本迁移不建任何 UPDATE 之外的便利通道，Mapper 也不提供 update/delete 语句。
--
-- 与字典的差异（依赖未到，不是遗漏）：
--   - 三张表都暂缺 operation_id（幂等记录）与外键，S1-07 落表后补；
--   - inventory_ledger 的行级唯一暂用 (tenant_id, hotel_id, room_id, stay_date, effect_kind)，
--     S1-07 加上 operation_id 后会收窄为"操作级"唯一，届时按迁移调整并重新验证。
-- =====================================================================

CREATE TABLE inventory_ledger (
    id                uuid   PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid   NOT NULL,
    hotel_id          uuid   NOT NULL,
    room_id           uuid   NOT NULL,
    stay_date         date   NOT NULL,
    effect_kind       text   NOT NULL,
    delta             smallint NOT NULL,
    reason_code       text   NOT NULL,
    effective_from_date date NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_inventory_ledger_room FOREIGN KEY (room_id, hotel_id)
        REFERENCES physical_room (id, hotel_id),
    -- 同一房间、同一天、同一种效果至多一条：重复的业务效果不能重复入账
    CONSTRAINT uq_inventory_ledger_effect UNIQUE (tenant_id, hotel_id, room_id, stay_date, effect_kind),
    CONSTRAINT ck_inventory_ledger_kind  CHECK (effect_kind IN ('RESERVATION', 'BLOCK')),
    CONSTRAINT ck_inventory_ledger_delta CHECK (delta IN (-1, 1)),
    CONSTRAINT ck_inventory_ledger_reason CHECK (length(btrim(reason_code)) > 0),
    CONSTRAINT ck_inventory_ledger_window CHECK (effective_from_date <= stay_date)
);

COMMENT ON TABLE inventory_ledger IS '库存效果流水：按"房间 + 住宿日"记录一次效果，是库存核对的事实源';
COMMENT ON COLUMN inventory_ledger.delta IS '占用为 -1，释放为 +1；只取 ±1，避免用一次多行表达数量';
COMMENT ON COLUMN inventory_ledger.stay_date IS '住宿日（半开区间的每一天），不是订单的入住/离店时刻';
COMMENT ON CONSTRAINT uq_inventory_ledger_effect ON inventory_ledger IS '重复同一业务效果不重复入账；S1-07 后收窄为操作级唯一';

CREATE INDEX idx_inventory_ledger_room_day ON inventory_ledger (tenant_id, hotel_id, room_id, stay_date);

CREATE TABLE audit_log (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      uuid        NOT NULL,
    hotel_id       uuid,
    action         text        NOT NULL,
    actor_type     text        NOT NULL,
    actor_id       uuid,
    target_type    text        NOT NULL,
    target_id      uuid,
    before_version bigint,
    after_version  bigint,
    change_summary jsonb,
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_audit_log_action      CHECK (action ~ '^[a-z]+(\.[a-z]+)+$'),
    CONSTRAINT ck_audit_log_actor_type  CHECK (actor_type IN ('USER', 'SYSTEM', 'TASK', 'CHANNEL')),
    CONSTRAINT ck_audit_log_target_type CHECK (length(btrim(target_type)) > 0),
    CONSTRAINT ck_audit_log_actor       CHECK (actor_type = 'SYSTEM' OR actor_id IS NOT NULL)
);

COMMENT ON TABLE audit_log IS '关键动作审计：一次命令可写多条；目标是多态引用，归属由服务层校验，不声称有外键';
COMMENT ON COLUMN audit_log.action IS '动作名，小写点分（如 occupancy.hold）；取值目录见 AuditActions';
COMMENT ON COLUMN audit_log.change_summary IS '只放最小必要字段，不写口令、令牌、证件等敏感原文';
COMMENT ON COLUMN audit_log.hotel_id IS '可空：租户级动作（如租户口径变更）没有门店归属';

CREATE INDEX idx_audit_log_target ON audit_log (tenant_id, target_type, target_id, occurred_at);
CREATE INDEX idx_audit_log_action ON audit_log (tenant_id, action, occurred_at);

CREATE TABLE outbox_event (
    event_id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL,
    hotel_id         uuid,
    aggregate_type   text        NOT NULL,
    aggregate_id     uuid        NOT NULL,
    aggregate_version bigint     NOT NULL,
    event_ordinal    integer     NOT NULL,
    event_type       text        NOT NULL,
    schema_version   integer     NOT NULL DEFAULT 1,
    payload          jsonb       NOT NULL,
    occurred_at      timestamptz NOT NULL DEFAULT now(),
    published_at     timestamptz,
    attempt_count    integer     NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz,
    lease_token      uuid,
    lease_until      timestamptz,
    CONSTRAINT uq_outbox_event_position UNIQUE (tenant_id, aggregate_type, aggregate_id,
                                                aggregate_version, event_ordinal),
    CONSTRAINT ck_outbox_event_ordinal  CHECK (event_ordinal >= 1),
    CONSTRAINT ck_outbox_event_version  CHECK (aggregate_version >= 0 AND schema_version >= 1),
    CONSTRAINT ck_outbox_event_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbox_event_published CHECK (published_at IS NULL OR published_at >= occurred_at),
    CONSTRAINT ck_outbox_event_lease    CHECK ((lease_token IS NULL AND lease_until IS NULL)
                                               OR (lease_token IS NOT NULL AND lease_until IS NOT NULL))
);

COMMENT ON TABLE outbox_event IS 'Outbox：业务提交的同事务事件；至少一次投递由 S2-01 的发布器负责';
COMMENT ON COLUMN outbox_event.payload IS '提交后不再改写，只更新投递元数据；改载荷等于伪造已发布的事实';
COMMENT ON COLUMN outbox_event.event_ordinal IS '同一聚合同一版本内从 1 递增，用于区分同版本多事件';
COMMENT ON COLUMN outbox_event.lease_token IS '发布租约；有 token 必有到期时间，避免无限占用';

CREATE INDEX idx_outbox_pending ON outbox_event (next_attempt_at, event_id)
    WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate ON outbox_event (tenant_id, aggregate_type, aggregate_id, aggregate_version);
