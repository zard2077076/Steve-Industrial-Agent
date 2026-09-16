# Player acceptance on macOS

Current execution instructions: `HANDOFF_CODEX_ACCEPTANCE.md`. The dated results below
are historical evidence, not current suite counts or an obligation to wait for the player.
Use Computer Use first for live UI; fix scoped defects and rerun. A missing environment
blocks that evidence only. Current completed scenarios are in `PROJECT_STATE.md`.

## Automated evidence

- The aggregate Core, Adapter and Forge JVM suite passes 640/640 with zero
  failures, errors or skips. This includes the exact player material ledger,
  SavedData migration/journal and bounded packet contracts.
- The 22 macOS canonical-path/alias fixtures now pass by asserting the intended
  formal-world refusal. Production formal-world protection was not relaxed.
- Site Preparation GameTest passes 12/12, including two-Bot clearing,
  conservation, full-container pause/resume, `privateItemsDropped=0`,
  `unknownBlocksRemoved=0`, `salvageLedgerBalanced=true` and a 60-tick
  identical-drop merge regression with stable UUIDs. Current evidence:
  `work/logs/site-preparation-gametest-20260809-203018.log`.
  A preceding reused-world attempt failed 9/11 because old GameTest structures
  blocked a Bot spawn and polluted the C-07 fixture. That directory is retained
  as `site-preparation-gametest.failed-20260801-120640-reused-world`; the clean
  isolated rerun is the accepted evidence.
- A separate fresh isolated integrated server passes 22/22 physical
  Direct/Bots/Hybrid equivalence tests, saves all dimensions and creates no
  crash report. Its log is
  `work/logs/phase-iv-integrated-visible-gametest-20260801-131732.log`.
- The target-entry gate passes 59/59 and proves `minecraft:blue_concrete` is
  search-visible, outside the eleven reviewed rows and accepted by the real server
  project entry: `work/logs/goal-driven-execution-gametest-20260809-202428.log`.
- A repository userdev client startup loaded macOS aarch64 Java 17, Forge
  47.4.10, Create 6.0.6, all resources and the render/audio engines, then
  remained alive until an intentional Ctrl-C. Crash reports and residual Java
  processes were both zero. The optional JourneyMap compatibility mixin logged
  a warning because JourneyMap is not installed.
- JSON language assets parse successfully.

## Visible in-world checklist (pending player operation)

1. Use `scripts/new-player-acceptance-client.sh` (directly or through the Harness)
   and the external disposable `Steve Agent Mac Acceptance` world.
2. Obtain the terminal, create one supported project and exercise all four
   rotations and three layouts.
3. Verify relocation avoids a chest and the approval button disables for a
   protected/unknown BlockEntity.
4. Select a dedicated empty salvage chest, start clearing, open Manage Project,
   pause, continue and observe the HUD.
5. Fill the salvage chest before delivery, confirm the session pauses without
   partial insertion, make room and continue.
6. Select two allowed material chests/barrels and confirm the highlighted
   source count, exact available/missing quantities and reset/cancel controls.
7. Confirm reservations, then alter a reserved stack and verify the project
   pauses with an exact source-change diagnostic rather than using another box.
8. In Bots mode, observe the courier walk to the selected source, withdraw only
   the required stack, visibly carry it and deliver it to staging before the
   existing construction executor begins.
9. Cancel one withdrawn/delivered project and verify exact return; repeat with a
   full return slot and verify safe `RETURN_PENDING` pause until capacity is
   restored.
10. Verify clearing salvage is excluded by default, then explicitly authorize
    this project's salvage and observe the bounded transfer evidence.
11. Complete one project and verify the report is derived from real output and
    shows zero duplicate withdrawal/return, zero unaccounted/private items and
    `materialLedgerBalanced=true`.
12. Save/reload during material movement and verify recovery pauses for exact
    reconciliation/return rather than claiming automatic executor resume.

The automated client-startup and server gates above are complete, but this new
material-source/courier/report click-through has not yet been performed by the
player in this branch. `MAC_DEVELOPMENT_ACCEPTANCE` therefore remains pending
and is not a release gate. Windows/PCL2 validation remains a separate deferred
handoff gate.

## 开发客户端日志层验收（Claude 可无头执行）

```bash
./scripts/new-player-acceptance-client.sh
```

日志在 `forge-create-1.20.1/run/logs/latest.log`。**判据只有三条**，都不需要看画面：

```bash
grep -cE '\[(ERROR|FATAL)\]' forge-create-1.20.1/run/logs/latest.log   # 必须是 0
grep -ciE 'Missing (texture|model)' .../latest.log                     # 必须是 0
grep -ciE 'Exception in thread|Reported exception' .../latest.log      # 必须是 0
# 不要用 'crash' 匹配：Forge 早期显示会打印一句含 crash 的提示文本，恒定误报 1。
```

