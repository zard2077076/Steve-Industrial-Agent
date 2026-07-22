# 安装与首次配置

> Experimental Alpha。只允许用于新建、可删除的创造模式测试世界；不要把本版本放入重要实例或重要存档。

## 兼容环境

- Windows 11、64 位 Java 17；
- Minecraft 1.20.1；
- Forge 47.4.0；
- Create 6.0.6；
- `steve-industrial-agent-0.1.0-alpha.1.jar`。

本项目不分发 Minecraft、Forge、Create、整合包或其他第三方 Mod。

## 安装步骤

1. 新建一个与任何重要实例完全分离的兼容实例。
2. 将发布包内的 Agent JAR 放入该实例的 `mods`，不要放 sources JAR。
3. 把 `config/steve-industrial-agent-common.example.toml` 复制为实例内
   `config/steve-industrial-agent-common.toml`。
4. 编辑该 TOML：
   - `testInstanceRoot`：当前测试实例的游戏目录；
   - `allowedTestWorlds`：准备新建的测试世界名称；
   - `backupRoot`：实例外、Git 仓库外的新备份目录；
   - `importantInstanceRoots`：所有不得触碰的重要实例根；
   - 保持 `requireBackup=true`、`requireWorldMarker=true`、
     `diagnosticsRedaction=true`、`formalWorldPolicy="DENY_CONFIGURED_ROOTS"`；
   - 只在确认以上路径后把 `allowDirectPilot` 设为 `true`。
5. 启动实例，新建可删除的创造模式世界并开启作弊。
6. 依次运行：

```text
/industrialagent version
/industrialagent compatibility
/industrialagent setup show-config
/industrialagent setup status
/industrialagent setup mark-test-world
/industrialagent setup validate
/industrialagent backup prepare
```

7. 保存并完全关闭 Minecraft。按聊天中显示的 `requestFile` 运行发布包内助手：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\Complete-IndustrialAgentBackup.ps1 -RequestFile "<requestFile>"
```

8. 看到 `PORTABLE_BACKUP_COMPLETE` 后重开同一实例和同一世界，运行：

```text
/industrialagent setup validate
/industrialagent backup verify
```

两项都 PASS 后才可继续 `QUICK_START.md`。任何路径重叠、reparse/junction、
marker、备份、版本或正式世界拒绝都必须先处理，不得绕过。

## 卸载

先取消活动 session 并完成安全 cleanup，退出游戏，然后删除 Agent JAR。
备份不会自动删除或上传；不要删除 Forge/Create 或整合包文件。
