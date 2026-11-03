/**
 * 路由配置 —— 唯一事实源（S3-01）。
 *
 * 分组 = 业务域，不再是模板的 demo 分组：
 * · 工作台：今日工作台
 * · 门店：订单、房型与房间、房态与可售
 * · 运维：运维处置
 *
 * 模板自带的 demo 路由（list / form / profile / account / result / table-list /
 * dashboard / chatbot / admin）在 O-11 中整体删除：它们展示的是假数据，
 * 与后端没有任何对应关系，留在菜单里只会让人以为系统里真有这些功能。
 *
 * access 只用于菜单可见性，**不等于授权**——服务端才是唯一授权关口。
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
         * 修改口令。
         *
         * 刻意 `layout: false`：主框架顶栏的门店选择器会拉业务接口，
         * 而待改密状态下这些接口一律被服务端拒绝——进了主框架只会先看到一串报错。
         * 不给 `name`：它是流程中的一个步骤，不该出现在菜单里。
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
    path: '/workspace',
    name: 'workspace',
    icon: 'dashboard',
    routes: [
      {
        path: '/workspace',
        redirect: '/workspace/today',
      },
      {
        path: '/workspace/today',
        name: 'today',
        icon: 'home',
        component: './Welcome',
      },
    ],
  },
  {
    path: '/hotel',
    name: 'hotel',
    icon: 'shop',
    routes: [
      {
        path: '/hotel',
        redirect: '/hotel/reservation',
      },
      {
        path: '/hotel/reservation',
        name: 'reservation',
        icon: 'profile',
        component: './reservation/list',
        access: 'canReadReservation',
      },
      {
        path: '/hotel/room',
        name: 'room',
        icon: 'appstore',
        component: './room',
        access: 'canReadRoom',
      },
      {
        path: '/hotel/inventory',
        name: 'inventory',
        icon: 'barChart',
        component: './inventory',
        access: 'canReadInventory',
      },
      {
        /*
         * 排房（R-20261102-04）：把已确认订单排到具体房间。
         * 后端 S3-04 未实现，骨架页只含列定义与真实空态。
         */
        path: '/hotel/assignment',
        name: 'assignment',
        icon: 'partition',
        component: './hotel/Assignment',
        access: 'canReadReservation',
      },
      {
        /*
         * 创建酒店/民宿（R-20261102-04）：表单骨架。
         * POST /hotels 未开放（A-01 扩展），提交不发请求、明确提示。
         */
        path: '/hotel/create',
        name: 'create',
        icon: 'plusCircle',
        component: './hotel/Create',
        access: 'canWriteOrg',
      },
    ],
  },
  {
    /*
     * OTA 对接（R-20261102-04）：骨架页。
     * L 域未实现（渠道授权未到位）。
     */
    path: '/ota',
    name: 'ota',
    icon: 'cloudSync',
    routes: [
      {
        path: '/ota',
        redirect: '/ota/integration',
      },
      {
        path: '/ota/integration',
        name: 'integration',
        icon: 'api',
        component: './ota/Integration',
      },
    ],
  },
  {
    path: '/identity',
    name: 'identity',
    icon: 'team',
    access: 'canReadIdentity',
    routes: [
      {
        path: '/identity',
        redirect: '/identity/user',
      },
      {
        path: '/identity/user',
        name: 'user',
        icon: 'user',
        component: './identity/user',
        access: 'canReadIdentity',
      },
      {
        path: '/identity/role',
        name: 'role',
        icon: 'safety',
        component: './identity/role',
        access: 'canReadIdentity',
      },
    ],
  },
  {
    path: '/ops',
    name: 'ops',
    icon: 'monitor',
    access: 'canReadOps',
    routes: [
      {
        path: '/ops',
        redirect: '/ops/queue',
      },
      {
        path: '/ops/queue',
        name: 'queue',
        icon: 'table',
        component: './ops',
      },
    ],
  },
  {
    path: '/exception',
    name: 'exception',
    icon: 'warning',
    routes: [
      {
        path: '/exception',
        redirect: '/exception/403',
      },
      {
        path: '/exception/403',
        name: '403',
        icon: 'stop',
        component: './exception/403',
      },
      {
        path: '/exception/404',
        name: '404',
        icon: 'warning',
        component: './exception/404',
      },
      {
        path: '/exception/500',
        name: '500',
        icon: 'bug',
        component: './exception/500',
      },
    ],
  },
  {
    path: '/',
    layout: false,
    redirect: '/workspace/today',
  },
  {
    component: './exception/404',
    path: '/*',
  },
];
