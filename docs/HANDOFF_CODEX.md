# 开发交接

在仓库根目录工作，确认当前分支与 `git status`，查看相关最近提交。
先读短版 [PROJECT_STATE](PROJECT_STATE.md) 和 [MASTER_PLAN](MASTER_PLAN.md)，
再按任务查代码、架构或历史记录；不需要固定读完全部文档、逐项报批或复制长提示词。

按用户最新授权自主选择实现顺序、架构和测试策略，持续完成有价值的切片。
旧门编号、固定计数、日期和“不要跳序”不是不可更改的约束。
发现缺陷可直接修复并复测；不能隐藏失败、冒充实机通过或动正式存档。

## 当前接力点

当前代码/验证状态仅维护在 [PROJECT_STATE](PROJECT_STATE.md)。C-04 恢复红门已关闭；
旧 C-03 PauseScreen 和 Composite quoting 问题也已关闭，不要重查。
进行中的工作是共用 Create 玩家场景 runner，不能将其未完成改动当已验证成果。

## 选择合适的验证

```bash
./gradlew test build --offline
# 以下按实际风险选择，不是每次改动都必须全跑：
./scripts/test-goal-driven-execution.sh
./scripts/test-composite-player-order.sh direct,bots,hybrid 01,03
./scripts/test-composite-resume.sh
./scripts/test-composite-reload.sh
./scripts/test-site-preparation-gametest.sh
./scripts/test-c04-recovery-reload.sh
./scripts/test-warehouse-unattended.sh
./scripts/test-warehouse-restart.sh
```

macOS 优先已有 `.sh`；需要新的依赖时可使用联网解析，缓存充分时用 `--offline`。
先确认脚本的实际运行目录/进程，保留失败证据；不要一边运行脚本一边修改它。
测试数从真实报告读取，关键行为从世界/资源/事务结果验证，不靠旧数字兜底。

## 实机

流程见 [HANDOFF_CODEX_ACCEPTANCE](HANDOFF_CODEX_ACCEPTANCE.md)。优先 Computer Use，
Harness 补充状态和证据。工具库存看不到 Java 时检查真实进程与应用/窗口映射；不要假定
某个 agent 天生支持或不支持 PID/windowID。只能用当前接口明确提供的能力。
显示或 Windows 环境暂不可用，记录未验证项并继续独立的开发工作。

详细旧交接可用 `git show cbac708:docs/HANDOFF_CODEX.md` 查看。
