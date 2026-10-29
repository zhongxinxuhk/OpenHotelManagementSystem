-- S1-08 追加权限点：订单写入与读取。
-- 与 V2/V6 一样只登记字典，不做业务数据；新增权限点必须同时改 feature-catalog 与 @PreAuthorize 常量。
INSERT INTO permission (code, name, description) VALUES
    ('reservation:write', '创建与修改订单', '锁房建单、确认与取消（后续任务）'),
    ('reservation:read',  '读取订单',       '订单列表与详情查询')
ON CONFLICT (code) DO NOTHING;
