-- 集成测试前置清理：按依赖顺序删除，不用 TRUNCATE（应用账号没有 TRUNCATE 权限，
-- 这正是"应用账号只有 DML"的分权体现）。
DELETE FROM hotel;
DELETE FROM hotel_group;
DELETE FROM tenant;
