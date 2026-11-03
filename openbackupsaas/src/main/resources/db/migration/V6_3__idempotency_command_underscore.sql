-- =====================================================================
-- V6_3 放宽幂等命令名校验以支持下划线（S1-09）
--
-- 依据：S1-09 新增 reservation.no_show 命令。原约束
--   ck_idempotency_command CHECK (command_name ~ '^[a-z]+(\.[a-z]+)+$')
-- 拒绝包含下划线的命令名（[a-z]+ 不含下划线），导致 no_show
-- 这类多词动词的命令无法写入幂等记录。
--
-- 命令名约定为 domain.verb，多词动词用下划线连接（如 reservation.no_show）。
-- 此处仅放宽字符集：每段仍以字母开头、可由字母与下划线组成；
-- 仍禁止数字、大写、前导下划线、空白以及点以外的分隔符。
-- =====================================================================

ALTER TABLE idempotency_record
    DROP CONSTRAINT ck_idempotency_command;

ALTER TABLE idempotency_record
    ADD CONSTRAINT ck_idempotency_command
    CHECK (command_name ~ '^[a-z][a-z_]*(\.[a-z][a-z_]*)+$');
