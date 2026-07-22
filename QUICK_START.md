# 五分钟快速开始

前提：已按 `INSTALLATION.md` 完成配置、世界标记、离线备份，并且
`compatibility`、`setup validate`、`backup verify` 都返回 PASS。

进入同一个可删除测试世界，站在平坦开阔区域：

```text
/industrialagent pilot region here 32 32 20
/industrialagent pilot region preview
/industrialagent pilot region confirm
/industrialagent pilot readiness minecraft:gravel 3
/industrialagent pilot dry-run minecraft:gravel 3
/industrialagent pilot start minecraft:gravel 3
/industrialagent pilot status
/industrialagent pilot cleanup preview
/industrialagent pilot cleanup
/industrialagent pilot readiness create:iron_sheet 2
/industrialagent pilot dry-run create:iron_sheet 2
/industrialagent pilot start create:iron_sheet 2
/industrialagent pilot status
```

每次 `start` 前都检查 preview/readiness/dry-run；它们必须显示零 mutation，且
身份、备份、权限、材料、动力和恢复门禁都通过。`start` 有三秒取消窗口，可运行
`/industrialagent pilot cancel`。完成后必须先 `cleanup preview`，确认只包含本
session 拥有的方块，再运行 `cleanup`。

任何 `UNKNOWN`、`stale`、`unsafe`、正式世界拒绝、备份过期或 cleanup ownership
错误都表示停止，不表示可以手工绕过。
