/**
 * room 模块：房型、实体房间、维修与清洁状态。
 *
 * <p><b>边界：</b>维修写入与预订共用同一互斥协议（ADR-002）；清洁状态不改变销售占用事实
 *
 * <p><b>依据：</b>S1-04、S1-05、S3-06；模块依赖规则见 docs/architecture/module-boundaries.md（S0-09 交付）。
 * <p><b>状态：</b>仅建包占位，尚无实现 —— 进度以 docs/development/feature-catalog.md 为准。
 */
package cn.sec.hn.openbackupsaas.room;
