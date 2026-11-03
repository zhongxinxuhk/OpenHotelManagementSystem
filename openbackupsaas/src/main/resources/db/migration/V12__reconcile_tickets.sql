-- =====================================================================
-- V12 内部对账：批次、差异工单与扫描水位（S2-05）
--
-- 为什么需要落库而不是"跑一次看报告"：
-- S1-12 的 /inventory/reconcile 是**即时核对**——跑完就忘，谁也不知道同一个问题
-- 已经连续存在三周了。运维需要的是"这件事还没人管"，所以差异必须有身份、
-- 有首次发现时间、有重复次数，并且重复扫描不能造出第二张工单。
--
-- 三条设计约束：
--   1. 同一（门店, 差异类型, 目标）只能有一张**有效**工单：靠部分唯一索引保证
--      （WHERE status = 'OPEN'）。重跑命中它并累加 occurrences，而不是新建。
--   2. 差异消失后工单要自动退场（SUPERSEDED），否则看板永远挂着已经不存在的问题。
--   3. 修复只允许发生在派生表上；事实类差异没有修复动作（见 FindingKind 的分级）。
--      这一条不写在数据库里——它是代码里的类型约束，因为修复动作本身不在 SQL 层。
-- =====================================================================

-- ---------- 批次：一次扫描一行，记录窗口与统计 ----------
CREATE TABLE reconcile_run (
    run_id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL,
    hotel_id          uuid        NOT NULL,
    window_from       date        NOT NULL,
    window_to         date        NOT NULL,
    trigger_source    text        NOT NULL,
    triggered_by      uuid,
    status            text        NOT NULL DEFAULT 'RUNNING',
    findings_new      integer     NOT NULL DEFAULT 0,
    findings_repeated integer     NOT NULL DEFAULT 0,
    findings_repaired integer     NOT NULL DEFAULT 0,
    findings_open     integer     NOT NULL DEFAULT 0,
    findings_superseded integer   NOT NULL DEFAULT 0,
    started_at        timestamptz NOT NULL DEFAULT now(),
    finished_at       timestamptz,
    error             text,
    CONSTRAINT fk_reconcile_run_hotel FOREIGN KEY (hotel_id, tenant_id)
        REFERENCES hotel (id, tenant_id),
    CONSTRAINT ck_reconcile_run_window CHECK (window_to > window_from),
    CONSTRAINT ck_reconcile_run_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_reconcile_run_trigger CHECK (trigger_source IN ('SCHEDULED', 'MANUAL', 'OPS'))
);

COMMENT ON TABLE reconcile_run IS
    '一次对账扫描。窗口是左闭右开的住宿日区间；统计字段让"这一轮新增多少问题"可直接查询。';
COMMENT ON COLUMN reconcile_run.trigger_source IS
    'SCHEDULED 定时 / MANUAL 人工触发 / OPS 处置后定向复核。区分来源是为了判断"是系统在看着"还是"出事后才看"。';

CREATE INDEX idx_reconcile_run_hotel_time
    ON reconcile_run (tenant_id, hotel_id, started_at DESC);

-- 同一门店同一时刻只允许一个 RUNNING 批次：并发扫描会让工单的 occurrences 计数与
-- "谁先发现"变得不可解释，收益却为零（同一份数据扫两遍结果一样）。
CREATE UNIQUE INDEX uq_reconcile_run_single_active
    ON reconcile_run (tenant_id, hotel_id)
    WHERE status = 'RUNNING';

