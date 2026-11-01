/**
 * 路由配置 —— 平台业务员控制台（R-20261102-03）。
 *
 * 身份平面：PLATFORM（与 platform-admin 同一平面、不同控制台）。
 * 核心业务是**实名人工审核**（guest.verification.read/review）：
 * 队列、决定、留痕历史——ADR-019 §2.5 的人工审核闭环的前端工作台。
 *
 * 分组：
 * · 工作台：运营概览（聚合真实接口数据）
 * · 实名审核：审核队列（真实接口：队列 + 决定 + 历史）
 * · 客服：工单（骨架，后端接口未开放）
 * · 业务审计：业务操作留痕（骨架）
 * · OTA：商业授权（骨架）
 *
 * access 只用于菜单可见性，**不等于授权**——服务端才是唯一授权关口。
 * 骨架页不渲染假数据（前端规范 §7）：只有列定义与真实空态。
 */
export default [
  {
    path: '/user',
    layout: false,
    routes: [
      {
        path: '/user/login',
        name: 'login',
        component: './user/login',
      },
      {
        path: '/user/password',
        layout: false,
        component: './user/password',
      },
      {
        path: '/user',
        redirect: '/user/login',
      },
    ],
  },
  {
    path: '/',
    redirect: '/workbench',
  },
  {
    path: '/workbench',
    name: 'workbench',
    icon: 'dashboard',
    component: './Workbench',
  },
  {
    path: '/verification',
    name: 'verification',
    icon: 'audit',
    routes: [
      {
        path: '/verification/queue',
        name: 'queue',
        icon: 'unorderedList',
        access: 'canReadVerifications',
        component: './verification/Queue',
      },
    ],
  },
  {
    path: '/customer',
    name: 'customer',
    icon: 'customerService',
    routes: [
      {
        path: '/customer/tickets',
        name: 'tickets',
        icon: 'comment',
        component: './customer/Tickets',
      },
    ],
  },
  {
    path: '/audit',
    name: 'audit',
    icon: 'fileSearch',
    routes: [
      {
        path: '/audit/operations',
        name: 'operations',
        icon: 'history',
        component: './audit/Operations',
      },
    ],
  },
  {
    path: '/ota',
    name: 'ota',
    icon: 'cloudSync',
    routes: [
      {
        path: '/ota/commercial',
        name: 'commercial',
        icon: 'api',
        component: './ota/Commercial',
      },
    ],
  },
  {
    component: './exception/404',
    path: '/*',
  },
];
