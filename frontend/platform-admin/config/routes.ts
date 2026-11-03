/**
 * 路由配置 —— 平台管理员控制台（R-20261102-02）。
 *
 * 身份平面：PLATFORM（ADR-019 §2.3 的 `/api/v1/platform/**` 安全链）。
 * 登录走平台账号（`platform_user`），授权是**动作码**（`platform_action` 字典），
 * 不是租户权限点——两套字典刻意不共用（ADR-019 §2.4）。
 *
 * 分组：
 * · 概览：系统概览（聚合真实接口数据）
 * · 住客身份：登录方式策略、核验渠道（真实接口）
 * · 系统管理：趋势、平台用户与权限、系统级审计（骨架，后端接口未开放）
 * · OTA：三方接口适配（骨架）
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
        /*
         * 强制改密：平台账号也有 must_change_password（V15），
         * 种子账号首登必须先改密。`layout: false` 的理由同 merchant-web：
         * 主框架会拉业务接口，待改密状态下必然报错。
         */
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
    redirect: '/overview',
  },
  {
    path: '/overview',
    name: 'overview',
    icon: 'dashboard',
    component: './Overview',
  },
  {
    path: '/guest',
    name: 'guest',
    icon: 'team',
    routes: [
      {
        path: '/guest/login-policy',
        name: 'loginPolicy',
        icon: 'key',
        access: 'canReadAuthPolicy',
        component: './guest/LoginPolicy',
      },
      {
        path: '/guest/channels',
        name: 'channels',
        icon: 'apartment',
        access: 'canReadChannels',
        component: './guest/Channels',
      },
    ],
  },
  {
    path: '/system',
    name: 'system',
    icon: 'setting',
    routes: [
      {
        path: '/system/trends',
        name: 'trends',
        icon: 'lineChart',
        component: './system/Trends',
      },
      {
        path: '/system/users',
        name: 'users',
        icon: 'user',
        component: './system/Users',
      },
      {
        path: '/system/audit',
        name: 'audit',
        icon: 'fileSearch',
        component: './system/Audit',
      },
    ],
  },
  {
    path: '/ota',
    name: 'ota',
    icon: 'cloudSync',
    routes: [
      {
        path: '/ota/adapters',
        name: 'adapters',
        icon: 'api',
        component: './ota/Adapters',
      },
    ],
  },
  {
    component: './exception/404',
    path: '/*',
  },
];
