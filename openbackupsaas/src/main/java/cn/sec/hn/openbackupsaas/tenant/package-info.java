/**
 * tenant 模块：租户、集团、门店与组织归属。
 *
 * <p><b>边界：</b>组织状态影响写入授权；跨租户关联必须被数据库外键拒绝
 *
 * <p><b>依据：</b>S1-02、ADR-009；模块依赖规则见 docs/architecture/module-boundaries.md（S0-09 交付）。
 * <p><b>状态：</b>仅建包占位，尚无实现 —— 进度以 docs/development/feature-catalog.md 为准。
 */
package cn.sec.hn.openbackupsaas.tenant;
