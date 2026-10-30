-- =====================================================================
-- 【已废弃 · S1-13】本脚本不再被任何测试引用。
--
-- 为什么保留：历史验证记录（S1-02 起）多处引用这个路径，删掉会让那些链接失效，
-- 而它记录的删除顺序仍有参考价值。
--
-- 为什么废弃：它要求人手维护删除清单，加表忘一行就会让整批用例拿脏库跑，
-- 且报错只说"第 N 条语句失败"、不说是哪张表——R-20261030-10 / -13 / -14 三次栽在这里。
-- 现在由 cn.sec.hn.openbackupsaas.support.DatabaseCleaner 从 pg_constraint 读外键依赖、
-- 按拓扑序清理并断言全库为空；新增表无需改任何脚本。
-- =====================================================================

-- 集成测试前置清理：按依赖顺序删除，不用 TRUNCATE（应用账号没有 TRUNCATE 权限，
-- 这正是"应用账号只有 DML"的分权体现）。
-- 顺序：事件 → 审计 → 流水（引用操作）→ 投影（引用房间）→ 占用 → 订单行 → 订单 → 幂等记录 → 房间 → 房型 → 会话 → 门店范围 → 角色绑定 → 账号/角色 → 组织。
-- permission 是全局字典（由迁移写入），不属于测试数据，故意不删。
DELETE FROM outbox_event;
DELETE FROM audit_log;
DELETE FROM inventory_ledger;
DELETE FROM room_inventory_day;
DELETE FROM room_occupancy;
DELETE FROM reservation_line;
DELETE FROM reservation;
DELETE FROM idempotency_record;
DELETE FROM physical_room;
DELETE FROM room_type;
DELETE FROM user_session;
DELETE FROM user_hotel_scope;
DELETE FROM user_role;
DELETE FROM role_permission;
DELETE FROM app_user;
DELETE FROM role;
DELETE FROM hotel;
DELETE FROM hotel_group;
DELETE FROM tenant;
