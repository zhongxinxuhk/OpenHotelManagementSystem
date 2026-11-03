# H5 商家端（merchant-h5）

> 身份平面：**TENANT**（与 `merchant-web` 同一条平面、同一批接口）。
> 状态：**待初始化**（未安装依赖）。

## 定位

给酒店员工在手机上用的作业面。不是把 Web 商家端缩小，而是只做**站在走廊里、前台柜台上真的会掏手机做的事**：

| 场景 | 需要的能力 | 对应后端接口 |
| --- | --- | --- |
| 看今天还有多少房 | 可售查询 | `GET /api/v1/hotels/{hotelId}/availability` |
| 查今天的到店与待确认 | 订单列表（按日期筛） | `GET /api/v1/hotels/{hotelId}/reservations` |
| 到店办入住 / 离店 | 订单状态操作 | `POST /api/v1/hotels/{hotelId}/reservations/{id}/check-in` 等 |
| 房态随手改（维修、封锁） | 房间状态 | 房间状态接口（S3-06 归口） |

**不需要**在 H5 上做的：报表、导出、角色与权限配置、对账处置。这些是坐着做的事，
放到 Web 商家端。把 PC 后台整块搬到手机上是典型的"响应式了但没人用"。

## 技术栈

Vite + React + TypeScript + antd-mobile。**不用 Ant Design Pro**：
ProTable/ProForm 的密度与交互是为鼠标设计的，在 375px 宽下会变成一堆需要横向滚动的表格。

## 初始化（须在宿主终端）

本机 Agent 沙箱无法完成依赖安装（见 `docs/verification/S3-01a`），请在宿主终端执行：

```bash
npm create vite@latest frontend/merchant-h5 -- --template react-ts
cd frontend/merchant-h5
npm i antd-mobile
```

初始化后第一件事是把契约类型接进来：

```bash
node scripts/gen-pms-types.mjs   # 会在 repoRoot 下按脚本内路径输出
```

注意：`gen-pms-types.mjs` 当前只写到 `frontend/merchant-web/src/services/pms/types.ts`。
接入第二个端时应把它改成"输出到指定目录"的参数化脚本，而不是复制一份——复制的那份一定会漂移。

## 与 merchant-web 的关系

- **复用**：同一批后端接口、同一套错误三分类、同一份契约类型生成链路。
- **不复用**：组件、布局、状态管理组织方式（移动端的页面栈与 PC 的侧边栏导航没有共同抽象）。

## 开工前置

1. 产品先定"移动端承载哪几个动作"（上表是建议，不是已确认范围）；
2. `gen-pms-types.mjs` 参数化；
3. 登录/改密流程要能跑通（后端已支持；`/user/password` 的强制改密逻辑需要按移动端样式重做一遍）。
