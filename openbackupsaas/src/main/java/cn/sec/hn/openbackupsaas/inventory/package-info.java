/**
 * inventory 模块：库存流水、房态投影、可售计算。
 *
 * <p><b>边界：</b>投影可重建，不反向决定已成交事实（ADR-001）；清空投影后必须能重建一致结果
 *
 * <p><b>依据：</b>S1-06、S1-12；模块依赖规则见 docs/architecture/module-boundaries.md（S0-09 交付）。
 * <p><b>状态：</b>仅建包占位，尚无实现 —— 进度以 docs/development/feature-catalog.md 为准。
 */
package cn.sec.hn.openbackupsaas.inventory;