-- ---------- 差异工单 ----------
CREATE TABLE reconcile_finding (
    finding_id      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       uuid        NOT NULL,
    hotel_id        uuid        NOT NULL,
    kind            text        NOT NULL,
    severity        text        NOT NULL,
    target_key      text        NOT NULL,
    target_detail   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    stay_date       date,
    room_id         uuid,
    reservation_id  uuid,
    detail          text        NOT NULL,
    status          text        NOT NULL DEFAULT 'OPEN',
    occurrences     integer     NOT NULL DEFAULT 1,
    first_run_id    uuid        NOT NULL REFERENCES reconcile_run (run_id),
    last_run_id     uuid        NOT NULL REFERENCES reconcile_run (run_id),
    first_seen_at   timestamptz NOT NULL DEFAULT now(),
    last_seen_at    timestamptz NOT NULL DEFAULT now(),
    repaired_at     timestamptz,
    settled_at      timestamptz,
    settled_by      uuid,
    settle_reason   text,
    CONSTRAINT fk_reconcile_finding_hotel FOREIGN KEY (hotel_id, tenant_id)
        REFERENCES hotel (id, tenant_id),
    CONSTRAINT ck_reconcile_finding_status
        CHECK (status IN ('OPEN', 'REPAIRED', 'IGNORED', 'SUPERSEDED')),
    CONSTRAINT ck_reconcile_finding_severity
        CHECK (severity IN ('DERIVED', 'FACT', 'BACKLOG')),
    -- 只有"处理过的"才允许有结算时间：OPEN 带结算时间说明状态机被绕过了
    CONSTRAINT ck_reconcile_finding_settled
        CHECK ((status = 'OPEN' AND settled_at IS NULL) OR (status <> 'OPEN')),
    -- 忽略必须带原因：半年后没人说得清这条为什么被扔掉
    CONSTRAINT ck_reconcile_finding_ignore_reason
        CHECK (status <> 'IGNORED' OR settle_reason IS NOT NULL),
    -- 自动修复只可能发生在派生类上：事实类差异没有修复路径（代码里也没有对应分支）
    CONSTRAINT ck_reconcile_finding_repaired_kind
        CHECK (status <> 'REPAIRED' OR severity = 'DERIVED'),
    CONSTRAINT ck_reconcile_finding_occurrences CHECK (occurrences >= 1)
);

COMMENT ON TABLE reconcile_finding IS
    '一条对账差异。同一（门店, 类型, 目标）只有一张 OPEN 工单（部分唯一索引），'
    '重复扫描累加 occurrences 并推进 last_seen_at，不新建。';
COMMENT ON COLUMN reconcile_finding.severity IS
    'DERIVED 派生数据（可从权威重建，允许自动修复）/ FACT 交易事实（只能人工判断）/ '
    'BACKLOG 投递与消费积压（靠重试消化，不修数据）。';
COMMENT ON COLUMN reconcile_finding.target_key IS
    '稳定标识：同一逻辑问题的多次发现必须算出同一个值，否则去重失效、看板被刷屏。';
COMMENT ON COLUMN reconcile_finding.repaired_at IS
    '自动修复完成的时间。与 settled_at 分开：修复是机器做的，结案是流程状态。';

-- 去重的核心：同一（门店, 类型, 目标）只允许一张有效工单
CREATE UNIQUE INDEX uq_reconcile_finding_open
    ON reconcile_finding (tenant_id, hotel_id, kind, target_key)
    WHERE status = 'OPEN';

CREATE INDEX idx_reconcile_finding_open_list
    ON reconcile_finding (tenant_id, hotel_id, severity, last_seen_at DESC)
    WHERE status = 'OPEN';

CREATE INDEX idx_reconcile_finding_history
    ON reconcile_finding (tenant_id, hotel_id, status, settled_at DESC);

-- ---------- 扫描水位 ----------
-- 水位按门店推进：定时对账用"上次扫到哪儿"决定这一轮扫哪一段，
-- 不靠猜窗口大小，也不会因为停机三天就漏掉那三天的数据。
CREATE TABLE reconcile_watermark (
    tenant_id       uuid        NOT NULL,
    hotel_id        uuid        NOT NULL,
    PRIMARY KEY (tenant_id, hotel_id),
    scanned_through date        NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_reconcile_watermark_hotel FOREIGN KEY (hotel_id, tenant_id)
        REFERENCES hotel (id, tenant_id)
);

COMMENT ON TABLE reconcile_watermark IS
    '按门店的扫描水位：scanned_through 之内（含）的住宿日已经扫过。'
    '与"当前日期"分开记——重扫历史区间时不能把水位推回去，否则定时任务会重复扫旧数据。';

-- ---------- 权限点 ----------
INSERT INTO permission (code, name, description) VALUES
    ('reconcile:read',    '查看对账结果', '批次、差异工单与水位；只读'),
    ('reconcile:operate', '处置对账差异', '触发定向复核、执行派生修复、忽略差异（需原因）')
ON CONFLICT (code) DO NOTHING;
