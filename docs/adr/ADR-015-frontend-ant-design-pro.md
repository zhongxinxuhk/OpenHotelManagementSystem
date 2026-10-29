# ADR-015：管理端前端基线改为 Ant Design Pro（深度使用）

> 日期：2026-10-29（AEST）；任务：S0-03 受控变更 / 新追加前置子任务 S3-01a。
>
> 状态：**已选定并落地工程骨架**；依赖安装受本机环境阻塞（见 §6），S3-01a 记为 BLOCKED。
>
> 取代：[ADR-011](./ADR-011-technology-baseline.md) §2「管理前端」行与 §3「新增 Element Plus」行。
>
> 关联：[前端工程规范](../development/frontend-antd-pro.md)、[功能清单 O 域](../development/feature-catalog.md)、[进度台账](../development/progress.md)。

## 1. 背景

ADR-011（2026-10-19）在现有 Vue 3 初始化工程上确定了「Vue 3.5 + TypeScript + Vite + Element Plus 2.14.7」作为管理端基线，只完成了一个 Element Plus 按钮的最小构建探针，尚无业务页面。

项目负责人于 2026-10-29 决定管理端改用 [Ant Design Pro](https://preview.pro.ant.design/welcome/) 并深度使用其 Pro 组件体系。原始动因：

1. PMS 后台是**高密度表单与表格型工作台**（订单列表、房态日历、价格日历、账务流水、报表），Ant Design Pro 的 `ProTable` / `ProForm` / `ProDescriptions` / `EditableProTable` 直接覆盖这些形态，减少自研列表与表单基础设施的工作量。
2. 该模板自带完整后台工程要素（ProLayout 布局、路由与菜单、权限 `access`、统一请求与错误处理、国际化、mock、构建与 Biome 校验），与 S3-01「后台前端基础与契约接入」的验收项高度重合。
3. 图纸化的工作区划分（集团 / 门店 / 前台 / 运维）可以用 ProLayout 路由分组直接表达，避免维护三套重复应用。

## 2. 决策

| 项 | 决定 |
| --- | --- |
| 框架 | Ant Design Pro 6.0.3 官方模板（与 `preview.pro.ant.design` 同源） |
| 构建 | Umi Max 4.7（`max dev` / `max build`），不叠加 Vite/Webpack 自定义配置 |
| UI 库 | antd 6.x + @ant-design/pro-components 3.x，**深度使用** Pro 系列（清单见规范 §5） |
| 运行时 | React 19.3、TypeScript 7.0、Node ≥ 22 |
| 图表 | @ant-design/plots 2.6（不引入第二套图表库） |
| 校验 | `npm run tsc` + `npm run biome:lint` + `npm run build` 三件套，接入每轮收口 |
| 目录 | 新增 `frontend-admin-pro/`；`frontend-engineering-vue/` 冻结停用，不参与构建 |
| 契约 | `max openapi` 生成 `services/` 类型（S3-01 接入），不手写重复 DTO |

「深度使用」的边界（写进规范，可检查）：

- 能用 Pro 组件表达的界面不得手写等价实现；例外必须在规范中登记理由。
- 列表一律 `ProTable` + 服务端分页；表单一律 `ProForm`/`ModalForm`/`DrawerForm`；详情一律 `ProDescriptions`。
- 页面容器统一 `PageContainer`；金额、状态、房态日历抽成受控业务组件，不散落在页面里。

## 3. 被比较的方案

| 方案 | 优势 | 未选原因 |
| --- | --- | --- |
| Vue 3 + Element Plus（原 ADR-011） | 现有工程已可构建、团队熟悉、包体较小 | 列表/表单/详情需自行搭建；房态、价格日历等复杂表格交互重复造轮子；权限、国际化、请求层需从零补 |
| React + Ant Design 5 + Vite（自建 SPA） | 依赖少、可控性最高 | 等于把 Ant Design Pro 已完成的基础设施重做一遍，与「小步快跑、先把交易内核做对」的资源分配冲突 |
| Next.js + shadcn/ui | 现代、样式自由 | 面向内容与 SEO 场景；PMS 后台不需要 SSR，且缺乏开箱的后台骨架与 Pro 组件 |
| **Ant Design Pro 6（选定）** | 后台骨架完整、Pro 组件覆盖高密度表格表单、权限/i18n/请求/构建齐备 | 模板自带 demo 与营销式欢迎页需清理（O-11）；Umi 生态绑定；antd 6 / TS 7 较新，需实测 |

## 4. 影响

| 对象 | 影响 | 处理 |
| --- | --- | --- |
| ADR-011 | 管理前端与 Element Plus 行失效 | 已标注为被本 ADR 取代；后端技术栈（Boot 4.1.1 / MyBatis / Flyway / PostgreSQL）不变 |
| 架构文档 §3.2 | 「商家/管理端 Vue + Element Plus」候选失效 | 增补实施基线说明，指向本 ADR |
| 开发规划 §2 基线表、§5.1 目录 | 前端路径与依赖描述变化 | 更新为 `frontend-admin-pro/`，并说明 Vue 目录已冻结 |
| S3-01 | 「按 S0-03 决策落实框架」的具体化 | 骨架提前由 **S3-01a** 落地；业务页面仍须等 S2-08（硬依赖不放宽） |
| 验证体系 | 前端门禁命令从 `vue-tsc` 改为 `npm run tsc` + `biome lint` | 已写入 [development/README.md](../development/README.md) §3.1 |
| 人员技能 | React/Pro 组件体系取代 Vue | 单人项目，风险可接受；复杂交互集中在受控业务组件内 |
| 依赖体积/许可 | Umi、antd、pro-components 均为 MIT | 与 ADR-011 §5「发布门禁需附依赖清单与许可证」一致，S6-05 复核 |

业务层不变项（不受本 ADR 影响）：库存权威、占用排斥、幂等、事务边界、租户隔离、门禁 G0—G6。

## 5. 工程落地内容（2026-10-29）

1. 从官方仓库取 Ant Design Pro 6.0.3 模板铺到 `frontend-admin-pro/`，移除 `.git`、`.github`、`.husky`、`docs`、`e2e`、`cloudflare-worker`、仓库 README/LICENSE 等仓库元文件，保留 `config/`、`src/`、`mock/`、`tests/`、`types/`、`scripts/`。
2. `package.json`：`name` 改为 `hotel-pms-admin`，`description` 改为中文业务描述，移除 `prepare`（husky 会写入仓库根 git hooks）、`deploy`（gh-pages）、`record`（录制脚本）三个与当前交付无关的脚本。
3. 保留 `mock/` 与 `MOCK=none` 两种启动方式；联调与验收固定用 `start:no-mock`。
4. 模板 demo 页面（含 `Welcome` 营销式文案）尚未清理，登记为 O-11，清理完成前 O-01 不置 `DONE`。

## 6. 验证证据

详见 [S3-01a 依赖安装记录](../verification/S3-01a/2026-10-30-dependency-install.md)。

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 依赖安装 | `npm ci`（Node 22.22.2） | **未通过**：本机 Agent 沙箱代理在安装高峰断开（`ECONNREFUSED broker.sock`，6 次尝试均同名错误）；registry 本身可达，需在宿主终端重跑 |
| 类型检查 | `npm run tsc` | 未执行（依赖未装完） |
| 静态检查 | `npm run biome:lint` | 未执行 |
| 生产构建 | `npm run build` | 未执行 |
| 冒烟 | `npm run start:no-mock` | 未执行 |

**未验证项不得写成已支持**：模板在 antd 6 / TS 7 下的编译与构建结论、页面渲染、房态日历等自研组件，均尚未取得证据。S3-01a 因此记为 `BLOCKED`（环境阻塞），不记为完成。

## 7. 风险与回退

| 风险 | 缓解 |
| --- | --- |
| 模板 demo 页面与营销文案混入业务交付 | O-11 强制清理清单；文案列入验收项（规范 §7） |
| Umi 生态绑定，未来迁出成本 | 业务逻辑收敛在 `services/pms` 与受控组件，页面层不直接依赖 Umi 特有 API |
| antd 6 / pro-components 3 / TS 7 较新，存在兼容缺陷 | 每轮 `tsc + build` 门禁；升级走独立提交并记录版本 |
| 依赖树体积大、安装慢 | 提交 `package-lock.json`，CI 用 `npm ci`；不使用 `latest` 浮动版本。**实测**：本机 Agent 沙箱代理无法承载约 1.5 GB 安装，须在宿主终端执行（见 §6） |
| Node 版本要求 ≥22 | `engines` + CI 固定 22 LTS，开发机已满足 |
| 与后端契约漂移 | S3-01 接入 `max openapi` 生成类型；接口变更先改契约 |

**回退路径**：本 ADR 只影响管理端。若模板不可用，可回退到「React + Ant Design 5 + Vite」自建 SPA（Pro 组件仍可单独引入），业务契约与权限模型不变；`frontend-engineering-vue/` 保持冻结未删除，必要时可回到 ADR-011 的 Vue 基线。回退不涉及数据库与后端，不产生数据风险。

## 8. 后续

1. **S3-01a 收口**：在宿主终端完成 `npm ci` 后补跑 `tsc` / `biome:lint` / `build`，把输出摘要写入 [S3-01a 记录](../verification/S3-01a/2026-10-30-dependency-install.md) §5，再置 `DONE`。
2. **O-11**：按规范 §10 六步清理 demo、建四工作区骨架与「今日工作台」首页（无欢迎语）。
3. **S3-01**：在 S2-08 完成后正式开工，接入 OpenAPI 类型生成、登录态与错误码映射、工作区菜单与权限点。
4. 任何偏离「深度使用 Pro 组件」的实现，须在本 ADR 或规范 §5 补充说明，不得默认为自由选择。
