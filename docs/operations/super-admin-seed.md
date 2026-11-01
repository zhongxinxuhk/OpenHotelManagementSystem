# 超级管理员初始化与登录

> 建立日期：2026-10-31（AEST）；对应轮次：R-20261031-25。
>
> 适用范围：本地开发、演示与预发环境的**初始管理员**。生产环境见 §4。

## 1. 一句话

```bash
cd openbackupsaas && ./scripts/seed-super-admin.sh
```

然后用 `pms-admin` / `admin` / `admin` 登录。

## 2. 脚本做了什么

`openbackupsaas/scripts/seed-super-admin.sql`（幂等，可重复执行）：

| 步骤 | 内容 | 说明 |
| --- | --- | --- |
| 1 | 建租户 `pms-admin` | 登录时用租户编码定位账号所在租户 |
| 2 | 建门店 `hq` | 时区 `Australia/Sydney`；超管需要至少一间门店，否则前端无可操作对象 |
| 3 | 建角色 `SUPER_ADMIN` | **从 `permission` 表全量授予权限点**，不手工维护清单 |
| 4 | 建账号 `admin` | 口令哈希用项目自身的 `BCryptPasswordEncoder` 生成 |
| 5 | 角色绑定 + 门店范围 | 授予该租户下全部门店 |

第 3 步为什么用 `SELECT code FROM permission` 而不是列清单：权限字典由各次迁移逐步扩充
（V2/V3/V6/V7/V11/V12），手工维护的清单必然漂移——新增权限点时忘了加，
超级管理员就会莫名其妙少一块能力。从字典取意味着"字典里有的就是有的"，永远同步。

## 3. 为什么不在 Flyway 迁移链里

V1—V12 描述的是**表结构**，随应用启动自动执行，会跑到每一个环境。
本脚本创建的是一个**知道弱口令的账号**：

- 放进迁移链 = 每个环境（含预发、生产）自动多出一个 `admin/admin`；
- 而口令强度校验只发生在**改密**接口（`AuthenticationService#requireAcceptablePassword`，下限 12 位），
  **登录接口并不校验长度** —— 也就是说这条弱口令在任何环境都能直接登录；
- 弱口令进版本库 = 凭据入库，等同于泄漏。

所以它留在 `scripts/` 下作为**显式动作**：谁执行、什么时候执行，由人决定。

## 4. 生产环境怎么办

**不要用默认口令 `admin`。** 生产环境有两个选项：

1. 用 `--random` 生成一次性随机口令（脚本会把口令打印一次，只显示这一次，也无法从库里读回）：

   ```bash
   ./scripts/seed-super-admin.sh --random
   ```

   随机口令是 20 位字母数字，满足改密接口的 12 位下限，可直接使用。

2. 若组织要求超管口令由本人设置：先用 `--random` 建账号并登录，再通过
   `POST /api/v1/auth/password` 改密（该接口会回收该账号的**其他**会话，保留当前会话）。

无论哪种方式，**首次登录后都应确认**：该账号的门店范围（`user_hotel_scope`）是否与实际职责相符——
超管默认拿到租户下全部门店，这在生产里可能过大。

## 5. 自检：哈希为什么可信

脚本在落库**之前**先做一次自检：用项目自身的 `BCryptPasswordEncoder` 验证 SQL 里的哈希确实对应明文。

```
== 步骤 1/2：BCrypt 哈希自检
MATCH
   OK：SQL 中的哈希可由项目 BCryptPasswordEncoder 验证（明文 admin）
```

这一步不是多余动作。哈希若前缀或算法不对（例如从别处抄来一个 `$2y$` 而项目用 `$2a$`），
登录会**永远**返回 401，而错误文案是统一的"凭据无效或账号不可用"，排查起来毫无线索。
在落库之前就知道"这个哈希是坏的"，比登不上再查要快得多。

## 6. 常见问题

### 登录返回 401

按顺序排查：

1. 租户编码是否为 `pms-admin`（**区分大小写**，后端会转小写，但填错就是另一个租户）；
2. 脚本是否执行过——可以查库确认：

   ```sql
   SELECT t.code, u.username, u.status
   FROM tenant t JOIN app_user u ON u.tenant_id = t.id
   WHERE t.code = 'pms-admin';
   ```

3. 账号是否被停用（`app_user.status = 'DISABLED'`）。

### 执行了脚本但登录仍失败

**先确认有没有跑过集成测试。** `PostgresIntegrationTest` 每个测试方法前会执行 `DatabaseCleaner`
全库清理——**dev 库里刚种入的 admin 账号会连同业务数据一起被清空**。
重跑一次 `seed-super-admin.sql` 即可（幂等）。

因此：**测试库与联调库必须分开**，否则会反复踩这一脚。

### 脚本用哪个账号连接

`seed-super-admin.sh` 默认连 `PMS_DB_HOST`/`PMS_DB_PORT`/`PMS_DB_NAME`/`PMS_DB_USER`，
默认 `127.0.0.1:55432/pms/pms_app`，口令取 `PMS_DB_PASSWORD`（默认为空，本机 trust 认证）。
这些值与 `deploy/compose/.env` 同源——脚本会自动读取该 env 文件。

**IPv6 回环自动回退**：某些机器上 Docker 发布的端口在 IPv4 回环不通（`127.0.0.1` 超时），
只有 `::1` 能连上。脚本检测到这种情况会自动改用 `::1` 并打印一行提示，
不必手工改配置。

---

## 员工账号怎么开通（S3-02，R-20261101-27）

