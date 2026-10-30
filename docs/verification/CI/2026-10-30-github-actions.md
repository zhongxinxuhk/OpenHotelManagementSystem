# GitHub Actions 门禁完善与实跑 —— 验证记录

- 日期：2026-10-30（AEST）
- 轮次：`R-20261030-12`
- 环境：macOS 本地（JDK 21 / Node 22.22.2 / PostgreSQL 17）+ GitHub Actions `ubuntu-24.04`
- 仓库：`zhongxinxuhk/OpenHotelManagementSystem`（私有），分支 `feat/stage1-identity-room-occupancy`
- 结论：**通过**。两个门禁在真实 CI 上均为 `success`；期间 frontend 红过一次，根因已定位并修复（见 §3）。

---

## 1. 本轮的改动

| 文件 | 改了什么 | 为什么 |
| --- | --- | --- |
| `backend.yml` | `permissions: contents: read`、`workflow_dispatch`、PR 限 main、`clean verify`、**零跳过断言**、surefire 报告 `always` 上传、成功时上传可执行 jar | 让"绿"有含义：只跑单元测试、集成测试被静默跳过也能 BUILD SUCCESS，那种绿是假的 |
| `frontend.yml` | `permissions`、`workflow_dispatch`、PR 限 main、**产物可用性断言**（`dist/index.html` 必须存在、`dist/CNAME` 必须不存在）、上传 dist | 模板的 `public/CNAME` 会让任意静态托管把域名指向 Ant Design 官方预览站；这是部署事故而不是风格问题 |
| `qodana_code_quality.yml` | checkout ref 改 `github.event.pull_request.head.sha \|\| github.sha`、push 分支去掉硬编码 feature 分支、**token 缺失时跳过并 warning**、补 concurrency/timeout | 原写法在 push 事件下 `ref` 为空 → checkout 退化为默认分支，扫的不是这次提交；另外让代码门禁不依赖某个 secret 是否已填 |
| `release.yml`（新增） | `workflow_dispatch`：从指定 ref 产出 jar / dist / `SHA256SUMS` 两个 artifact | CD 的起点。**只打包不部署**——部署、健康检查与回滚属 S2-07 / S6-06，现在没有目标环境与凭据，不假装有 |

## 2. 实跑证据（真实 CI，不是本地模拟）

### 2.1 历史：`main` 上 backend 早已是绿的，frontend 是红的

| run | 提交 | 结论 | 失败步骤 |
| --- | --- | --- | --- |
| [40918697977](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/40918697977) | `main` @ 2026-10-29 | backend **success** | — |
| [40918697968](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/40918697968) | `main` @ 2026-10-29 | frontend **failure** | 第 5 步 `Type check` |

这直接修正了 S1-01 的一个旧口径："CI 实跑未观测"其实早就观测到了——backend 通了，frontend 挂在 `tsc`。

### 2.2 本轮：backend 全绿（含新增的零跳过断言）

[run 41017905046](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41017905046) @ `75d40e2`，`verify` job 74 秒（07:05:14Z → 07:06:28Z），**9 个步骤全部 success**：

```
 1 Set up job                                  success
 2 Initialize containers                       success
 3 Run actions/checkout@v4                     success
 4 Set up JDK 21                               success
 5 Bootstrap database roles and grants         success
 6 Verify (compile + unit + postgres integration tests)  success
 7 Assert no skipped tests                     success
 8 Upload surefire reports                     success
 9 Upload runnable jar                         success
```

关键日志：

```
[INFO] Tests run: 132, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
tests=132 skipped=0 failures=0 errors=0
```

即：PostgreSQL 17 service 起来了、`db-bootstrap.sql` 建好了两个非超级用户角色、集成测试真的连库跑了、且**没有任何用例被跳过**。

### 2.3 本轮：frontend 先红后绿

| run | 提交 | 结论 | 说明 |
| --- | --- | --- | --- |
| [41017905232](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41017905232) | `75d40e2` | **failure** | `Type check` 失败，40+ 个 `TS2305 Module '"@umijs/max"' has no exported member 'request'/'history'/'Link'…`（见 §3） |
| [41018431906](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41018431906) | `dd24b20` | **success** | `tsc` / `biome:lint` / `build` / 产物断言 / 上传 dist 全绿 |

**路径过滤也顺带验证了**：`dd24b20` 只改前端与 `frontend.yml`，该次 push 只触发 frontend，backend 未启动——说明 `paths` 过滤按预期工作，不会把无关改动算进后端门禁。

## 3. 失败与处置（不掩盖）

### 3.1 frontend 在 CI 上 `tsc` 失败、本地却通过

**现象**：CI 报 40+ 个 `Module '"@umijs/max"' has no exported member 'request' / 'history' / 'Link' / 'useIntl' / 'getIntl' / 'getAllLocales' / 'setLocale' / 'RequestConfig' / 'RunTimeLayoutConfig'`。

**根因**：`@umijs/max` 的插件导出**只存在于 umi 生成的 `src/.umi` 类型里**。本地因为反复跑过 `start` / `build`，`src/.umi` 一直在，所以 `tsc` 始终是绿的；CI 是全新检出，没有这份生成物，于是所有插件 API 都"不存在"。

