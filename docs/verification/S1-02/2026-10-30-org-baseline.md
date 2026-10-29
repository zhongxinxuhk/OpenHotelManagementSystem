# S1-02 组织基线（Flyway + 租户/集团/门店）验证记录

> 日期：2026-10-30（AEST）；任务：S1-02；结论：**DONE（真实 PostgreSQL 17 验证通过）**
>
> 环境：macOS / Temurin 21.0.13 / Spring Boot 4.1.1 / MyBatis 3.5.19 / PostgreSQL 17（本机一次性集群，端口 55432）/ Flyway 12.4.0

## 1. 数据库准备（可复现）

```bash
openbackupsaas/scripts/dev-db.sh start
```

- 独立数据目录 `/tmp/pms-pgdata`、端口 55432、仅本机 trust 认证；不触碰系统既有集群。
- 角色与授权来自 `scripts/db-bootstrap.sql`（幂等，CI 复用同一文件）：
  - `pms_migrator`：表/索引所有者，DDL；
  - `pms_app`：仅 DML；两者均 `NOSUPERUSER`。

## 2. 测试结果

```bash
PMS_IT_DB=true ./mvnw -B -ntp test
```

```text
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- OrganizationIntegrationTest
[INFO] Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- RequestIdFilterTest
[INFO] Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- GlobalExceptionHandlerTest
[INFO] Tests run: 2,  Failures: 0, Errors: 0, Skipped: 0 -- OpenbackupsaasApplicationTests
[INFO] Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

不带 `PMS_IT_DB=true` 时：12 个集成用例**跳过**（不是通过），其余 8 个通过 → `BUILD SUCCESS`。

### 2.1 用例与验收项对应

| 验收项（规划 S1-02） | 用例 | 结果 |
| --- | --- | --- |
| 空库初始化通过 | `flywayBaselineIsAppliedOnEmptyDatabase` | V1 应用成功，`tenant/hotel_group/hotel` 均存在 |
| 非法跨租户关联失败 | `crossTenantGroupRejectedByService` / `...ByDatabaseConstraint` | 服务层拒绝 + 复合外键 `fk_hotel_group_same_tenant` 拒绝 |
| 不能删除仍有业务引用的组织记录 | `cannotDeleteTenantStillReferencedByHotel` | 删除被外键拒绝 |
| 组织状态影响写入授权 | `suspendedTenantRejectsNewWrites` | 停用租户新增门店 409 冲突 |
| 同店编码唯一、跨租户可重名 | `duplicateTenantCodeRejectedByService` / `...ByDatabaseConstraint` / `sameHotelCodeAllowedInDifferentTenants` | 均符合预期 |
| 迁移/应用账号分权 | `appAccountCannotRunDdl` | `pms_app` 建表被拒绝 |
| 越权读不可行 | `crossTenantReadReturnsEmpty` | 带错误 tenantId 查询返回空，不是"查到再判断" |
| 编码/时区校验前置 | `invalidFormatRejectedBeforeHittingDatabase` | 非法编码、空名称、非法时区均 400 级拒绝 |

## 3. 数据库层证据

```text
version | description  | success
   1    | org baseline | t

 Schema |         Name          |    Owner
 public | flyway_schema_history | pms_migrator
 public | hotel                 | pms_migrator
 public | hotel_group           | pms_migrator
 public | tenant                | pms_migrator

hotel 约束：ck_hotel_code_fmt / ck_hotel_name_blank / ck_hotel_status / ck_hotel_timezone
           fk_hotel_group_same_tenant (f)  ← 复合外键，跨租户挂接在数据库层失败
           fk_hotel_tenant (f) / hotel_pkey (p) / uq_hotel_code / uq_hotel_id_tenant (u)

pms_app 权限：can_create=f（不能建表） can_insert=t is_super=f
```

## 4. 未验证项

| 项 | 原因 | 何时验证 |
| --- | --- | --- |
| V1 → V2 升级路径 | 目前只有一个迁移版本，升级语义要等 V2 出现 | S1-04/S1-05 引入新迁移时 |
| 并发下唯一键冲突时机 | 本轮未做并发测试 | S1-13（T01—T07） |
| CI 实际执行 | 未推送触发 | 下次推送 |
| 门店营业日字段 | S0-05（ADR-012）未定案，**故意不加** | S0-05 后追加迁移 |

## 5. 复现命令

```bash
cd openbackupsaas
./scripts/dev-db.sh start
PMS_IT_DB=true ./mvnw -B -ntp test        # 20 tests
PMS_IT_DB=true ./mvnw -B -ntp verify      # 含打包
./scripts/dev-db.sh stop                  # 需要时
```
