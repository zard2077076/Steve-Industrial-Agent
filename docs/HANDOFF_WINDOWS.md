# Mac → Windows 交接

这份文件的用途和 `HANDOFF_CODEX.md` 不同。那份是「Claude 额度用完，换 Codex 接着做同一批活」；
这份是**平台交接**：有一批工作在 macOS 上根本跑不了，必须换到 Windows。

传输方式沿用这个项目原本的做法——git bundle，不经 GitHub。
Windows→Mac 当初就是这么过来的（`Steve-Agent-Phase-IV-Windows-to-Mac.bundle`）。

---

## 一、拿到代码

Mac 侧已生成 `Steve-Agent-Phase-IV-Mac-to-Windows.bundle`（完整历史，`git bundle verify` 通过）。
把它拷到 Windows 后：

```powershell
git clone <bundle 文件路径> Steve-Agent-phase-iv-mac
cd Steve-Agent-phase-iv-mac
git checkout feature/phase-iv-player-ux-site-workflow
```

`git log --oneline` 应该能看到本轮 17 个提交，最新一个是移除本机绝对路径那条。

---

## 二、为什么必须换平台

不是偏好问题。仓库里 42 个 `Test-*.ps1` 门，**只有 4 个有 macOS 对应脚本**；另有 22 个门驱动的
gradle property 在 bash 侧完全没有实现。发布链更彻底：`Test-Release*`、`Test-CleanRoom*`、
`Test-Reproducible*`、`Test-Distribution*`、`Test-PublicRuntime*` **十个发布门全部只有 PowerShell 版**。

这批门在 Mac 上不是「跑失败」，是「跑不了」。而门跑不了的后果这轮已经付出过代价——见第四节。

---

## 三、Windows 上该做的（按价值排序）

### 1. RC1 的三个 open gate

`docs/PUBLIC_ALPHA_RELEASE.md` 写死了：三个不关，不许打 RC tag。

| # | 门 | 脚本 |
|---|---|---|
| 1 | 两套全新隔离 Gradle 缓存离线构建出**字节一致**的 release JAR | `Test-ReproducibleRelease.ps1` |
| 2 | PCL2 工作流肉眼验收：setup/backup、gravel×3、cleanup、iron sheet×2、cancel、reload/resume | 人工，`docs/HANDOFF_CODEX_ACCEPTANCE.md` |
| 3 | 最终回归/隐私扫描 + 零进程/崩溃回读 | `Test-ReleasePrivacy.ps1` 等 |

第 2 条只能由玩家本人做，Codex 做不了。

### 2. C-04 恢复门：一个诊断到底、但没修完的 bug

`scripts/test-c04-recovery-reload.sh`（bash 版本本轮才补上）读阶段最后一步超时。
**不要从头查**——`KNOWN_ISSUES.md` 里有完整的排除记录，五个假说全被实测推翻了：

- 不是预算不够：同拓扑下 `test-c04-survival-power.sh` 压制成功
- 不是漏斗朝向：`BeltPressPlan:213` 计划本身就规定 `PlanBlockFacing.UP`
- 不是漏斗 behaviour：物品 `beltPos` 纹丝不动，根本没到带末端
- 不是缺动力/BE 丢失/区块卸载：全部实测正常，`positionTicking=true`
- 不是实例分裂：字段与注册实例 `same=true`

**卡点**：`prevRunningTicks=233 / runningTicks=234` 长期冻结，而 Create 6.0.6 字节码里
`tick()` 服务端路径 `206 → 229 → 348 → 401` 上没有任何 return，401 又无条件推进。
前提全为真而结论为假，必有一个前提不成立。

下一步需要的手段和前面几轮不同：**要观察控制流本身**（在 behaviour 里放进入计数器，或挂调试器），
而不是继续拍状态快照。Windows 上有 IDE 调试器，这正是换平台的价值。

### 3. Mekanism 依赖 pin —— 解锁整条 M 线

`M-02/03/04` 三条 PLANNED 全卡在同一件事：**仓库里没有 Mekanism 依赖**，
`build.gradle` 只有 `mekanismAbsentSmoke`（证明缺席时安全降级的门）。

本轮已经把**不需要 jar 的部分**做完了（`M-01A`，见 `core/mekanism/`）：
能力形状、`CHEMICAL` 的首次落地、Enrichment Chamber 声明，都带空的
`acceptedRuntimeFingerprints`，过不了任何运行时门——这是刻意的。

Windows 上要做的：

1. 确认官方 Maven 源与确切版本（候选 `1.20.1-10.4.9.61`，来自公开 Maven 记录，**未验证**）
2. 确认与 Forge 47.4.10 / MC 1.20.1 的兼容性
3. 加 `compileOnly fg.deobf(...)`，形式照抄 Create/IE 那两行
4. **重跑 `Test-ReproducibleRelease.ps1`**——加依赖会让 RA-05 的字节一致结论失效，必须重验
5. 然后才是 M-02 的扫描适配器

注意 `AGENTS.md` 的硬停：核/辐射自动化在独立安全门实现并显式批准前保持禁用。
`M-01A` 有一条测试断言不声明任何 fission/fusion/reactor/radiation/waste 能力，别绕过它。

### 4. 那 22 个缺 macOS 对应的 gradle 门

其中恢复类是重点：`Test-C03RecoveryReload`、`Test-C04RecoveryReload`、`Test-PhaseIVRecovery`、
`Test-BotFleetGameTest`。本轮在 Mac 侧补了 `test-recovery-reload.sh`（G-11）、
`test-c03-recovery-reload.sh`、`test-c04-recovery-reload.sh`、`test-phase-iv-composite.sh`、
`test-phase-iv-composite-faults.sh` 五个，其余仍缺。

---

## 四、本轮 Mac 侧做了什么（17 个提交）

修好并验证：

| | 证据 |
|---|---|
| **IE 类初始化崩溃** | `MetalPressProductionService` 在类初始化引用带 IE 类型的 adapter，没装 IE 的隔离 profile 一 tick 就崩。2026-08-13/14 引入，到 09-02 才发现——期间 site-preparation 与 composite 两条门线**一次都没成功跑起来过** |
| **玩家场地准备持久化** | `PlayerSitePreparationSavedData`，重启不再把项目卡死在 `MATERIAL_SOURCE_SELECTION` |
| **composite/03 死锁** | 分支/合并的路由检查读的是分支箱绝对数量，而生存动力提升给分支加了自己的 `create:shaft=2`，闸门要求恰好等于 1 → 永远等不到。改为 `routeDelivered` 减去安装物料基线 |
| **C-04 生产恢复缺陷** | 恢复扫描只接受 plan 的 26 个放置位置，而 checkpoint 必然带 2 个引导流水单元 → 水力拓扑下每次重启恢复都被 `PLAN_REJECTED` |
| **Mekanism 契约层** | `M-01A`，见上 |

**教训写在这里**：那个 IE 崩溃能潜伏三周，是因为门在本机跑不了。
修好后**立刻**连着挖出两个真 bug（composite/03 死锁、定向故障门里第四份错误基线）。
所以「门是绿的」之前，先确认门**跑起来过**。

---

## 五、别做的事

- 不要把 `M-02/03/04` 标 DONE：它们的验收都要求真机 Mekanism GameTest
- 不要为了让 C-04 门变绿而改断言（比如把 `== 1` 改成 `== 3`）——本轮第四个 bug 正是靠
  `AGENTS.md` 那条「不许削弱关键断言」逼出来的
- 不要在没重跑 `Test-ReproducibleRelease.ps1` 的情况下宣称 RA-05 仍然成立
