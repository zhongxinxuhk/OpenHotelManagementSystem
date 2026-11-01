# 前端多端工程（frontend/）

> 依据：[ADR-017 多端形态与身份平面](../docs/adr/ADR-017-multi-surface-and-identity-planes.md)。
> 每个端是**独立工程、独立依赖、独立构建产物、独立发布**，不共享 `node_modules`。

## 1. 为什么拆

单一前端工程承载"内部员工 + 外部住客 + 平台运营"三类用户会出现三个问题：

1. **产物变大且互相牵连**：任何一次构建都会把三套界面的代码打进同一个 bundle，
   改客户端的样式要重新发版商家端；
2. **攻击面混在一起**：一个 XSS 或依赖投毒同时影响内外部用户；
3. **发布节奏冲突**：客户端要频繁小步上线，平台端要严格冻结窗口。

拆开后各端可以按自己的节奏走，出问题也止于一端。

## 2. 目录与端

| 目录 | 端 | 身份平面 | 使用者 | 技术栈 | 状态 |
| --- | --- | --- | --- | --- | --- |
| `merchant-web/` | Web 商家端 | TENANT | 前台、客房、店长、财务 | Ant Design Pro 6（Umi Max + React 19） | **已可运行** |
| `merchant-h5/` | H5 商家端 | TENANT | 员工（移动：查房态、办入住、接通知） | Vite + React + antd-mobile | 待初始化（H5 暂缓，需求方 2026-11-02） |
| `guest/` | Web 客户端 | GUEST | 住客 | Ant Design Pro 6（需求方 2026-11-02 定栈，推翻原 Vite 方案） | **已可运行**（R-20261102-05：注册/登录/邮箱绑定真实接口 + 骨架空态页） |
| `guest-h5/` | H5 客户端 + 小程序 | GUEST | 住客（移动） | Vite + React + antd-mobile（预案） | **暂缓**（需求方指示：只建目录放规划 README） |
| `platform-ops/` | 平台业务管理端 | PLATFORM | 客服、售后、运营 | Ant Design Pro 6 | **已可运行**（R-20261102-03：实名审核工作台真实接口 + 骨架空态页） |
| `platform-admin/` | 平台管理员端 | PLATFORM | 技术、组织架构、存储 | Ant Design Pro 6 | **已可运行**（R-20261102-02：登录/策略/渠道真实接口 + 骨架空态页） |
| `corp-portal/` | 企业商旅控制台 | 待定（DEC-17） | 企业对公账户 | Ant Design Pro 6 | **已可运行**（R-20261102-06 纯骨架：无登录、四页空态，冒烟 7/7） |

**为什么只有 5 个工程却有 6 个端**：H5 客户端与 Web 客户端是同一套业务流程（看房型 → 下单 → 查订单），
差别只在版式与触屏交互。拆成两个工程等于把下单流程维护两遍，
而它们又必须保持行为一致——这是最容易长期漂移的那种重复。
若将来真的需要独立发布（例如 H5 走微信内、Web 走独立域名），把 `guest/` 复制成两个目录即可，
不会因为现在合在一起而变难。

## 3. 共享什么、不共享什么

**共享（通过文件，不通过 npm 包）**

- `docs/api/openapi-v1.json`：唯一的接口契约；
- `scripts/gen-pms-types.mjs`：从契约生成 TypeScript 类型（各端各自生成到自己的 `src/services/pms/types.ts`）；
- 错误三分类约定（`services/pms/errors.ts` 的心智模型）：`REJECTED` / `CONFLICT` / `UNKNOWN`。

**不共享**

- 不建 npm workspace。首期各端独立 `node_modules`：workspace 会把构建耦合提前引进来
  （改一个端触发全量重装），而这个项目当前是单人主导，耦合成本大于收益。
- 不共享组件库。商家端的 ProTable/ProForm 与客户端的商品卡片没有共同抽象，
  强行抽一层只会得到一个谁都不好用的 `packages/ui`。

## 4. 本地启动

五个 Web 控制台均已可运行（2026-11-02 起）。后端默认端口 18080（联调惯例；
`PMS_API_TARGET` 指向它），dev 端口各端固定互不冲突：

| 工程 | dev 端口 | 启动 |
| --- | --- | --- |
| merchant-web | 8000 | `PORT=8000 PMS_API_TARGET=http://127.0.0.1:18080 npm run start:no-mock` |
| platform-admin | 8001 | 同上（PORT=8001） |
| platform-ops | 8002 | 同上（PORT=8002） |
| guest | 8003 | 同上（PORT=8003） |
| corp-portal | 8004 | 静态骨架，无需后端（`PORT=8004 npm run start:no-mock`） |

首次进入各工程先 `npm ci`（同一份 package-lock 派生，缓存命中很快）。
门禁三件套（每端同一条链）：`npm run tsc` → `npm run biome:lint` → `npm run build`。
CI（`.github/workflows/frontend.yml`）以五工程矩阵逐端执行同一链。

> **TeleAgent 等 Electron 宿主注意**：宿主注入的 `APP_ROOT` 会与 Umi 的
> `getCwd()` 撞名导致 `max setup` 解析失败——跑 umi 命令须 `env -u APP_ROOT`；
> 普通 Terminal 与 CI 不受影响（backend-setup 已知坑 22）。dev 端口用 `PORT`
> 环境变量（`--port` 参数不生效）。

**五控制台计划（需求方 2026-11-02 指示）已全部落地**：①platform-admin（R-02）
②platform-ops（R-03）③merchant-web 扩展（R-04）④guest Web（R-05）
⑤corp-portal（R-06 纯骨架）。H5 与小程序暂缓（`merchant-h5/`、`guest-h5/`
只建目录放规划 README）。

**骨架先行的边界**：各端细化功能仍受对应 DEC 约束（DEC-01/02 平台细化、
DEC-17 企业对公、J-01 验证码登录）；骨架页只含列定义与真实空态，
不渲染假数据——需求方显式指示创建骨架，不等于越过未定案的业务决策。

## 5. 新增一个端时必须过的检查

1. **一个产物只服务一条身份平面**。`platform-*` 的代码不得出现在 `merchant-*` 的产物里，
   反过来也一样——这条靠"物理分开的工程"保证，不靠代码评审。
2. **类型从契约生成**，不手写接口类型。手写的那份一定会和 `openapi-v1.json` 漂移，
   而漂移的表现是运行时字段为 `undefined`，不是编译错误。
3. **错误处理沿用三分类**。特别是 `UNKNOWN`：客户端（住客）看到"结果不确定"时
   要引导去查订单，不能显示"下单失败"让人再点一次——那就是重复预订。
4. **文案遵守禁令**：不写产品自述、欢迎辞、营销数字、装饰性英文标语
   （见 `docs/development/frontend-antd-pro.md` §7）。客户端尤其容易写跑，因为它是面向消费者的。

## 6. 与旧路径的关系

`frontend-admin-pro/` 已迁移为 `frontend/merchant-web/`（R-20261101-29）。
CI（`frontend.yml` / `beta.yml` / `release.yml`）与生成脚本已同步。
`docs/verification/**` 下的历史记录保留原路径：那是当时的证据，不做追溯改写。

> AI生成