**定位方法（不依赖 CI，本地就能证明）**：把 `src/.umi` 移走后跑 `npm run tsc`，**一字不差地复现了同一组报错**：

```
src/app.tsx(4,15): error TS2305: Module '"@umijs/max"' has no exported member 'RequestConfig'.
src/app.tsx(4,30): error TS2305: Module '"@umijs/max"' has no exported member 'RunTimeLayoutConfig'.
src/app.tsx(5,10): error TS2305: Module '"@umijs/max"' has no exported member 'history'.
...
```

**修法**：`package.json` 的 `tsc` 脚本改为 `max setup && tsc --noEmit`，把"先生成类型再生效"固化进脚本本身，而不是让每个执行环境各自记得先跑一步。本地干净状态复跑 `npm run tsc` 通过（自举成功）。

**为什么这个修法比"在 CI 里加一步"更好**：加在 CI 里只修了 CI，本地新人 clone 后仍会撞同样的墙；固化进脚本后，本地与 CI 走同一条命令，这类"只在干净环境出现"的问题会在本地先暴露。

### 3.2 断言脚本被本地残留报告污染

第一版 `Assert no skipped tests` 统计 `target/surefire-reports/TEST-*.xml` 全部文件。本地复跑时实测得到 `tests=134`，而本次 `verify` 只有 132——多出来的 2 个来自历史遗留的报告文件。

**处置**：`verify` 改为 `clean verify`。CI 上 `target` 本来就不存在，这一步是给"本地也能复现同一结论"兜底。改后本地断言输出 `files=17 tests=132 skipped=0`，与 Maven 输出一致。

### 3.3 构建产物里带着模板的 CNAME

`frontend-admin-pro/public/CNAME` 内容是 `preview.pro.ant.design`，会被原样复制到 `dist/`。任何静态托管（GitHub Pages / Vercel / OSS 静态站点）读到它都会把自定义域名指到 Ant Design 官方预览站。

**处置**：删除 `public/CNAME`（已备份到 `/tmp/pms-template-removed/`），并在 `frontend.yml` 里加断言：产物含 CNAME 直接失败。同时把站点标题从 `Ant Design Pro` 改「酒店 PMS」——注意 `<title>` 由 `config/config.ts` 的 `title` 决定，侧边栏品牌由 `config/defaultSettings.ts` 的 `title` 决定，**两处都要改**，只改一处会出现"侧边栏叫酒店 PMS、标签页叫 Ant Design Pro"的错位（本轮第一次构建就踩到了，`dist/index.html` 仍是旧标题）。

## 4. 未做（明确边界）

1. **没有部署**。`release.yml` 只产出可追溯的发布物（jar / dist / SHA256SUMS），不连任何环境。部署、密钥、健康检查、回滚属 S2-07 与 S6-06，且需要先定目标环境。
2. **没有开分支保护 / required checks**。这属于仓库设置而不是代码，且当前是单人直接向 feature 分支推送的工作方式；直接锁 `main` 会让现有流程中断。建议在 Settings → Branches 把 `backend` / `frontend` 设为 required，等有 PR 流程时再开。
3. **没有引入依赖漏洞扫描与 SBOM**（`npm audit` / OWASP dependency-check）。这类扫描需要告警基线与处置流程，属 S6-05 安全与供应链验收，现在加只会制造噪声。
4. **没有引入第三方 test-reporter action**。surefire 报告已作为 artifact 上传，需要时可下载查看；引入第三方 action 属于供应链面，留待 S6-05 评审。
5. **Qodana 依赖 `QODANA_TOKEN`**：未配置时 workflow 会跳过扫描并给出 warning，不会红。配置 secret 后自动开始扫描——这一点没有实测（当前仓库未配该 secret，也不该由开发侧猜测其内容）。


## 附：action 运行时升级（2026-10-30 晚，R-20261030-22 增补）

GitHub Actions 弃用 Node.js 20（强制切 Node 24）且 setup-java v4 停止更新，CI 页面出现两条告警。
统一升级并实测：

| action | v4 → v5 | 输入参数 |
| --- | --- | --- |
| `actions/checkout` | ✓ | 无变化 |
| `actions/setup-java` | ✓ | 无变化（distribution/java-version/cache） |
| `actions/setup-node` | ✓ | 无变化 |
| `actions/upload-artifact` | ✓ | 无变化（name/path/if-no-files-found/retention-days） |

`JetBrains/qodana-action@v2026.2` 是 JetBrains 自有 runner，不在弃用范围。
升级后 [backend run 41080433602](https://github.com/zhongxinxuhk/OpenHotelManagementSystem/actions/runs/41080433602) 与 frontend 双 **success**，
两条 Node 20 / setup-java v4 告警消失；日志中剩余 warning 为 Java 代码层既有技术债
（Spring AMQP `Confirm.isAck()/getReason()` 标记移除、`@Valid` 容器元素用法），与本次无关，
转后续轮次随依赖升级处理。
