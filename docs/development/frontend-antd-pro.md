# 管理端前端工程规范（Ant Design Pro 深度使用）

> 版本：1.0；建立日期：2026-10-29（AEST）；决策依据：[ADR-015](../adr/ADR-015-frontend-ant-design-pro.md)。
>
> 适用范围：`frontend-admin-pro/`（集团端 / 门店端 / 前台端 / 运维端统一后台）。
> 不适用：住客 H5、微信小程序（技术栈另定）。
>
> 实时进度见 [progress.md](./progress.md)；功能项 O-01—O-11 见 [feature-catalog.md](./feature-catalog.md) §O。

## 1. 基线版本

| 项 | 版本 | 说明 |
| --- | --- | --- |
| 框架模板 | Ant Design Pro 6.0.3 | 官方 `preview.pro.ant.design` 同源模板 |
| 构建 | Umi Max 4.7（`max dev` / `max build`） | 不再引入 Vite/Webpack 自定义配置 |
| UI | antd 6.6+、@ant-design/pro-components 3.1+ | 深度使用 Pro 系列组件 |
| 运行时 | React 19.3、TypeScript 7.0 | `npm run tsc` 为类型门禁 |
| 图表 | @ant-design/plots 2.6 | 报表统一使用，不引入第二套图表库 |
| 代码规范 | Biome 2.x（`npm run biome:lint`） | 与 Umi lint 并存，禁止 ESLint 与 Biome 重复配置 |
| Node | ≥ 22（本机验证 22.22.2） | `engines` 已约束 |

启动与门禁命令：

```bash
npm start               # 带 mock 数据（UMI_ENV=dev max dev），端口 8000，仅用于纯 UI 走查
npm run start:no-mock   # MOCK=none 直连后端，联调与验收只用这个
npm run dev             # 同为 MOCK=none 的开发模式（模板已内置），与上者等价的简写
npm run tsc             # 类型检查（必跑）
npm run biome:lint      # 静态检查（必跑）
npm run build           # 生产构建（必跑）
```

**规则**：涉及真实业务数据的演示/验收一律 `start:no-mock`。mock 数据无法证明契约正确，接 mock 截图不能作为验收证据。

## 2. 目录约定（目标态）

模板自带的 demo 目录（`pages/Welcome*`、`pages/list`、`pages/form`、`pages/profile`、`pages/account`、`pages/result`、`pages/table-list`、`pages/dashboard`、`pages/chatbot`、`pages/Admin.tsx`）在 O-11 中整体清理；业务代码按下表归位。

```text
frontend-admin-pro/
  config/
    config.ts            # 构建、代理、布局插件（只做加法，保持模板结构）
    defaultSettings.ts   # ProLayout 主题：title/logo/colorPrimary/token
    routes.ts            # 唯一路由事实源，按工作区分组
    proxy.ts             # 本地代理到后端（仅 dev 生效）
  src/
    app.tsx              # getInitialState（当前用户/权限/门店）、layout 配置
    access.ts            # 前端可达性判断（不等于服务端授权）
    requestErrorConfig.ts# 统一响应/错误处理与错误码映射
    services/
      pms/               # 按后端模块分文件：identity.ts tenant.ts room.ts ...
      typings/           # 由 OpenAPI 生成的类型（S3-01 接入 max openapi）
    pages/
      workspace/         # 集团端（dashboard、门店管理、组织与权限、集团报表）
      hotel/             # 门店端（房态日历、订单、房价、账务、房务、报表）
      frontdesk/         # 前台端（在住、入住/离店、收银、交接班）
      ops/               # 运维端（任务、死信、对账差异、审计、导出）
      user/login/        # 登录
      exception/         # 403/404/500
    components/
      room-calendar/     # 房态日历（自研业务组件）
      money/             # 金额展示/输入（分转元、币种、右对齐）
      status-tag/        # 订单/占用/账务状态色板映射
      hotel-selector/    # 门店/集团切换
```

**约定**：
- 一个后端模块对应一个 `services/pms/*.ts`，函数名与 OpenAPI operationId 一致，不在页面里裸写 `request()`。
- 跨模块复用的展示逻辑进 `components/`；只服务单页面的组件留在该页面目录下。
- 不改动 `config/config.ts` 的既有结构；新增插件/别名需写在注释块内并说明理由。

## 3. 布局与路由