除了上面这个超级管理员种子，**日常员工账号一律走界面**：`员工与权限 → 员工管理 → 新建员工`。

### 初始口令只出现一次

开通时服务端生成一个 16 位随机口令（字符集刻意去掉了 `0 O 1 l I`，方便口头转达），

- 只在**创建响应**里出现一次；
- 库里只有 BCrypt 哈希，**服务端事后也取不回**；
- 界面会弹窗提示"关闭后无法再次查看"，并给复制按钮。

**丢了只能重设口令，没有"再查一次"这种操作。** 这一点是设计如此，不是限制。

### 首次登录必须改口令

新账号带 `must_change_password = true`（迁移 V13）。在这个状态下：

- 只放行 `/api/v1/auth/me`、`/api/v1/auth/password`、`/api/v1/auth/logout`；
- 其它任何 `/api` 请求返回 **403 + `code = PASSWORD_CHANGE_REQUIRED`**；
- 前端据此把登录后的人直接送到 `/user/password`，改完立即进入工作台。

改密成功时标记自动清除，**同一个会话下一次请求就放行**（标记是每请求从库里读的，
不需要重新登录，也没有"改完还得等"的说法）。改完之后，管理员手上那份初始口令**登不进来**。

### 为什么状态码是 403 而不是 401

这个人是**已认证**的。用 401 会让前端清掉令牌跳登录页，用户在登录页再登一次还是被挡，
形成"登录成功但什么都点不开"的循环。前端靠 `code` 而不是状态码来区分
"待改密"与"没权限"——两者都是 403。

### 排障：某人说"登录了但页面全在报错"

先查这一列：

```sql
SELECT username, status, must_change_password, last_login_at
FROM app_user
WHERE must_change_password = true;
```

有行就说明这个人还没改初始口令——让他打开 `/user/password` 改一次即可，
不要去查权限配置。

### 运维口径：不要手工往 `app_user` 里插账号

手工插入要自己算 BCrypt 哈希、自己配角色与门店范围、自己置 `must_change_password`，
漏一项就是一个"能登录但什么都做不了"或"永久弱口令"的账号。
需要批量开通时用接口 `POST /api/v1/identity/users`，它是幂等检查 + 事务的。

---

## 平台管理员账号（R-20261101-31，ADR-019）

平台雇员（审实名、管核验渠道）与商家员工是**两套完全隔离的身份**：
不同的表（`platform_user` vs `app_user`）、不同的会话（`platform_session` vs `user_session`）、
不同的令牌派生（哈希带域前缀）、不同的权限字典（`platform_action` vs 租户 `permission`）。
一个令牌只能在一个平面被认出来——这是结构保证，不靠约定。

### 初始化

```bash
cd openbackupsaas
./scripts/seed-platform-admin.sh            # 明文口令 platform-admin，仅本机联调用
./scripts/seed-platform-admin.sh --random   # 生产：一次性随机口令，只在输出里出现一次
./scripts/seed-platform-admin.sh --verify-only
```

脚本会先用项目自身的 `BCryptPasswordEncoder` 生成哈希并**自检**，然后落库。
种子账号 `must_change_password = true`：**首次登录只能改口令**，改完才能用其它功能。

### 登录与首改

```bash
curl -X POST /api/v1/platform/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"platform-admin","password":"..."}'
```

待改密状态下，除 `/platform/auth/me`、`/platform/auth/password`、`/platform/auth/logout`
外全部 403 + `code=PASSWORD_CHANGE_REQUIRED`（与员工侧同一套约定，同一套前端处理）。
改密成功后**其它设备的会话会被撤销**（发起改密的那个保留）。

### 实名人工审核怎么用

1. 住客提交实名后状态为 `PENDING`，出现在
   `GET /api/v1/platform/guest-verifications`；
2. 队列里**只有掩码**（`110105********002X`）与姓名掩码——审核判断"证件是否合规、
   姓名是否一致"不需要完整号码；完整号码的取证是独立受审计流程，不在队列里；
3. `POST .../{accountId}/decision`，body 带 `decision`（`VERIFIED`/`REJECTED`）与
   **必填的 `reason`**。留痕写入 `guest_verification_review`，含审核者与当时的掩码快照；
4. 审核通过后，住客**下一次请求**即解除限制（状态每请求现读，无需重新登录）。

### 权威核验渠道（当前未对接任何厂商）

`GET/POST/PUT /api/v1/platform/verification-channels` 管配置；
熔断中的渠道可 `POST .../{code}/reset-circuit` 手动复位（必填原因）。

三点必须知道：

1. **配置存在 ≠ 已接通**。响应里的 `implementationAvailable` 才是真相：
   人工渠道恒为 true（人工审核就是它的实现）；权威渠道在本轮**没有任何实现**，
   配了也是存着，直到对接具体厂商。
2. **人工渠道不能停用**（服务层 409）。它是权威接口熔断时唯一的兜底——
   停掉它，权威接口抖动的瞬间所有新住客注册全部卡死。
3. **人工审核不依赖渠道状态**。权威接口熔断时人工照常——这是结构保证
   （决定服务不读渠道表），不是靠 if 跳过。渠道表出问题也不影响审核。

### 排障：平台账号登录后全 403

先查：

```sql
SELECT username, status, must_change_password FROM platform_user;
```

`must_change_password = true` 就让他先改口令（`POST /api/v1/platform/auth/password`）。
不是权限配置问题——平台动作全量授予时是 4 个（见 `platform_action`）。
