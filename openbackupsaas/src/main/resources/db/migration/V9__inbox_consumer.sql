-- =====================================================================
-- V9：Inbox 消费去重与位点（S2-02）
--
-- 两张表解决两个不同的问题，不要混：
--   inbox_event            —— 这条消息我处理过没有？（按（消费者, 事件）唯一）
--   inbox_consumer_position —— 这个聚合我推到第几版了？（按（消费者, 聚合）唯一）
-- 只有前者会漏掉"重复的一条旧事件被当成新事件应用"，只有后者会漏掉
-- "同一事件被处理两次"，所以两者都要。
-- =====================================================================

CREATE TABLE inbox_event (
    consumer_name     text        NOT NULL,
    event_id          uuid        NOT NULL,
    tenant_id         uuid        NOT NULL,
    hotel_id          uuid,
    aggregate_type    text        NOT NULL,
    aggregate_id      uuid        NOT NULL,
    aggregate_version bigint      NOT NULL,
    event_ordinal     integer     NOT NULL,
    event_type        text        NOT NULL,
    schema_version    integer     NOT NULL DEFAULT 1,
    occurred_at       timestamptz,
    payload           jsonb       NOT NULL,
    outcome           text        NOT NULL,
    detail            text,
    received_at       timestamptz NOT NULL DEFAULT now(),
    settled_at        timestamptz,
    CONSTRAINT pk_inbox_event PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT ck_inbox_event_outcome
        CHECK (outcome IN ('PROCESSED', 'IGNORED', 'SKIPPED_STALE', 'PARKED_GAP')),
    -- 挂起是唯一"未结案"的状态：它必须能被重放，因此不写 settled_at。
    -- 其余三种都是结论，必须带结算时间，否则"处理过没有"就无从判断。
    CONSTRAINT ck_inbox_event_settled
        CHECK ((outcome = 'PARKED_GAP' AND settled_at IS NULL)
            OR (outcome <> 'PARKED_GAP' AND settled_at IS NOT NULL)),
    CONSTRAINT ck_inbox_event_position
        CHECK (aggregate_version >= 0 AND event_ordinal >= 1),
    CONSTRAINT ck_inbox_event_schema CHECK (schema_version >= 1)
);

COMMENT ON TABLE inbox_event IS
    'Inbox：按（消费者, 事件）去重。主键即去重键——先插入再判定，插入冲突说明这条已经处理过。';
COMMENT ON COLUMN inbox_event.payload IS
    '消息体快照，仅用于挂起后重放。不存它就等于把"挂起"做成"丢弃"——缺口补齐时无从重放。';
COMMENT ON COLUMN inbox_event.occurred_at IS
    '事件在业务侧发生的时间。重放时要保留原始发生时间，否则追溯链断在这里。';
COMMENT ON COLUMN inbox_event.outcome IS
    'PROCESSED 已应用 / IGNORED 本消费者不处理该事件类型 / SKIPPED_STALE 旧事件 / PARKED_GAP 版本缺口挂起';

-- 挂起事件的重放扫描：按聚合 + 版本顺序推进，才能保证缺口补齐后按序追上。
CREATE INDEX idx_inbox_event_parked
    ON inbox_event (consumer_name, aggregate_id, aggregate_version, event_ordinal)
    WHERE outcome = 'PARKED_GAP';

-- 为什么这里不建外键到 tenant / 聚合表：
-- inbox 是"消息处理台账"，它的生命周期与业务聚合不同——订单可能被归档清理，
-- 但"这条消息处理过"这个事实必须长期保留，否则重投的老消息会被当成新消息再执行一次。
-- 加外键会让清理聚合时被迫删台账，正好破坏去重的依据。

CREATE TABLE inbox_consumer_position (
    consumer_name  text        NOT NULL,
    tenant_id      uuid        NOT NULL,
    aggregate_type text        NOT NULL,
    aggregate_id   uuid        NOT NULL,
    last_version   bigint      NOT NULL,
    last_ordinal   integer     NOT NULL,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_inbox_consumer_position
        PRIMARY KEY (consumer_name, tenant_id, aggregate_type, aggregate_id),
    CONSTRAINT ck_inbox_consumer_position
        CHECK (last_version >= 0 AND last_ordinal >= 1)
);

COMMENT ON TABLE inbox_consumer_position IS
    '每个消费者在每个聚合上推进到（版本, 序号）。重复与旧事件靠它判定，缺口也靠它发现。';
COMMENT ON COLUMN inbox_consumer_position.last_ordinal IS
    '同一版本内可能有多条事件（event_ordinal 从 1 递增），只记版本会漏判"同版本第二条"是重复。';