- 统一用 `ProLayout`（`layout: 'mix'`），分组 = 工作区：集团 / 门店 / 前台 / 运维。
- 每个页面外层必须包 `PageContainer`，`title` 用业务名词（「订单列表」「房态日历」），副标题只写筛选条件或时间范围，**不写功能说明段落**。
- 路由 `name` 走 `locales` 词条，不在路由里写中文常量。
- 页面级权限用 `access` 属性 + `access.ts`；菜单隐藏只是体验优化，服务端仍是唯一授权关口。
- 破坏性操作（取消订单、冲正、退款、停用房间）必须二次确认并显示对象标识（订单号 + 房号 + 日期区间），不使用「确定吗？」这类无信息确认。

## 4. 数据层与错误处理

```ts
// services/pms/reservation.ts —— 统一形态
export async function createReservation(
  body: API.CreateReservationRequest,
  idempotencyKey: string,
) {
  return request<API.Result<API.Reservation>>('/api/v1/reservations', {
    method: 'POST',
    data: body,
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}
```

强制规则：

1. **写操作必须带幂等键**：`Idempotency-Key` 由前端在进入表单时生成（`crypto.randomUUID()`），重试复用同一个键，重新填写才换新键。
2. **结果三分类必须在前端可区分**：`失败（4xx 业务拒绝）` / `冲突（占用冲突、版本冲突）` / `待核实（5xx、超时、未知结果）`。第三类不得显示「失败」，必须给「查询原交易结果」入口（对应 D-10）。
3. 统一错误提示走 `requestErrorConfig.ts`：`code → 文案` 映射表集中维护，不在页面里散落 `message.error`。
4. 金额一律以**分为单位的字符串/整数**传输，展示用 `money` 组件格式化；禁止前端参与算价与结算（服务端为准）。
5. 时间字段按门店时区展示；`dayjs` 统一从 `@umijs/max` 或 `dayjs` 单例引入，不引入 moment。

## 5. 「深度使用」清单（哪些场景必须用 Pro 组件）

| 场景 | 必须使用 | 禁止 |
| --- | --- | --- |
| 列表/检索 | `ProTable`（`request` 直连服务、列配置、工具条、批量操作） | 手写 `<table>` 或自行实现分页 |
| 新建/编辑 | `ProForm` / `ModalForm` / `DrawerForm` / `StepsForm` | 手写受控表单 + 自定义校验提示 |
| 详情 | `ProDescriptions`（列数自适应） | 手写 label/value 栅格 |
| 指标卡 | `StatisticCard` / `ProCard` 栅格 | 自绘卡片阴影样式 |
| 编辑型表格（价格日历批量改价） | `EditableProTable` | 单元格内手写 input 矩阵 |
| 流程/状态说明 | `Steps`、`ProList` | 自定义时间轴 HTML |
| 登录 | `LoginForm` + `ProFormText` | 自建登录表单 |
| 页面容器 | `PageContainer`（面包屑、页头操作区） | 裸 `<div>` 布局 |
| 状态标签 | `Tag` + 统一色板映射 | 每页自定义颜色 |

判断标准：**能由 pro-components 表达的界面，不允许手写等价物**。例外（如房态日历这种二维网格）需在本文件记录理由并抽成独立业务组件。

### 5.1 业务组件规范

- **房态日历**：`components/room-calendar`，数据源为后端 `E-05` 房态接口；单元格只呈现占用/维修/清洁三类事实与可售状态，拖拽结果必须以服务端返回为准，失败要回滚并提示冲突原因。
- **状态标签**：`components/status-tag` 集中维护「订单状态 / 占用状态 / 账务状态 / 支付状态」四套色板，禁止各页面自行 `color="red"`。
- **金额组件**：右对齐、千分位、币种符号，负数（冲正）显示为红色并保留符号。遵循国内财务习惯（红字为负/减少）。

## 6. 视觉与密度

- 以 antd 6 token 为准，`colorPrimary` 保持 `#1677ff`；不在页面里写裸色值。
- 信息密度优先：后台是工作台，不是展示页。表格行高紧凑、留白克制，不用大面积渐变、插画、装饰性插图。
- 辅助说明文本统一用灰色小字（`#8a8f99`，12–13px），只写真实约束（如「最晚 18:00 前免费取消」），不写鼓励性语气。
- 空态用 `Empty` 并给出**可执行**动作（「本日无可售房型，去设置房价」），不写空泛文案。

## 7. 文案约束（硬性）：页面不写自述式营销文本

**这条是长期约束，任何页面、任何角色、任何紧急情况下都不例外。**

不允许出现在业务页面（含登录后首页、空态、页脚、登录页侧栏）的内容：

