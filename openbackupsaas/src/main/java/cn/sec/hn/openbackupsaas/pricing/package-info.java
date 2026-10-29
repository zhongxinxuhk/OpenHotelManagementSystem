/**
 * pricing 模块：日价格、价规、报价快照。
 *
 * <p><b>边界：</b>下单价必须由服务端计算；成交快照不被后续改价覆盖
 *
 * <p><b>依据：</b>S3-07；模块依赖规则见 docs/architecture/module-boundaries.md（S0-09 交付）。
 * <p><b>状态：</b>仅建包占位，尚无实现 —— 进度以 docs/development/feature-catalog.md 为准。
 */
package cn.sec.hn.openbackupsaas.pricing;
