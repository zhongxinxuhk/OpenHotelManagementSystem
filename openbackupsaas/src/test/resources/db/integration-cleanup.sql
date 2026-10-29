-- 集成测试前置清理：按依赖顺序删除，不用 TRUNCATE（应用账号没有 TRUNCATE 权限，
-- 这正是"应用账号只有 DML"的分权体现）。
-- 顺序：事件 → 审计 → 流水 → 占用 → 订单行 → 订单 → 房间 → 房型 → 会话 → 门店范围 → 角色绑定 → 账号/角色 → 组织。
-- permission 是全局字典（由迁移写入），不属于测试数据，故意不删。
DELETE FROM outbox_event;
DELETE FROM audit_log;
DELETE FROM inventory_ledger;
DELETE FROM room_occupancy;
DELETE FROM reservation_line;
DELETE FROM reservation;
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
