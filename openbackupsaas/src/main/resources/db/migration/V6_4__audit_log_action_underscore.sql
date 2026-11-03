-- =====================================================================
-- V6_4 放宽审计动作名校验以支持下划线（S1-09）
--
-- 依据：S1-09 新增审计动作 reservation.no_show（见 AuditActions）。
-- 原约束
--   ck_audit_log_action CHECK (action ~ '^[a-z]+(\.[a-z]+)+$')
-- 拒绝包含下划线的动作名（[a-z]+ 不含下划线），导致 no_show
-- 这类多词动作无法写入审计日志。
--
-- 与 V6_3 对齐：每段仍以字母开头、可由字母与下划线组成；
-- 仍禁止数字、大写、前导下划线、空白以及点以外的分隔符。
-- =====================================================================

ALTER TABLE audit_log
    DROP CONSTRAINT ck_audit_log_action;

ALTER TABLE audit_log
    ADD CONSTRAINT ck_audit_log_action
    CHECK (action ~ '^[a-z][a-z_]*(\.[a-z][a-z_]*)+$');