**这一层能抓到什么**：2026-08-08 第一次跑就抓到工程师终端的物品贴图缺失——
model 指向 `minecraft:item/compass`，而指南针是 32 帧动画物品，**根本没有 `compass.png`**，
所以玩家手里那个核心物品一直是紫黑格子。任何 JVM 测试都看不到这个：
模型能解析、物品能注册、配方能用，坏的只有玩家看到的那一面。

**这一层不能抓到什么**（写清楚，免得被当成完整验收）：

- 中文字体好不好看、UI 排版对不对、按钮点下去的反馈——**要人看画面**
- C-01～C-10、Composite、Bot 群、IE Metal Press 的实机闭环——
  这个客户端的游戏目录**在仓库内**，安全门禁会拒绝完整世界施工。**设计如此，不是缺陷。**

**Claude 为什么截不到那个窗口**（2026-08-08 实测）：
`runClient` 起的是裸 JVM，**没有 bundle identifier**
（`osascript … get bundle identifier of … "java"` 返回 `missing value`），
而截图按 bundle id 白名单在合成层过滤，所以那个窗口对自动化不可见。
辅助功能权限本身是好的——截图工具对有 bundle id 的应用工作正常。
**要让画面可被自动检查，必须用一个有 bundle id 的启动器**（例如 Prism Launcher 的 .app），
那也正是下面第二层实机验收要用的东西。

## 完整玩家验收：仓库外的 runClient（2026-08-08，Claude）

```bash
./scripts/new-player-acceptance-client.sh
```

**不需要正版 Minecraft**，ForgeGradle 自带开发账号；**不需要启动器**。

### 为什么默认的 runClient 建不了东西，而这个可以

运行时有一条：

```java
if (hasGitAncestor(actualGame)) return failure("SOURCE_TREE_GAME_DIR_REFUSED");
```

游戏目录只要有 git 祖先就拒绝施工——**这是刻意的**：施工要写区块文件，
源码树是最不该放它们的地方。默认的 `run/` 就在仓库里，所以那个客户端
**只能做上面那层日志检查，永远不能宣称任何施工闭环**。

**这条拦的是目录位置，不是 runClient。** 所以脚本把游戏目录挪到
`~/SteveAgentPlayerAcceptance/`（可用 `STEVE_ACCEPTANCE_ROOT` 改），
门禁一个字没动就满足了。

**曾经为此装过 Prism Launcher 搭独立实例，那是绕远路**：
正版验证挡在前面，而这条路根本不需要正版。Prism 与它下载的 749MB 已卸载。

脚本每次都重写配置，因为 `testInstanceRoot` 必须**等于**游戏目录，
路径过期只会报 `TEST_INSTANCE_ROOT_MISMATCH`，不会说到底哪里不一样。
`importantInstanceRoots` 也必须非空且真实存在（`IMPORTANT_INSTANCE_ROOT_REQUIRED`），
脚本会建一个 `~/SteveAgentProtectedInstances/` 占位。

**否定半边也是可用的**，值得偶尔跑一次确认它还在：

```bash
STEVE_ACCEPTANCE_ROOT=<仓库内任意路径> ./scripts/new-player-acceptance-client.sh
# → Refusing a game directory inside the repository
```

**已实测**（2026-08-08）：游戏目录落在 `~/SteveAgentPlayerAcceptance`，
0 个 ERROR、0 个缺失贴图，Create 6.0.6、Immersive Engineering、
本 mod 全部加载。

### 进去之后

建一个**创造模式、开作弊**、名字正好是 `Steve Agent Mac Acceptance` 的世界，然后：

```
/industrialagent version
/industrialagent compatibility
/industrialagent setup show-config
/industrialagent setup mark-test-world
/industrialagent setup validate
/give @s steve_create_agent:engineer_terminal
```

然后按本文件前面的检查表逐项走。

**点击必须由玩家做**，原因是 `runClient` 起的是**裸 JVM，没有 bundle identifier**
（`osascript … get bundle identifier of … "java"` 返回 `missing value`），
而自动化的窗口白名单按 bundle id 解析，所以那个窗口既不能被截到、也不能被点到。
键盘可用，日志可读。

**先前在这里写过「macOS 27 把任意一点都判给程序坞」——那个诊断是错的**，
留在这里当反例：命中测试一直在**正确**报告最上层窗口。
真相是屏幕上有若干后台应用开着全屏窗口（扇贝单词、QQ、微信……），
而截图会把不在白名单的应用**从画面里滤掉**，于是看上去点的是空白桌面，
实际上点在它们身上。把 Dock 隐藏之后，报的名字立刻变成了下一个遮挡者。
**「我看不见它」和「它不在那里」是两回事**，截图过滤让这两者看起来一样。
