/**
 * command 模块：命令入口与幂等。
 *
 * <p><b>边界：</b>命令入口负责"同一键同一内容只产生一次效果"，不负责业务规则；
 * 业务规则在各领域服务内。幂等记录与业务结果必须**同事务**提交，
 * 否则会出现"业务成了但查不到结果"或"结果有了但业务没成"。
 *
 * <p><b>依据：</b>S1-07、ADR-004；模块依赖规则见 docs/architecture/module-boundaries.md（S0-09 交付）。
 * <p><b>状态：</b>S1-07 已落 `idempotency_record` 与命令入口组件；真实命令（锁房/确认/取消）在 S1-08/S1-09 接入。
 */
package cn.sec.hn.openbackupsaas.command;
