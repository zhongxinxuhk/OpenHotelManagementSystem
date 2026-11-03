/**
 * 状态标签（S3-01）。
 *
 * 规范 §5.1：四套色板（订单状态 / 占用状态 / 房务状态 / 账务状态）集中在这里维护，
 * 页面禁止自己写 `color="red"`。
 *
 * 集中维护的理由不是"好看"，而是**一致性**：同一个状态在不同页面显示成不同颜色，
 * 操作员会以为是两个状态；而散落的颜色迟早会漏改。
 */
import { Tag } from 'antd';
import React from 'react';

/** 订单状态色板。 */
const RESERVATION_STATUS_COLOR: Record<string, string> = {
  PENDING: 'gold',
  CONFIRMED: 'blue',
  CHECKED_IN: 'green',
  CHECKED_OUT: 'default',
  CANCELLED: 'red',
  EXPIRED: 'default',
  NO_SHOW: 'orange',
};

/** 订单状态中文名。 */
const RESERVATION_STATUS_TEXT: Record<string, string> = {
  PENDING: '待确认',
  CONFIRMED: '已确认',
  CHECKED_IN: '已入住',
  CHECKED_OUT: '已离店',
  CANCELLED: '已取消',
  EXPIRED: '已过期',
  NO_SHOW: 'No-show',
};

/** 房间运营状态色板。 */
const ROOM_STATUS_COLOR: Record<string, string> = {
  ACTIVE: 'green',
  INACTIVE: 'default',
};

const ROOM_STATUS_TEXT: Record<string, string> = {
  ACTIVE: '在售',
  INACTIVE: '停用',
};

/** 清洁状态色板。清洁与销售占用是两条独立的线，不可互相覆盖。 */
const HOUSEKEEPING_STATUS_COLOR: Record<string, string> = {
  CLEAN: 'green',
  DIRTY: 'orange',
  INSPECTED: 'blue',
};

const HOUSEKEEPING_STATUS_TEXT: Record<string, string> = {
  CLEAN: '已清洁',
  DIRTY: '待清洁',
  INSPECTED: '已检查',
};

/** 房型/门店等组织单元的启停状态。 */
const ORG_STATUS_COLOR: Record<string, string> = {
  ACTIVE: 'green',
  INACTIVE: 'default',
  SUSPENDED: 'orange',
  CLOSED: 'red',
};

const ORG_STATUS_TEXT: Record<string, string> = {
  ACTIVE: '启用',
  INACTIVE: '停用',
  SUSPENDED: '暂停',
  CLOSED: '关闭',
};

/** 对账差异严重度色板。 */
const SEVERITY_COLOR: Record<string, string> = {
  FACT: 'red',
  DERIVED: 'orange',
  BACKLOG: 'blue',
};

const SEVERITY_TEXT: Record<string, string> = {
  FACT: '事实类',
  DERIVED: '派生类',
  BACKLOG: '积压类',
};

/** 任务/死信状态色板。 */
const OPS_STATUS_COLOR: Record<string, string> = {
  DEAD: 'red',
  PENDING: 'gold',
  RUNNING: 'blue',
  SUCCEEDED: 'green',
  FAILED: 'red',
  PARKED: 'orange',
  MANUAL_REVIEW: 'purple',
};

const OPS_STATUS_TEXT: Record<string, string> = {
  DEAD: '已终止',
  PENDING: '待执行',
  RUNNING: '执行中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  PARKED: '已挂起',
  MANUAL_REVIEW: '待人工复核',
};

type StatusTagProps = {
  status?: string;
  /** 找不到映射时的兜底展示文本。 */
  fallback?: string;
};

/** 订单状态标签。 */
export const ReservationStatusTag: React.FC<StatusTagProps> = ({
  status,
  fallback,
}) => {
  if (!status) return <Tag>-</Tag>;
  return (
    <Tag
      color={RESERVATION_STATUS_COLOR[status]}
      data-testid="reservation-status"
    >
      {RESERVATION_STATUS_TEXT[status] ?? fallback ?? status}
    </Tag>
  );
};

/** 房间运营状态标签。 */
export const RoomStatusTag: React.FC<StatusTagProps> = ({ status }) => {
  if (!status) return <Tag>-</Tag>;
  return (
    <Tag color={ROOM_STATUS_COLOR[status]}>
      {ROOM_STATUS_TEXT[status] ?? status}
    </Tag>
  );
};

/** 清洁状态标签。 */
export const HousekeepingStatusTag: React.FC<StatusTagProps> = ({ status }) => {
  if (!status) return <Tag>-</Tag>;
  return (
    <Tag color={HOUSEKEEPING_STATUS_COLOR[status]}>
      {HOUSEKEEPING_STATUS_TEXT[status] ?? status}
    </Tag>
  );
};

/** 组织单元状态标签（房型、门店）。 */
export const OrgStatusTag: React.FC<StatusTagProps> = ({ status }) => {
  if (!status) return <Tag>-</Tag>;
  return (
    <Tag color={ORG_STATUS_COLOR[status]}>
      {ORG_STATUS_TEXT[status] ?? status}
    </Tag>
  );
};

/** 对账差异严重度标签。 */
export const SeverityTag: React.FC<StatusTagProps> = ({ status }) => {
  if (!status) return <Tag>-</Tag>;
  return (
    <Tag color={SEVERITY_COLOR[status]}>{SEVERITY_TEXT[status] ?? status}</Tag>
  );
};

/** 运维任务状态标签。 */
export const OpsStatusTag: React.FC<StatusTagProps> = ({ status }) => {
  if (!status) return <Tag>-</Tag>;
  return (
    <Tag color={OPS_STATUS_COLOR[status]}>
      {OPS_STATUS_TEXT[status] ?? status}
    </Tag>
  );
};

/** 订单来源标签。 */
export const SourceTypeTag: React.FC<StatusTagProps> = ({ status }) => {
  const text: Record<string, string> = {
    FRONTDESK: '前台',
    DIRECT: '直客',
    OTA: 'OTA',
    MINIPROGRAM: '小程序',
  };
  if (!status) return <Tag>-</Tag>;
  return <Tag>{text[status] ?? status}</Tag>;
};

export {
  HOUSEKEEPING_STATUS_COLOR,
  HOUSEKEEPING_STATUS_TEXT,
  OPS_STATUS_COLOR,
  OPS_STATUS_TEXT,
  ORG_STATUS_COLOR,
  ORG_STATUS_TEXT,
  RESERVATION_STATUS_COLOR,
  RESERVATION_STATUS_TEXT,
  ROOM_STATUS_COLOR,
  ROOM_STATUS_TEXT,
  SEVERITY_COLOR,
  SEVERITY_TEXT,
};
