/**
 * room 模块：房型、实体房间、维修与清洁状态。
 *
 * <p><b>边界：</b>维修写入与预订共用同一互斥协议（ADR-002）；清洁状态不改变销售占用事实
 *
 * <p><b>依据：</b>S1-04、S1-05、S3-06；模块依赖规则见 docs/architecture/module-boundaries.md（S0-09 交付）。
 * <p><b>状态：</b>S1-04 已实现房型与实体房的建立、读取、属性修改与启停（含"房型下仍有在用房间时拒绝停用"）；
 * 维修区间（`room_block`，与预订共用互斥协议）归 S1-05，清洁状态流转与房务任务归 S3-06 —— 进度以
 * docs/development/feature-catalog.md 为准。清洁状态目前**只读**：迁移里有列，但没有任何 UPDATE 路径。
 */
package cn.sec.hn.openbackupsaas.room;
