/**
 * reservation 模块：订单、订单行与房间占用事实。
 *
 * <p><b>边界：</b>占用只有一个事实源——预订与封房/维修写同一张 `room_occupancy`；
 * 排斥由数据库 EXCLUDE 约束保证，应用层"先查后插"不算正确性
 *
 * <p><b>依据：</b>S1-05、S1-08、ADR-002；模块依赖规则见 docs/architecture/module-boundaries.md（S0-09 交付）。
 * <p><b>状态：</b>S1-05 已落 `reservation` / `reservation_line` / `room_occupancy`
 * （统一 A/B）与数据库级排斥约束，并提供统一锁序的写入服务；
 * 订单命令（锁房/确认/取消）、超时释放、库存流水与幂等记录尚未实现 —— 进度以
 * docs/development/feature-catalog.md 为准。
 */
package cn.sec.hn.openbackupsaas.reservation;
