/**
 * 路由配置 —— 企业商旅控制台（R-20261102-06，纯骨架）。
 *
 * **DEC-17 OPEN**：企业对公账户未定案（需先定「企业 ≠ 租户」），
 * 没有身份平面、没有后端接口。本工程是**界面骨架**：
 * 把将来的功能分区立起来（差旅政策/员工出行/账单与对账），
 * 每页只有列定义与真实空态，不渲染假数据，也不提供假登录。
 *
 * 身份归属候选（定案后二选一，见 ADR-017 §2.3 与 ADR-019 §2.9）：
 * · 企业员工经 CORP_SSO 进入 GUEST 平面（GuestProvider 已预留该值）；
 * · 独立的 CORP 平面（新会话表与令牌域）。
 */
export default [
  {
    path: '/user/login',
    name: 'login',
    layout: false,
    component: './user/login',
  },
  {
    path: '/',
    redirect: '/home',
  },
  {
    path: '/home',
    name: 'home',
    icon: 'dashboard',
    component: './Home',
  },
  {
    path: '/policy',
    name: 'policy',
    icon: 'audit',
    component: './Policy',
  },
  {
    path: '/travel',
    name: 'travel',
    icon: 'car',
    component: './Travel',
  },
  {
    path: '/billing',
    name: 'billing',
    icon: 'accountBook',
    component: './Billing',
  },
  {
    component: './exception/404',
    path: '/*',
  },
];
