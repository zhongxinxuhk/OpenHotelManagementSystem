/**
 * payment 模块：支付、退款、对账与账务联动。
 *
 * <p><b>边界：</b>外部资金动作不能与本地事务假装原子化；按查询、幂等与补偿协调（BL-08）
 *
 * <p><b>依据：</b>S3-08、S4-04—S4-06、ADR-014；模块依赖规则见 docs/architecture/module-boundaries.md（S0-09 交付）。
 * <p><b>状态：</b>仅建包占位，尚无实现 —— 进度以 docs/development/feature-catalog.md 为准。
 */
package cn.sec.hn.openbackupsaas.payment;
