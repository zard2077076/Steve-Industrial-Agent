# 当前已知问题

更新：2026-09-05。当前验证状态见 [PROJECT_STATE](docs/PROJECT_STATE.md)；详细问题与
修复出处见 [问题台账](docs/KNOWN_ISSUES.md)。旧根目录诊断可用
`git show cbac708:KNOWN_ISSUES.md` 查看，不再作为待办。

- 完整玩家交互/故障/布局矩阵、C-06～C-10 与多 Bot 客户端代表性验证仍未完成。
- 最新 UI loading/empty 文案尚未视觉复验；缺少图形会话时继续后台工作。
- 旧隔离世界可能残留前次 Bot/掉落物，需按工程归属识别，不可计入新工程或随意清空。
- Windows/PCL2 为 `WINDOWS_VALIDATION_DEFERRED`；Mekanism 真机集成尚未完成。
- 恢复覆盖与限制取决于具体事务/执行器和持久化证据；不承诺任意 PROCESS 中断都能恢复。
- 正式玩家实例不用于开发测试；未知所有权、物品账差或不确定恢复不能静默绕过。

已解决：C-04 双进程恢复超时（`cbac708`）、C-03 PauseScreen、Composite fixture quoting、
Composite/03 投送量混入安装基线、玩家场地准备状态丢失。请勿按旧记录重新开工。
