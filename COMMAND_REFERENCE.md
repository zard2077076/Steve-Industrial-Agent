# 命令大全与使用说明

> 本表对应发布 JAR 的生产配置、世界标记和便携备份流程。最终客户端肉眼验收仍待完成，
> 因而这里不宣称 RC_READY。

> Phase IV 的 C-05 Crushing 当前只是未发布分支上的自动化验收能力，没有新增面向玩家
> 的施工命令，也没有把 Alpha pilot 目标扩展到砂或铜处理。开发者只应在仓库内运行
> `.\scripts\Test-C05Crushing.ps1`；不得把该脚本指向 PCL2 或正式存档。

所有命令要求 OP/作弊权限。施工命令只接受固定的资源 ID、数量和受信方向，不接受
自然语言、任意路径、任意坐标或代码。以下“会改世界”只表示通过全部门禁后，是否会
在已确认的测试区域内改变方块/资源。

## 版本和诊断

| 命令 | 用途 | 会改世界 | 使用说明 |
|---|---|---:|---|
| `/industrialagent version` | 显示 Agent、Git、MC、Forge、Create 版本 | 否 | 安装后首先运行；版本应为 `0.1.0-alpha.1`。 |
| `/industrialagent compatibility` | 对当前运行时做兼容矩阵检查 | 否 | 必须返回 `PASS` 后才继续。失败时按输出安装正确版本。 |
| `/industrialagent diagnostics` | 显示脱敏诊断概要 | 否 | 查看版本、网络/遥测关闭和诊断状态，不读取聊天、存档内容或路径。 |
| `/industrialagent export-diagnostics` | 生成小型脱敏 ZIP | 否 | 写入固定诊断目录，不自动上传；提交 Issue 前可人工检查。 |

## 首次设置与世界标记

| 命令 | 用途 | 会改世界 | 使用说明 |
|---|---|---:|---|
| `/industrialagent setup status` | 查看当前世界 marker 状态 | 否 | 首次进入世界和每次排障时运行；`marked=false` 时 pilot 不会放行。 |
| `/industrialagent setup show-config` | 显示脱敏配置摘要 | 否 | 检查 schema、模式、允许世界数量和安全门禁；路径只显示是否已配置。 |
| `/industrialagent setup mark-test-world` | 标记当前世界为可删除测试世界 | 仅写 marker | 只能由世界内管理员在主世界维度执行；它不授予备份、区域或施工权限。 |
| `/industrialagent setup validate` | 验证配置、路径、世界身份和 marker | 否 | 必须 PASS；失败码后按 `safeNextStep` 修复，不要修改 NBT。 |
| `/industrialagent setup unmark-test-world` | 移除当前世界 marker | 仅写 marker | 只有世界内管理员可执行，且任何活动 pilot 都会阻止；移除后旧备份证据立即过期。 |

## 便携备份

| 命令 | 用途 | 会改世界 | 使用说明 |
|---|---|---:|---|
| `/industrialagent backup status` | 查看是否存在当前世界的有效备份 | 否 | `NOT_READY` 时先执行 prepare，不能直接 start。 |
| `/industrialagent backup prepare` | 生成一次 pending 请求 | 否 | 管理员执行后保存并完全关闭游戏；不要在游戏运行时复制世界。 |
| `/industrialagent backup verify` | 重启后独立验真 | 否 | 重算 manifest、备份、恢复演练及世界/marker 绑定；必须 PASS。 |
| `/industrialagent backup list` | 列出当前世界最近的备份记录 | 否 | 最多显示 32 个记录 ID，不显示本地路径。 |
| `/industrialagent backup explain` | 显示完整备份顺序 | 否 | 不确定下一步时运行；流程固定为 prepare → 关游戏 → helper → 重开 → verify。 |

