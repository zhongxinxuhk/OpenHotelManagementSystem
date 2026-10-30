-- =====================================================================
-- V11：死信、人工处置留痕与运维权限点（S2-04）
--
-- 两件事：
--   dead_letter      —— 接不下来的消息，落库而不是只写日志（S2-02 的遗留缺口）
--   ops_intervention —— 每次人工处置的记录：谁、为什么、动手前后是什么状态
--
-- 为什么处置记录要独立成表而不是写进被处置对象的 last_error：
-- 一次对象可能被处置多次（重放→再次失败→再处置），而 last_error 只有一格。
-- 事后追责要的是"完整序列 + 操作者 + 原因"，一格字符串给不了。
-- =====================================================================

CREATE TABLE dead_letter (
    dead_letter_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- 解析失败时可能连租户都取不出来，因此允许为空；宁可记不全，也不能因此丢掉原始报文
    tenant_id      uuid,
    source         text        NOT NULL,
    message_body   text        NOT NULL,
    error          text        NOT NULL,
    status         text        NOT NULL DEFAULT 'PENDING',
    received_at    timestamptz NOT NULL DEFAULT now(),
    settled_at     timestamptz,
    CONSTRAINT ck_dead_letter_status CHECK (status IN ('PENDING', 'REPLAYED', 'DISMISSED')),
    CONSTRAINT ck_dead_letter_settled
        CHECK ((status = 'PENDING' AND settled_at IS NULL)
            OR (status <> 'PENDING' AND settled_at IS NOT NULL))
);

COMMENT ON TABLE dead_letter IS
    '接不下来的消息原文 + 失败原因。落库才谈得上重放；只写日志等于"丢了但留了个印"。';
COMMENT ON COLUMN dead_letter.message_body IS
    '原始报文原样保存。重放要用它，改写它就等于重放一条不存在过的消息。';

CREATE INDEX idx_dead_letter_pending ON dead_letter (received_at) WHERE status = 'PENDING';

CREATE TABLE ops_intervention (
    intervention_id uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid,
    target_type     text        NOT NULL,
    target_id       uuid        NOT NULL,
    action          text        NOT NULL,
    operator_id     uuid        NOT NULL,
    operator_name   text        NOT NULL,
    -- 高风险处置（资金/库存类）要求第二位操作者，普通处置为空
    reviewer_name   text,
    reason          text        NOT NULL,
    before_state    text        NOT NULL,
    after_state     text        NOT NULL,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_ops_intervention_target
        CHECK (target_type IN ('DEAD_LETTER', 'EXTERNAL_TASK', 'INBOX_EVENT')),
    CONSTRAINT ck_ops_intervention_action
        CHECK (action IN ('REPLAY', 'DISMISS', 'RESOLVE_SUCCEEDED', 'RESOLVE_FAILED')),
    CONSTRAINT ck_ops_intervention_reason CHECK (length(reason) >= 4)
);

COMMENT ON TABLE ops_intervention IS
    '人工处置审计：每次重放/忽略/改判一条记录，含操作者、复核人、原因与前后状态。';
COMMENT ON COLUMN ops_intervention.before_state IS
    '处置前状态快照（不只是状态名：含尝试次数等关键字段），否则事后无法证明"改前是什么"。';

CREATE INDEX idx_ops_intervention_target ON ops_intervention (target_type, target_id, occurred_at DESC);

-- 运维权限点：读与操作分开。只读用于盯盘，操作会改外部系统的执行结果，必须单独授予。
-- 命名沿用「资源:动作」；与 reservation:write / inventory:write 同级粒度。
INSERT INTO permission (code, name, description) VALUES
    ('ops:read',    '查看待处置队列', '死信、卡住的外部任务、挂起事件的只读查询'),
    ('ops:operate', '执行人工处置',   '重放/忽略死信、重放外部任务、人工结案（高风险需复核）')
ON CONFLICT (code) DO NOTHING;
