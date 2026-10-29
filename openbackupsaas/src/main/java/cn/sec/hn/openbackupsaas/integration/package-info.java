/**
 * integration 模块：Inbox、Outbox、持久化任务与差异工单。
 *
 * <p><b>边界：</b>数据库事实提交后才触发外部调用；消费者不得绕过领域命令直接改状态
 *
 * <p><b>依据：</b>S2-01—S2-05；模块依赖规则见 docs/architecture/module-boundaries.md（S0-09 交付）。
 * <p><b>状态：</b>仅建包占位，尚无实现 —— 进度以 docs/development/feature-catalog.md 为准。
 */
package cn.sec.hn.openbackupsaas.integration;
