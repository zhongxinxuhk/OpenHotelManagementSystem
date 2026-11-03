-- =====================================================================
-- V8：Outbox 投递（S2-01）
--
-- V5 建表时已预留 published_at / attempt_count / next_attempt_at / lease_token /
-- lease_until，本迁移只补两样东西：诊断字段与领取索引。
-- =====================================================================

-- 最后一次失败原因。没有它，"事件卡住了"只能靠翻应用日志；
-- 有了它，运维能直接在库里看到是交换机不存在还是连接被拒。
ALTER TABLE outbox_event ADD COLUMN last_error text;

COMMENT ON COLUMN outbox_event.last_error IS
    '最近一次发布失败的原因（截断存储）；发布成功后清空。用于定位卡住的事件，不是审计。';

-- 领取索引：只覆盖"未发布"的行。
-- 发布器每轮只关心未发布事件，且按发生时间从早到晚领取；全表索引会被已发布的历史撑大，
-- 而历史事件永远占绝大多数（每个订单至少产生 1 条）。
CREATE INDEX idx_outbox_event_due
    ON outbox_event (next_attempt_at, occurred_at, event_id)
    WHERE published_at IS NULL;

COMMENT ON INDEX idx_outbox_event_due IS
    'S2-01 发布器的领取索引；部分索引只含未发布事件，避免历史事件把索引撑大';

-- next_attempt_at 语义收敛为"早于此刻才允许领取"。
-- 建表时它是可空的（NULL 表示未设置），领取条件里就要写 COALESCE，索引会失效；
-- 这里回填并加默认值与非空约束，让条件保持 sargable。
UPDATE outbox_event SET next_attempt_at = occurred_at WHERE next_attempt_at IS NULL;
ALTER TABLE outbox_event ALTER COLUMN next_attempt_at SET DEFAULT now();
ALTER TABLE outbox_event ALTER COLUMN next_attempt_at SET NOT NULL;

COMMENT ON COLUMN outbox_event.attempt_count IS
    '领取次数（不是成功次数）：每次拿到租约即加一。崩溃在发布前也会加，这是租约式至少一次投递的正常代价。';
COMMENT ON COLUMN outbox_event.lease_until IS
    '租约到期时间；到期后其他 Worker 可重新领取。发布成功但未能标记时会重发——允许重复，不允许丢失。';
