/**
 * frontdesk 模块：入住、离店、续住、换房。
 *
 * <p><b>边界：</b>通过领域服务编排订单与占用，必要时共享同一 PostgreSQL 事务；不删除历史占用与账务
 *
 * <p><b>依据：</b>S3-04、S3-05；模块依赖规则见 docs/architecture/module-boundaries.md（S0-09 交付）。
 * <p><b>状态：</b>仅建包占位，尚无实现 —— 进度以 docs/development/feature-catalog.md 为准。
 */
package cn.sec.hn.openbackupsaas.frontdesk;
