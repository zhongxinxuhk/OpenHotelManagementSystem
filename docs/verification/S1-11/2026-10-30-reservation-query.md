# S1-11 交易 API、查询与权限闭环 —— 验证记录

- 日期：2026-10-30（AEST）
- 轮次：`R-20261030-13`
- 环境：macOS 本地（JDK 21 / PostgreSQL 17）+ GitHub Actions `ubuntu-24.04`
- 结论：**通过**。本地 `clean verify` 154 测试 / 0 失败；真实 CI [run 41020142020](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41020142020) 9 步全绿（`tests=154 skipped=0`）。

---

## 1. 交付内容

### 1.1 查询接口

| 接口 | 说明 |
| --- | --- |
| `GET /api/v1/hotels/{hotelId}/reservations` | 分页列表。过滤：`status`（可重复）、`sourceType`、`no`（订单号前缀）、`roomCode`、`stayFrom`/`stayTo`（区间交集）、`page`/`size` |
| `GET /api/v1/hotels/{hotelId}/reservations/{reservationId}` | 详情。含每行分到的房号与占用状态、整单跨度、`allowedActions`、`version` |

两条接口都要求 `reservation:read`，并先经 `HotelAccess`（门店不属于本租户 → 404，不在数据范围 → 403）。

### 1.2 设计取舍（写清楚，避免以后被"优化"掉）

