-- =====================================================================
-- V10：持久化任务与外部调用状态（S2-03）
--
-- 解决的核心问题：对外部系统（OTA / 支付）的调用可能"结果未知"——
-- 请求发出去了、回包没回来。此时既不能当成功，也不能当失败：
-- 当成功会漏掉重试，当失败可能重复扣款/重复占房。
-- 所以任务状态里必须有"未知"，并且重试前必须先查询。
-- =====================================================================

CREATE TABLE external_task (
    task_id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        uuid        NOT NULL,
    hotel_id         uuid,
    task_type        text        NOT NULL,
    channel          text        NOT NULL,
    -- 幂等键：同一业务意图重复入队时必须复用同一个键，外部系统据此去重
    idempotency_key  text        NOT NULL,
    payload          jsonb       NOT NULL,
    status           text        NOT NULL,
    attempts         integer     NOT NULL DEFAULT 0,
    max_attempts     integer     NOT NULL,
    next_attempt_at  timestamptz NOT NULL DEFAULT now(),
    lease_token      uuid,
    lease_until      timestamptz,
    -- 最近一次失败分类：RETRYABLE / UNKNOWN / PERMANENT / RATE_LIMITED
    last_error_class text,
    last_error       text,
    result_ref       text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    completed_at     timestamptz,
    CONSTRAINT ck_external_task_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'MANUAL_REVIEW', 'DEAD')),
    CONSTRAINT ck_external_task_error_class
        CHECK (last_error_class IS NULL
            OR last_error_class IN ('RETRYABLE', 'UNKNOWN', 'PERMANENT', 'RATE_LIMITED')),
    CONSTRAINT ck_external_task_attempts
        CHECK (attempts >= 0 AND max_attempts >= 1 AND attempts <= max_attempts),
    CONSTRAINT ck_external_task_lease
        CHECK ((lease_token IS NULL AND lease_until IS NULL)
            OR (lease_token IS NOT NULL AND lease_until IS NOT NULL)),
    -- 终态必须有完成时间，进行态不能有——否则"这条到底结了没有"无从判断
    CONSTRAINT ck_external_task_completed
        CHECK ((status IN ('SUCCEEDED', 'FAILED', 'MANUAL_REVIEW', 'DEAD') AND completed_at IS NOT NULL)
            OR (status IN ('PENDING', 'RUNNING') AND completed_at IS NULL))
);

COMMENT ON TABLE external_task IS
    '外部调用的持久化任务：状态、尝试次数、租约、退避都落库，进程崩溃不丢。';
COMMENT ON COLUMN external_task.idempotency_key IS
    '业务幂等键。同一意图重复入队必须复用，外部系统据此去重；不是每次重试都换一个键。';
COMMENT ON COLUMN external_task.status IS
    'PENDING 待执行（含退避等待）/ RUNNING 持租约执行中 / SUCCEEDED / FAILED 永久失败 / MANUAL_REVIEW 转人工 / DEAD 超过最大次数';
COMMENT ON COLUMN external_task.last_error_class IS
    'UNKNOWN 表示"发出去了但不知道结果"——这类任务重试前必须先查询，不能直接重发。';

-- 领取索引：只覆盖待执行的行。历史任务会一直增长，全表索引迟早被它们撑大。
CREATE INDEX idx_external_task_due
    ON external_task (next_attempt_at, task_id)
    WHERE status = 'PENDING';

-- 运维视角：按渠道看积压与卡住的量
CREATE INDEX idx_external_task_channel
    ON external_task (channel, status);

-- 同一业务意图只允许一个未结任务：重复入队直接命中唯一键，避免同一笔操作被并行发两次。
CREATE UNIQUE INDEX uq_external_task_open_idempotency
    ON external_task (tenant_id, task_type, idempotency_key)
    WHERE status IN ('PENDING', 'RUNNING', 'MANUAL_REVIEW');

CREATE TABLE external_task_attempt (
    task_id       uuid        NOT NULL,
    attempt_no    integer     NOT NULL,
    started_at    timestamptz NOT NULL,
    finished_at   timestamptz NOT NULL,
    outcome       text        NOT NULL,
    error_class   text,
    detail        text,
    result_ref    text,
    CONSTRAINT pk_external_task_attempt PRIMARY KEY (task_id, attempt_no),
    -- 尝试记录必须随任务一起消失：孤儿尝试记录会让"重试了几次"变成两个真相
    CONSTRAINT fk_external_task_attempt FOREIGN KEY (task_id)
        REFERENCES external_task (task_id) ON DELETE CASCADE,
    CONSTRAINT ck_external_task_attempt_no CHECK (attempt_no >= 1)
);

COMMENT ON TABLE external_task_attempt IS
    '每次真实调用尝试的台账。"尝试"只统计真的发出去的调用，被限流/熔断跳过的轮次不计入，否则重试预算会被无意义的等待吃光。';
