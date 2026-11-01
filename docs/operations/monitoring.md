# 可观测性运维手册（S2-06）

> 版本：1.0；建立日期：2026-10-31（AEST）；最近更新：2026-10-31（R-20261031-24）。
> 适用：PMS 后端（OpenBackupSaaS）。前端为纯静态资源，可观测性由宿主机 nginx 访问日志承担，不在本文件范围。

PMS 的可观测性分三层，互不替代：

| 层 | 技术 | 作用 | 默认状态 |
| --- | --- | --- | --- |
| **指标 Metrics** | Micrometer + Prometheus registry | 业务与系统计数/计量，供趋势与告警 | **开启**（/actuator/prometheus） |
| **链路 Tracing** | Micrometer Tracing + OpenTelemetry 桥接 | 请求级因果链（HTTP/DB 自动埋点） | **关闭**（采样率 0，不外发） |
| **日志 Logging** | SLF4J + Logback + MDC | 单请求细节、审计、排障 | 开启（requestId 入 MDC） |

---

## 1. 日志关联（MDC）

每个请求由 `RequestIdFilter`（`shared/web`，最高优先级，先于安全链）分配一个 `requestId`：

- 客户端传入合规的 `X-Request-Id`（8–64 位安全字符）则沿用，否则重新生成 UUID；
- 写入 MDC 键 `requestId`，并在响应头 `X-Request-Id` 回传；
- 请求结束清除 MDC。

**排障用法**：告警/用户报告里带 `requestId`，直接 `grep` 后端日志即可拉出该请求在 Web / Service / DB / 消息各层的全部行。

**链路联动**：开启 Tracing（见 §4）后，Spring Boot 自动把 `traceId` / `spanId` 也注入 MDC；若需在日志格式里看到它们，在 `logback-spring.xml` 的 pattern 追加 `%X{traceId:-}-%X{spanId:-}` 即可。日志关联是**已具备**能力，本文件不重复造。

---

## 2. 指标目录（前缀 `pms.`）

所有业务指标经唯一出口 `PmsMetrics`（`shared/observability`，受 `@Component` 管理），指标名与标签键只有一份定义。
**硬约束：标签值只能是有限集合**（命令名、枚举名小写、渠道小写编码），绝不接受订单号/房间号/租户 UUID/手机号等高基数字段——越界一律归为 `invalid`。这是采集端不爆的前提。

| 指标 | 类型 | 标签 | 记录点 | 含义 |
| --- | --- | --- | --- | --- |
| `pms.reservation.command` | Counter | `command`, `outcome` | `ReservationCommandService` | 订单写命令结果；`outcome` ∈ {success, conflict, error} |
| `pms.occupancy.conflict` | Counter | `kind` | `OccupancyService` | 占用冲突（EXCLUDE 约束兜底），`kind` ∈ {reservation, block} |
| `pms.reservation.expired` | Counter | `reason` (=hold_timeout) | `ReservationExpiryService` | 锁房超时承接释放的订单数（带增量） |
| `pms.external_task.outcome` | Counter | `channel`, `outcome` | `ExternalTaskExecutor` | 外部任务处置结果；`channel` 归一为小写编码，`outcome` ∈ {success, retry, conflict, manual, dead, skipped}。**FAILED 归 conflict**（渠道明确拒绝是业务结论，不是故障） |
| `pms.inbox.outcome` | Counter | `outcome` | `InboxProcessor` | Inbox 消费结论，∈ {processed, ignored, skipped_stale, parked_gap}；重复投递记独立取值 `duplicate` |
| `pms.security.auth` | Counter | `event`, 可选 `reason` | `AuthenticationService` | 安全事件，`event` ∈ {login_success, login_failure, logout, password_changed, password_change_failure}；失败带 `reason` ∈ {tenant_unavailable, unknown_user, user_disabled, bad_password, bad_current_password} |
| `pms.outbox.pending` | Gauge | — | `OutboxBacklogMonitor` | 未发布事件条数（含退避中） |
| `pms.outbox.oldest_event_age_seconds` | Gauge | — | `OutboxBacklogMonitor` | 最老未发布事件等待秒数；0 表示无积压 |
| `pms.outbox.backlog_breach` | Counter | — | `OutboxBacklogMonitor` | 积压越过阈值的刷新次数（rate 即持续时长） |

> 系统级指标（HTTP、`jvm_*`、GC、线程池、数据源连接池）由 actuator 自动注册，同样在 `/actuator/prometheus` 暴露，本表不重复列。

---

## 3. 告警目录（见 `deploy/monitoring/alert-rules.yml`）

严重度两档：**page**（叫醒，业务已受损或即将受损）、**ticket**（工单，需处理但可等）。

| 告警 | 严重度 | 触发 | 第一时间动作 |
| --- | --- | --- | --- |
| `OutboxBacklogStale` | page | 最老事件等待 > 600s 持续 2m | 查 Broker 连通性、队列绑定、`outbox_event.last_error` |
| `OutboxBreachPersisted` | ticket | backlog_breach 10m 速率 > 0 | 积压长期未恢复，排查发布失败根因 |
| `ExternalTaskDead` | ticket | 15m 内出现 `outcome=dead` | 按 channel 标签定位渠道，检查映射与授权（需人工处置） |
| `InboxVersionGap` | page | 15m 内出现 `parked_gap` 持续 5m | 事件总线缺口，用 `InboxIntegrityService` 重放补齐 |
| `LoginFailureSpike` | ticket | `login_failure` 5m 速率 > 10/s | 排查爆破或下游身份源故障 |
| `BackendDown` | page | `up{job=pms-backend} == 0` 持续 1m | 实例进程/网络，看 `deploy/app.sh status` 与日志 |
| `JvmOldGenPressure` | ticket | Old Gen > 90% 持续 10m | 内存泄漏或 `-Xmx` 偏小 |

**告警送达**：本文件与 `alert-rules.yml` 只定义"评什么"。真实接收人/路由（钉钉/邮件/电话）在 Alertmanager 配置，需填入凭据——属 S2-07 预发宿主机的部署动作，**不写进版本库**。一个只写日志、无人收的"告警"不能叫告警。

---

## 4. 启用链路追踪（默认关闭，需两步走）

Tracing 默认 `management.tracing.sampling.probability=0`：**不向外发任何 span**。原则同 Outbox——不能让默认配置把内部调用细节发到一个没被批准的外部端点。

需要观察时：

1. **打开采样**：`PMS_TRACING_SAMPLING` 调到目标概率（如 `0.1`）。
2. **指向采集器**：部署 `opentelemetry-exporter-otlp` 依赖（已在 pom 注释里说明），并设置 `management.otlp.tracing.endpoint` 到已批准的 Collector。

二者缺一不可：只开采样不加导出器，span 只在本进程做上下文传播、不离开；只加导出器不调采样，仍按 0 采样不导出。

---

## 5. 端点速查

| 端点 | 用途 | 暴露条件 |
| --- | --- | --- |
| `/actuator/health` | 存活/就绪（含 probes） | 已暴露，details 仅授权可见 |
| `/actuator/info` | 应用版本/环境 | 已暴露 |
| `/actuator/prometheus` | Prometheus 抓取文本 | 已暴露（需 `micrometer-registry-prometheus`） |
