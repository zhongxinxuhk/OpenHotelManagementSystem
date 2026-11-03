/**
 * 路由配置 —— C 端用户控制台 Web（R-20261102-05）。
 *
 * 身份平面：GUEST（ADR-019 §2.3 的 `/api/v1/guest/**` 安全链）。
 * 登录方式当前只有用户名+密码（EMAIL 验证码登录是下一轮——通道已接通）。
 *
 * 分组：
 * · 首页：房型浏览（公开面默认关闭，页面做优雅降级）
 * · 我的：账户信息（真实接口：显示名/实名状态/会话）
 * · 登录方式：凭证绑定管理（真实接口：用户名绑定/EMAIL 挑战-确认/解绑）
 * · 我的订单：骨架（后端 J-03 未做）
 *
 * C 端文案遵守前端规范 §7（尤其容易写跑：不写营销、不写欢迎辞、
 * 不写「我们致力于…」）。
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
        path: '/user/register',
        name: 'register',
        component: './user/register',
      },
      {
        /*
         * 修改口令（R-20261103-01 恢复）：已登录改密。`layout: false`——
         * 低频动作独立成页更聚焦；头像菜单「修改口令」指向这里。
         */
        path: '/user/password',
        layout: false,
        component: './user/password',
      },
      {
        /*
         * 忘记密码（R-20261103-01）：匿名找回，两步式（发码→重置）。
         * 不给 name：流程步骤不进菜单。
         */
        path: '/user/forgot',
        layout: false,
        component: './user/forgot',
      },
      {
        path: '/user',
        redirect: '/user/login',
      },
    ],
  },
  {
    path: '/',
    redirect: '/home',
  },
  {
    path: '/home',
    name: 'home',
    icon: 'home',
    component: './Home',
  },
  {
    path: '/orders',
    name: 'orders',
    icon: 'profile',
    component: './Orders',
  },
  {
    path: '/me',
    name: 'me',
    icon: 'user',
    component: './Me',
  },
  {
    path: '/me/bindings',
    name: 'bindings',
    icon: 'key',
    component: './Bindings',
  },
  {
    component: './exception/404',
    path: '/*',
  },
];