离线助手用法：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Complete-IndustrialAgentBackup.ps1 -RequestFile "<聊天中显示的 requestFile>"
```

助手只接受绑定当前测试实例/世界/backupRoot 的请求；检测到游戏仍在运行、锁、路径逃逸、
空间不足、正式根重叠或旧目标覆盖时会失败。它不上传数据。

## 区域选择

| 命令 | 用途 | 会改世界 | 使用说明 |
|---|---|---:|---|
| `/industrialagent pilot region here <X> <Z> <Y>` | 以玩家附近选择区域 | 否 | Alpha 建议 `32 32 20`；X/Z 还受配置 `maxRegionSize` 限制。 |
| `/industrialagent pilot region pos1` | 记录第一个角 | 否 | 双角选择时站到第一个角执行。 |
| `/industrialagent pilot region pos2` | 记录第二个角 | 否 | 必须与 pos1 同世界/维度并符合尺寸限制。 |
| `/industrialagent pilot region preview` | 扫描并显示区域边界/风险 | 否 | 等待完成消息，检查障碍、容器、BlockEntity 和未加载块。 |
| `/industrialagent pilot region confirm` | 确认当前预览 | 否 | 仅在预览无危险且区域确实可删除时执行；预览变化会使确认过期。 |
| `/industrialagent pilot region status` | 查看选择/确认状态 | 否 | 用于排查“区域未确认/已过期”。 |
| `/industrialagent pilot region clear` | 清除区域选择 | 否 | 活动 session 期间会拒绝；它不删除世界方块。 |

## 试点施工生命周期

目标只允许 `minecraft:gravel 3` 和 `create:iron_sheet 2`；方向可选
`zero`、`clockwise_90`、`clockwise_270`。

| 命令 | 用途 | 会改世界 | 使用说明 |
|---|---|---:|---|
| `/industrialagent pilot readiness <资源> <数量> [方向]` | 运行部署与执行门禁 | 否 | 核验区域、备份、权限、材料、动力、预算、恢复和正式世界 hard stop。 |
| `/industrialagent pilot dry-run <资源> <数量> [方向]` | 生成精确施工预演 | 否 | 不创建 session、不消耗资源；`start` 必须匹配最新 dry-run。 |
| `/industrialagent pilot start <资源> <数量>` | 开始固定试点 | 是 | 三秒内可 cancel；之后按 BUILD/CONNECT/FEED/PROCESS/VERIFY 运行。 |
| `/industrialagent pilot status` | 查看活动或历史 session | 否 | 检查阶段、输出和完成/失败状态。 |
| `/industrialagent pilot cancel` | 取消仍可安全停止的 session | 可能回滚 | 不伪造资源补偿；已发生不可逆处理时会保守提示。 |
| `/industrialagent pilot cleanup preview` | 预览 journal 拥有的清理项 | 否 | 每次 cleanup 前必须执行，未知/被玩家修改的方块会拒绝。 |
| `/industrialagent pilot cleanup` | 删除仍与 journal 完全匹配的 Agent 方块 | 是 | 只清理由该 session 拥有的方块，未知删除数必须为 0。 |

## 恢复与验收控制

| 命令 | 用途 | 会改世界 | 使用说明 |
|---|---|---:|---|
| `/industrialagent pilot recovery-status` | 查看持久化 checkpoint | 否 | 重进同一世界后先运行，确认 stage 和安全模式。 |
| `/industrialagent pilot resume` | 精确重扫后恢复 | 可能 | 仅 BUILD/资源前边界可恢复；不确定 PROCESS 会安全拒绝。 |
| `/industrialagent pilot hold build\|connect\|process\|verify` | 在指定阶段暂停 | 否 | Alpha 验收/故障演练命令；正常试玩无需使用。 |
| `/industrialagent pilot continue` | 继续当前内存 hold | 可能 | 先确认 status；跨退出应使用 recovery-status/resume。 |

## 只读开发/规划命令

- `/industrialagent scan create|mekanism|all [radius]`：在已加载范围做有界组件扫描；不加载新块。
- `/industrialagent plan create <resource_id> <quantity>`：生成坐标无关的类型化逻辑计划；不施工。
- `/industrialagent bind create <resource_id> <quantity>`：绑定已验证实现候选；不施工。
- `/industrialagent deploy preview|risks|budget|readiness <resource_id> <quantity> [orientation]`：
  输出只读部署证据；不会创建可执行 session。

## 常见失败处理

- `compatibility FAIL`：安装文档中的精确版本，不要继续。
- `region not confirmed/stale`：重新 preview、检查后 confirm。
- `obstacle/block entity/container`：换开阔区域，不要让 Agent 擅自拆除。
- `backup missing/invalid`：完全退出游戏，按备份流程建立并验证新备份。
- `TEST_WORLD_MARKER_REQUIRED`：只在确认这是可删除测试世界后运行 setup mark；不要改 NBT。
- `GAME_PROCESS_STILL_RUNNING`/`WORLD_SESSION_LOCKED`：保存并完全关闭客户端后再运行助手。
- `formal/unknown world refused`：说明身份或路径门禁未证明这是可删除测试世界；不得绕过。
- `duplicate session`：先查看 status，必要时 cancel/cleanup/recovery。
- `cleanup unsafe/ownership mismatch`：停止，保留诊断包，不手工强制批量删除。
- `recovery unsafe`：接受保守拒绝，使用 cleanup 或从已验证备份恢复。
# Phase IV-C player terminal (development branch)

In an explicitly marked disposable test world, obtain the terminal through its
crafting recipe or with `/give @s steve_create_agent:engineer_terminal`. Right
click opens the bilingual goal picker. During placement, the wheel rotates,
Shift+wheel changes Compact/Standard/Expandable layout, right click requests
an authoritative survey, and left click or Esc cancels the local preview. The
survey page can launch the bounded safer-site search or open Review & Confirm.
The approval button remains disabled for protected blocks, containers/data,
unknown blocks or hazards; successful approval is short-lived and exact-scope.
After approval, Select Salvage closes the screen: look at a dedicated chest or
barrel inside the nearby bounded work envelope and right-click it. The final
Start Bot Clearing screen is still an undo window. A full/changed container
pauses without partial delivery; make room or restore it before resuming.
Manage Project opens the evidence HUD controls for a live clearing session.
After a server-process restart the same project is shown as paused and requires
a fresh preview/approval; UI SavedData is never treated as execution authority.
After a successful rescan the player selects material sources, reserves the exact
bill and starts construction through the shared material-ledger workflow. Completion
and safe cancellation/return are implemented; coverage depends on the executor and
runtime. See `docs/PROJECT_STATE.md` for actual current verification.
Existing `/industrialagent site ...` and pilot commands remain the advanced
and debug interface; the UI does not bypass them or create new write authority.

# Composite production orders (development branch)

A Composite order runs several real processing stages in sequence and carries
the intermediate between them through a physical locked-hopper route. Only the
reviewed graphs in the catalog can be ordered; the runtime still re-plans and
re-reserves everything before any machine is built.

- `/steveagent composite catalog` — lists every orderable graph with the exact
  material it will reserve and the salvage it will hand back.
- `/steveagent composite create <order_type> <material_source> <site_origin> <direct|bots|hybrid> [secondary_source]`
  — starts one player-owned order. The optional `secondary_source` lets the
  reservation span a second explicitly named chest; no nearby-container discovery
  is performed. `material_source` is your own chest. `site_origin` anchors
  the derived layout: for `composite/01` it becomes the first stage's source
  chest and the line runs east; for `composite/03` it becomes the split hopper's
  ground cell, with the raw chest directly above it and the two branches to
  either side.
- `/steveagent composite status` — per-node progress of the running order.
- `/steveagent composite cancel` — stops the run. Installed infrastructure is
  deliberately left standing for inspection and the order is paused, not
  reported complete.

The order charges every boundary chest, hopper and route lock as ordinary
reserved material, so a Composite never conjures its own containers. On
success the finished output and all settled salvage are moved back into the
chest you selected before the site is cleared. If the ledger, the route
evidence, the exact output, the salvage or the cleanup cannot all be proven,
the order pauses with a typed reason instead of reporting success.

If the server restarts while an order is running, the order is paused at
`RELOAD_RECONCILIATION_REQUIRED` and nothing is replayed: no machine is rebuilt,
no report is written and no further material is taken from your chest. There is
still no way to continue such an order, but `composite cancel` now works after a
restart: it clears the site and returns everything not already consumed to the
chest you selected. Material consumed by stages that already finished is spent
and does not come back, so a restart mid-run still costs you the completed
stages' inputs.
