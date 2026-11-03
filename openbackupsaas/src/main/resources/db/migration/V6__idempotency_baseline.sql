-- =====================================================================
-- V6 幂等记录基线（S1-07，DB-C 后半）
--
-- 依据：docs/domain/data-dictionary.md §6、ADR-004。
--
-- 幂等作用域是五元组 (tenant_id, hotel_id, caller_namespace, command_name, idempotency_key)：
-- 少了调用主体，"A 用同一个键重试"会被"B 的结果"顶掉；
-- 少了门店，跨店共用一个键会把两笔不同业务当成同一笔。
--
-- 三条状态语义：
--   IN_PROGRESS：已占位、业务未定。并发的第二个请求撞唯一键 → 拒绝，不重复执行。
--   SUCCEEDED：业务已提交。重试必须返回**同一个结果引用**，而不是再执行一次。
--   FAILED：业务终态失败（不是系统崩溃）。重试返回同一个失败结论，避免无意义重放。
--
-- 关于"提交不确定"：业务与幂等记录在**同一个事务**里提交。
--   提交成功但响应丢失时，记录已经是 SUCCEEDED，重试能查回原结果；
--   业务失败回滚时，IN_PROGRESS 行随之消失，下一次可以重来——没有任何副作用被吞掉。
-- =====================================================================

CREATE TABLE idempotency_record (
    operation_id       uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          uuid        NOT NULL,
    hotel_id           uuid        NOT NULL,
    caller_namespace   text        NOT NULL,
    command_name       text        NOT NULL,
    idempotency_key    text        NOT NULL,
    hash_version       integer     NOT NULL DEFAULT 1,
    request_hash       text        NOT NULL,
    status             text        NOT NULL DEFAULT 'IN_PROGRESS',
    result_code        text,
    result_ref         uuid,
    response_snapshot  jsonb,
    created_at         timestamptz NOT NULL DEFAULT now(),
    completed_at       timestamptz,
    CONSTRAINT uq_idempotency_scope UNIQUE (tenant_id, hotel_id, caller_namespace, command_name,
                                            idempotency_key),
    -- 供 inventory_ledger 的复合外键引用：流水行必须指向本租户本门店的一次操作
    CONSTRAINT uq_idempotency_operation UNIQUE (tenant_id, hotel_id, operation_id),
    CONSTRAINT ck_idempotency_namespace   CHECK (caller_namespace ~ '^[a-z][a-z0-9-]{1,31}$'),
    CONSTRAINT ck_idempotency_command     CHECK (command_name ~ '^[a-z]+(\.[a-z]+)+$'),
    CONSTRAINT ck_idempotency_key         CHECK (length(btrim(idempotency_key)) BETWEEN 1 AND 128),
    -- 摘要固定为 64 位十六进制；换算法必须升 hash_version，不能在同一版本里换字符串
    CONSTRAINT ck_idempotency_hash        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_idempotency_hash_version CHECK (hash_version >= 1),
    CONSTRAINT ck_idempotency_status      CHECK (status IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED')),
    -- 处理中没有完成时间；终态必须有完成时间
    CONSTRAINT ck_idempotency_completed   CHECK ((status = 'IN_PROGRESS' AND completed_at IS NULL)
                                                 OR (status <> 'IN_PROGRESS' AND completed_at IS NOT NULL)),
    -- 成功必须有结果引用：没有引用的"成功"等于下次无法还原结果
    CONSTRAINT ck_idempotency_success     CHECK (status <> 'SUCCEEDED' OR result_ref IS NOT NULL),
    CONSTRAINT ck_idempotency_failure     CHECK (status <> 'FAILED' OR result_code IS NOT NULL)
);

COMMENT ON TABLE idempotency_record IS '命令幂等记录：同键同内容返回原结果，同键不同内容拒绝；与业务同事务提交';
COMMENT ON COLUMN idempotency_record.caller_namespace IS '调用主体命名空间（如 employee / channel / system）；不接受客户端冒用系统命名空间';
COMMENT ON COLUMN idempotency_record.request_hash IS '规范化请求摘要（SHA-256 十六进制）。只存摘要，不存请求原文';
COMMENT ON COLUMN idempotency_record.hash_version IS '摘要算法版本；换算法视为不同内容，由唯一键与摘要差异共同拒绝';
COMMENT ON COLUMN idempotency_record.result_ref IS '业务结果引用（如占用 id）；成功必须有，否则重试无法还原结果';
COMMENT ON COLUMN idempotency_record.response_snapshot IS '可回放的响应快照；不含令牌、口令等敏感字段';
COMMENT ON CONSTRAINT ck_idempotency_completed ON idempotency_record IS '处理中不得有完成时间；终态必须有，便于判断"卡住多久"';

CREATE INDEX idx_idempotency_pending ON idempotency_record (created_at)
    WHERE status = 'IN_PROGRESS';

-- 流水行指向真实存在的一次操作：补上 S1-06 预告的外键
ALTER TABLE inventory_ledger
    ADD CONSTRAINT fk_inventory_ledger_operation FOREIGN KEY (operation_id, tenant_id, hotel_id)
        REFERENCES idempotency_record (operation_id, tenant_id, hotel_id);

COMMENT ON CONSTRAINT fk_inventory_ledger_operation ON inventory_ledger IS
    '库存效果必须归属一次真实操作，杜绝虚构 operation_id 的流水行';

-- 新增权限点：查询结果用
INSERT INTO permission (code, name, description) VALUES
    ('operation:read', '查询操作结果', '按操作标识查询幂等记录与业务结果引用')
ON CONFLICT (code) DO NOTHING;
