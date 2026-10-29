/**
 * reservation 模块：预订、订单状态机、占用记录。
 *
 * <p><b>边界：</b>占用变更的唯一业务入口；前台、支付、渠道都必须经此调用，不直接改占用表
 *
 * <p><b>依据：</b>S1-07—S1-11、ADR-003、ADR-004；模块依赖规则见 docs/architecture/module-boundaries.md（S0-09 交付）。
 * <p><b>状态：</b>仅建包占位，尚无实现 —— 进度以 docs/development/feature-catalog.md 为准。
 */
package cn.sec.hn.openbackupsaas.reservation;
