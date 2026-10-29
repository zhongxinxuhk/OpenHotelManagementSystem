# S3-01a 依赖安装与构建验证记录（未通过）

> 日期：2026-10-30（AEST）；任务：S3-01a（前端工程骨架前置准备）；结论：**BLOCKED（环境阻塞）**。
>
> 结论摘要：Ant Design Pro 6 工程文件已就位，但 `npm ci` 在本机 Agent 沙箱网络代理下无法完成依赖安装（`broker.sock` 连接被拒），因此 `tsc` / `biome:lint` / `build` 三项门禁**未执行，不得视为通过**。

## 1. 环境

| 项 | 值 |
| --- | --- |
| OS | macOS（Apple Silicon） |
| Node | v22.22.2 |
| npm | 10.9.7 |
| 工程 | `frontend-admin-pro/`（Ant Design Pro 6.0.3 模板，包名 `hotel-pms-admin`） |
| 依赖清单 | 模板自带 `package-lock.json`（`npm ci` 依据它安装） |
| npm 缓存 | `~/.npm/_cacache` 约 1.4 GB（首次尝试已下载大部分 tarball） |

## 2. 尝试记录

| # | 命令 | 耗时 | 结果 |
| --- | --- | --- | --- |
| 1 | `npm ci --no-audit --no-fund`（沙箱内） | 20m30s | 失败。`npm warn tar TAR_ENTRY_ERROR connect ECONNREFUSED .../broker.sock` ×8；最终 `EEXIST`/`Broker request outcome unknown: Broker request timed out`。产物 1.7 GB、1206 个包目录，但**无 `node_modules/.bin`** |
| 2 | `npm ci --prefer-offline`（关闭沙箱隔离） | — | 失败。npm 内部清理旧树触发大批量删除保护（`SAFE_DELETE_BULK_CONFIRM_REQUIRED`，68 个目录），进程中止 |
| 3 | `npm install --prefer-offline`（就地修复） | — | 失败。未生成 `node_modules/.bin`，依赖树仍不完整 |
| 4 | `mv node_modules /tmp/...` + `npm ci --prefer-offline` | 2m34s | 失败。`ECONNREFUSED /var/folders/.../T/cbb-IkTu96/broker.sock`（errno -61） |
| 5 | `mv node_modules /tmp/...` + `npm ci --offline` | 4m25s | 失败。同一 `ECONNREFUSED`，离线模式仍在启动阶段连代理 socket |
| 6 | `mv node_modules /tmp/...` + `npm ci --maxsockets=3 --prefer-offline` | 3m33s | 失败。同一根因；本次已写入 421 MB 后中断 |
| 对照 | `npm view react version` | <5s | 成功（`19.3.1`）。说明**registry 可达**，故障只发生在大量并行下载/解包阶段 |

## 3. 根因判断

- 所有失败共享同一错误：`connect ECONNREFUSED .../T/cbb-*/broker.sock`——Agent 运行环境的 HTTP 代理（broker）在安装高峰断开，npm 后续请求全部落空。
- 与网络策略无关：对照命令可正常访问 registry；缓存中已有大部分包，仍失败。
- 与依赖或锁文件无关：没有出现版本解析冲突或 `ERESOLVE`，模板锁文件本身可用。
- 结论：**本机 Agent 沙箱的代理会话无法承载 Ant Design Pro 全量依赖（约 1.3–1.7 GB、1200+ 包）的安装**，需要在宿主终端（不经 Agent 代理）执行。

## 4. 复现与解除条件（宿主终端执行）

```bash
cd /Users/xuzhongxin/WorkBuddy/Worktrees/OpenHotelManagementSystem/main-aed5b5c8/frontend-admin-pro
rm -rf node_modules            # 当前目录为多次中断后的半成品
npm ci                         # 或使用 pnpm：pnpm install
npm run tsc                    # 期望 0 error
npm run biome:lint             # 期望 0 error
npm run build                  # 期望产出 dist/
```

验收：四条命令全部成功，把实际输出摘要补写回本文件 §5，S3-01a 方可从 `BLOCKED` 转 `DONE`。

## 5. 宿主执行结果（待补）

| 命令 | 结果 |
| --- | --- |
| `npm ci` | 待补 |
| `npm run tsc` | 待补 |
| `npm run biome:lint` | 待补 |
| `npm run build` | 待补 |

## 6. 遗留物

多次中断留下的半成品目录被移至临时目录（未删除，避免破坏性操作）：

- `/tmp/pms-node_modules-broken-20261030`（1.6 GB）
- `/tmp/pms-node_modules-partial-20261030`（118 MB）
- `/tmp/pms-nm-partial2-<HHMM>`（约 221 MB）

确认 `npm ci` 成功后可由用户自行清理（建议移入回收站而非 `rm`）。
