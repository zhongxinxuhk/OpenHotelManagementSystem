/**
 * identity 模块：登录、会话、权限点、门店数据范围。
 *
 * <p><b>边界：</b>不得自行解析请求体里的 tenant_id；租户与门店范围只能来自可信身份上下文（BL-07）
 *
 * <p><b>依据：</b>S1-03、S3-02、ADR-008；模块依赖规则见 docs/architecture/module-boundaries.md（S0-09 交付）。
 * <p><b>状态：</b>S1-03 已实现登录/登出/改密/当前身份、RBAC 权限点、门店数据范围与会话回收；
 * 员工与角色的增删改接口、平台运维身份（A-05，DEC-02 阻塞）尚未实现 —— 进度以
 * docs/development/feature-catalog.md 为准；会话方式与隔离取舍见 ADR-016。
 */
package cn.sec.hn.openbackupsaas.identity;
