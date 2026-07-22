# 已知问题

- RC1 尚未就绪：仍需最终提交上的两个全新 Gradle 缓存字节一致构建，以及全新独立客户端肉眼验收。
- 生产配置、世界 marker、离线备份和重启验真已自动通过；这不等于 gravel/iron-sheet
  的新客户端视觉验收，视觉结果必须由用户确认。

- 这是 Experimental Alpha，只支持新建、可删除、创造模式测试世界。
- 只支持 `minecraft:gravel x3` 和 `create:iron_sheet x2` 两个真实施工试点。
- 不支持电网、核反应堆、多生产线、多区域、任意 Create 配方或自然语言施工。
- Mekanism-only 与 Create+Mekanism 运行矩阵仍未完成，不得推断为支持。
- Windows/PCL2 是当前真实验证组合；其他启动器和 Linux 玩家运行未宣称支持。
- 恢复只覆盖严格的 BUILD/资源前边界；不确定的 PROCESS 会拒绝。
- cleanup 只删除 journal 拥有且状态匹配的方块；玩家修改会导致安全拒绝。
- 诊断 ZIP 不自动上传。报告 Bug 时附带它、操作命令和可复现步骤，不要附世界文件、
  playerdata、聊天、账号信息或整合包 JAR。