| 取舍 | 理由 |
| --- | --- |
| 查询与命令分成两个 Controller / Service / Mapper | 命令侧每条 SQL 带 `FOR UPDATE` 与幂等记录，查询侧要的恰好相反（不占锁、`readOnly`）。混在一起早晚有人在列表查询里复用带锁语句，然后在并发下抱怨"翻页卡住下单" |
| 排序固定 `created_at DESC, id DESC`，不接受客户端指定 | 只按 `created_at` 排序时，同一毫秒创建的两单会在翻页时重复或漏掉；不接受排序参数也就没有排序注入面 |
| count 与列表共用同一段 `<sql id="listFilter">` | 条件各写一份，迟早出现"第 2 页有数据但 total=1"的自相矛盾分页 |
| 入住区间用 `daterange &&` 交集，不是"某天落在区间内" | 查 6/2—6/5，必须命中 6/1—6/4 与 6/3—6/6 的单；按单点判断会漏 |
| 订单号只做前缀匹配（`LIKE x || '%'`）并转义 `% _ \` | 中间匹配用不上索引等于全表扫；不转义的话用户输一个 `%` 就把条件去掉了（有用例覆盖） |
| `allowedActions` 由状态机守卫算出 | 规则只有一份：守卫改了，前端的按钮提示自动跟着改，不会出现"按钮能点但接口 409" |
| 详情不下发 `tenantId` / 占用 id / 操作 id | 脱敏的正确做法是不下发不需要的数据，而不是下发后遮起来 |

### 1.3 错误契约（三分类冻结）

所有 `ProblemDetail` 现在都带 `outcome`：

| outcome | 状态码 | 客户端该怎么做 |
| --- | --- | --- |
| `REJECTED` | 400 / 401 / 403 / 404 | 改输入或换账号；不要重试同样内容 |
| `CONFLICT` | 409 | 提示用户处理（改日期、刷新后重试）；不要自动重试 |
| `UNKNOWN` | 500 / 超时 | **结果不确定**：必须先查原交易结果（`GET /api/v1/operations/*`），不能显示"失败"让用户重新下单 |

新增两个 400 处理器：参数类型不匹配（`?status=FOO`、非法 UUID/日期）与缺少必填参数，都会带 `requestId` 与 `errors[{field,message}]`，与校验失败同形态。

> 覆写的是 `ResponseEntityExceptionHandler#handleTypeMismatch(TypeMismatchException, …)`。
> 写成 `handleMethodArgumentTypeMismatch` 编译期会因 `@Override` 报错，但**如果当时没写 `@Override`**，
> 就会得到一个永不生效的"处理器"——参数错误仍返回 Spring 的英文原文且不带 requestId。

### 1.4 OpenAPI v1 冻结

- 基线：`docs/api/openapi-v1.json`（18 个 path / 22 个 schema）
- 测试：`OpenApiContractTest` —— 启动上下文、请求 `/v3/api-docs`，与基线比对**结构**：
  HTTP 方法集合、每操作状态码集合、参数（位置+名字+是否必填）、schema 字段集合与必填项。
- **不比对** description / summary / example：它们不影响调用方代码，一旦纳入比对，改个错别字就要动基线，最后所有人都会习惯性"重新生成基线"，契约就名存实亡。

## 2. 门禁命令与实际输出

| # | 命令 | 结果 |
| --- | --- | --- |
| 1 | `./mvnw -o -q -DskipTests compile` | 退出码 0 |
| 2 | `PMS_IT_DB=true ./mvnw -B -ntp clean verify` | `Tests run: 154, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS** |
| 3 | `./mvnw -B -ntp test`（无库） | `Tests run: 154, Failures: 0, Errors: 0, Skipped: 129` → BUILD SUCCESS（129 个需库用例真跳过） |
| 4 | CI `backend` @ `972c988` | **success**，9 步全绿；日志 `Tests run: 154, Failures: 0, Errors: 0, Skipped: 0`、断言输出 `tests=154 skipped=0 failures=0 errors=0` |
| 5 | OpenAPI 导出 | `/v3/api-docs` → 200，18 path、22 schema，已格式化落地为基线 |

本轮新增 22 个测试（132 → 154）：查询集成 11、HTTP 9、契约 2。

## 3. 失败、歧义与处置（不掩盖）

### 3.1 我曾以为 record 访问器不被 MyBatis 识别——错了

写 `ReservationQuery` 时凭印象补了 10 个 `getXxx()` 转发方法，并在注释里断言"MyBatis 的 Reflector 只认 getXxx，record 的 `tenantId()` 会抛 `There is no getter`"。

按项目惯例做了一次反向验证：**把所有 getter 删掉，11 个查询用例全部通过**。说明 MyBatis 3.5.19 已支持 record 访问器。已删除这 10 个无用方法，并把注释改成实测结论。

记入此处的价值不是"我改对了一个文件"，而是：**凭印象写下的注释会变成后人不敢动的"事实"**。凡是"因为框架不支持"而加的绕路代码，都该先反向验证一次。

### 3.2 契约测试第一次跑就抓到一个真缺陷

`ReservationDetailView.LineView` 与 `ReservationView.LineView` 同名。springdoc 用**简单类名**作为 `components.schemas` 的 key，于是两份 schema 被合并成一份：

- `ReservationView.LineView`（创建响应）字段：`lineId / requestedRoomTypeId / assignedRoomId / stayRange / occupancyId`
- 详情实际返回：`lineId / lineNo / requestedRoomTypeId / status / plannedRange / assignedRoomId / assignedRoomCode / occupancyStatus`

OpenAPI 里详情的 `lines` 指向了创建响应的 schema。**运行时 JSON 是对的，文档是错的**——按文档生成的前端类型会拿到不存在的 `occupancyId`，同时看不到 `assignedRoomCode`。这类错误任何运行时测试都发现不了。

处置：重命名 `DetailLineView`，重新导出基线。修复后 schema 正确分离（`DetailLineView` 8 字段 / `LineView` 5 字段）。

### 3.3 契约测试本身做过反向验证

为确认它不是"永远绿"的摆设：临时把基线里列表接口的 `size` 参数删掉，测试立即失败并精确指出差异：

```
接口契约与冻结基线不一致（共 1 处）：
  - /operations/GET /api/v1/hotels/{hotelId}/reservations/parameters/：
    基线=[path:hotelId:true, query:no:false, query:page:false, query:roomCode:false,
          query:sourceType:false, query:status:false, query:stayFrom:false, query:stayTo:false]
    运行时=[… 多出 query:size:false …]
```

恢复基线后转绿。

### 3.4 只改契约基线不会触发 CI（已修）

`backend.yml` 的 `paths` 原本只有 `openbackupsaas/**` 与 workflow 自身。契约基线在 `docs/api/openapi-v1.json`，意味着"悄悄放宽契约"可以绕过 CI。已把该文件加入 `push` 与 `pull_request` 的 paths。

## 4. 未做（明确边界）

1. **可售查询 `/availability`（D-01）** 属 S1-12，需要投影与重建，不在本轮。
2. **前端页面**：首页四个指标位与待办表格现在有接口可接了，但页面接入属 S3-03（房态日历与预订工作台）。
3. **自定义排序、字段选择、导出**：有意不做。排序开放给客户端会引入不稳定的分页与一个排序注入面；导出属 S3-09。
4. **游标分页**：单店日增几十单，offset 足够；等 S6-01 容量测试给出真实翻页深度再评估。
5. **创建响应里的 `occupancyId`**：`ReservationView.LineView` 仍回显占用 id，属 S1-08 的实现细节外泄。移除它是破坏性变更，需要与前端接入一起决定，记入待办而非本轮顺手改。