| 禁止类型 | 反例 |
| --- | --- |
| 产品自述 / 价值主张 | 「智能高效的酒店管理平台，让经营更简单」 |
| 团队 / 理念 / 愿景 | 「我们致力于用技术重塑酒店运营」「以客为尊，精益求精」 |
| 欢迎辞与寒暄 | 「欢迎回来，今天也要加油哦！」「Hi，很高兴见到你」 |
| 功能介绍段落 | 「本页面可以帮助您查看所有订单，支持按日期筛选…」 |
| 营销数字 / 背书 | 「服务 500+ 门店」「行业领先」「已被众多酒店信赖」 |
| 拟人化助手话术 | 「我是您的智能管家，随时为您服务」 |
| 装饰性英文标语 | 「Make hotel management delightful」 |

允许且应当出现的内容：

- 业务名词与字段标签：`订单号`、`房型`、`入住日期`、`应收/实收/押金`。
- 真实约束与规则提示：`[2026-10-20, 2026-10-22)，共 2 晚`、`缺价日期不可售`、`该房型当日已关闭售卖`。
- 状态与结果：`已确认`、`占用冲突（房间 8302 已被订单 R20261029001 占用）`、`待核实：请查询原交易结果`。
- 可执行指引：`请先完成结算再办理离店`，附跳转按钮。

落地要求：

1. **首页不是欢迎页**。默认首页为「今日工作台」——今日到店/在住/待清洁/待处理任务的数字与清单，无欢迎语。模板 `Welcome` 页（含 logo、产品介绍、社区入口）在 O-11 中删除。
2. **页脚不写版权宣传**，需要时只保留版本号与服务状态（`v1.2.0 · 构建 2f8e1a9`）。
3. **登录页**左侧如需配图，只能是门店/房态的真实视觉素材或纯色留白，不得出现标语式文案。
4. 提交评审时，文案属于验收项：出现上述禁止内容即视为该页面未完成。
5. 国际化词条（`locales/zh-CN/`）中同样适用，不得以「这是 i18n 文案」为由豁免。

## 8. 权限与安全

- `getInitialState` 只缓存当前用户、权限点、可访问门店；**不缓存业务数据**。
- 门店切换必须清空列表缓存与查询条件并重新拉取，禁止沿用上一门店的分页/筛选。
- 前端不得持有任何服务商密钥、签名密钥；支付类跳转参数由服务端生成。
- 敏感字段（证件号、手机号）按服务端返回的脱敏值展示，前端不做「自己解密再显示」。
- 导出下载链接使用后端签发的一次性/短期地址，不在前端拼接路径。

## 9. 质量门禁（每轮必须执行并记录）

| 门禁 | 命令 | 通过标准 |
| --- | --- | --- |
| 类型 | `npm run tsc` | 0 error（warning 须在轮次记录里说明） |
| 静态检查 | `npm run biome:lint` | 0 error |
| 构建 | `npm run build` | 成功产出 `dist/`，无未解析依赖 |
| 冒烟 | `npm run start:no-mock` + 手动走查本轮页面 | 本轮涉及的页面可打开、关键操作有结果反馈 |

构建产物不得包含 `.env` 中的任何密钥；生产环境变量通过部署注入（N-02）。

## 10. 模板清理与迁移计划（O-11）

| 步骤 | 动作 | 验收 |
| --- | --- | --- |
| 1 | 删除 demo 页面与对应路由：`Welcome*`、`list`、`form`、`profile`、`account`、`result`、`table-list`、`dashboard`、`chatbot`、`Admin.tsx` | `npm run tsc && npm run build` 通过，无悬挂引用 |
| 2 | 清理 `mock/` 中与 demo 相关的接口，仅保留登录等最小桩 | 页面不再依赖 demo mock |
| 3 | 重建 `config/routes.ts`：登录 + 四个工作区骨架 + 403/404 | 菜单与权限一致 |
| 4 | 新增 `pages/workspace/dashboard`（今日工作台，无欢迎语）+ `pages/user/login`（复用 `LoginForm`） | 文案符合 §7 |
| 5 | 建立 `services/pms/` 与 OpenAPI 类型生成链路 | 类型来自契约，不手写重复定义 |
| 6 | 更新 `defaultSettings.ts`：`title`（酒店 PMS）、`logo`（自有素材） | 不残留 Ant Design Pro 品牌 |

完成以上 6 步后，O-01 与 O-11 方可置 `DONE`；此前只允许在独立分支/独立目录中试验。
