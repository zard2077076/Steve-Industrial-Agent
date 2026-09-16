# Mac 客户端验证

目标是发现并修好真实玩家会遇到的问题，而非完成形式检查表。当前授权包含边测边修；
只有用户另行要求只读验收时才只报告。开发交接见 [HANDOFF_CODEX](HANDOFF_CODEX.md)。

## 环境与启动

仅使用指定的仓库外可删除测试根 `~/SteveAgentPlayerAcceptance`，世界名精确为
`Steve Agent Mac Acceptance`。保留已有失败现场；未确认当前游戏归属前不要操作或重启。

```bash
# 如需测试桥，先 doctor，再通过 Harness 启动：
steve-agent-test --json doctor
steve-agent-test --json client launch --wait
# 不使用桥时可直接运行同一 launcher：
./scripts/new-player-acceptance-client.sh
```

两种方式择一，不重复启动。不要直接 runClient：默认目录位于 Git 祖先下，生产安全检查
会正确拒绝施工。此处应修启动路径而不是移除正式世界保护。仅在指定隔离根中按需建世界。

## UI 控制与找不到窗口

优先使用当前已提供的 Computer Use 能力，使用其返回文档中的 API；Harness 可补充
真实 packet、世界、材料和日志证据。不要沿用旧“Claude 不能/Codex 一定能按 PID”结论。

若用户能看见并操作游戏、工具却没有列出窗口，先自行诊断：

1. 用当前应用名尝试，失败后检查应用库存中的真实 ID；不能只靠搜索 `Minecraft`。
2. 只读核对 Java 进程、父进程及本次验收启动身份，区分 Gradle daemon 与游戏。
3. 按当前工具明确支持的应用路径/ID 重新绑定；若 AX 树为空，尝试该应用截图再操作。
4. 对照图形会话和工具权限错误；“无 app 记录”“无 AX 节点”“无窗口”“无显示器”
   是不同问题。不得用其中一项推断其余三项。
5. API 确实不支持该窗口时报告具体限制并使用已授权的可用替代方式；不得假造 PID API、
   盲点坐标、修改系统安全权限或擅自重启用户当前游戏来掩盖问题。

## 启动诊断

按需查看 `/industrialagent version`、`compatibility`、`setup show-config`、
`setup mark-test-world` 和 `setup validate`，并领取工程终端。

| 失败码 | 诊断方向 |
|---|---|
| TEST_INSTANCE_ROOT_MISMATCH | 配置中的隔离根与实际游戏目录不一致 |
| CURRENT_WORLD_NOT_ALLOWLISTED | 世界名称/身份不符合指定验收环境 |
| SOURCE_TREE_GAME_DIR_REFUSED | 游戏目录位于 Git 树内，核对 launcher |
| IMPORTANT_INSTANCE_ROOT_REQUIRED | 检查隔离配置如何保留正式实例排除项 |

失败后先查真实原因；可修本任务内的代码或隔离配置并重试，不要求把每个错误先交回用户。
涉及正式实例、新权限或无法识别的保存路径才停下请求方向。保留原失败日志。

## 按风险选择场景

- 终端贴图、搜索、数量/模式、旋转/布局、预览和保护方块。空查询与当前评审目录一致；
  `concrete` 应找到 `minecraft:blue_concrete` 且能实际发单。不要硬守旧派生产品总数。
- 无回收箱时能退出选择、放箱、继续；单/多材料源、缺料提示、重选、取消。
- 同箱槽位重排不影响物品级预留；实际取走材料会准确暂停，不偷偷换箱。
- DIRECT/BOTS/HYBRID、Composite、多 Bot；观察真实拾取/运输/搭建，不只看进度标签。
- 取消精确返还、箱满 RETURN_PENDING、腾出空间恢复、保存退出后的对账与继续。
- 风扇材料单应包含实际放置介质所需的水桶/打火石等物品，不用“水/火”替代实物成本。
- IE Metal Press：从当前配方和 BOM 验证结构、锤、模具、真实 FE 网络、投料/产出、返还与
  基线恢复。当前固定单批代表是 2400 FE/1 铁板；更换配方或结构后按真实计划/配方更新预期。

完整候选场景见 [PLAYER_ACCEPTANCE_MAC](PLAYER_ACCEPTANCE_MAC.md)，并非每次小改都全跑。
UI 问题可直接修复并复验；删除错误断言须换成有判别力的行为检查，不是删掉失败结果。

## 结果

区分通过、失败、未运行及原因。账本验证关注物品守恒、唯一取料/返还/产出和私人物品零触碰；
不同场景按实际契约核对。保存对应工程 ID、日志与截图，不能用旧报告或旧截图冒充新版本。
Computer Use 的可见观察、桥接自动化和玩家主观体验分别说明。缺图形环境时继续独立工作。

测试后不默认删世界或关闭非本任务创建的客户端；清理仅针对明确归属且不需保留的测试产物。
