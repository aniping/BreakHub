# Breakpoint Debugging 安装与配置

发布目录中的 `breakpoint-debugging-manager.exe` 是唯一的安装、卸载和运行期连接配置工具。它是独立 EXE，不要求用户安装 Python、PowerShell 7 或新版 .NET。

## 安装

把 EXE 与 `breakpoint-debugging.zip` 保持在同一目录。双击 EXE 可按提示选择当前项目或全局安装，也可以使用命令行：

```powershell
# 当前项目
.\breakpoint-debugging-manager.exe install --scope project --project-root C:\path\to\project

# 当前 Windows 用户的 OpenCode 全局目录
.\breakpoint-debugging-manager.exe install --scope global --project-root C:\path\to\project
```

安装器会保留 OpenCode 配置中的无关内容，并验证 `microbreakpoint` MCP 可以连接。安装完成后，管理器还会保留在：

- 项目安装：`<项目>\.opencode\breakhub\breakpoint-debugging-manager.exe`
- 全局安装：`%USERPROFILE%\.config\opencode\breakhub\breakpoint-debugging-manager.exe`

## 配置 BreakHub 连接

本地只保存 BreakHub URL 与访问 Token。设备 ID 和展示名不在本地重复配置；MCP 每次列举或连接时都从 BreakHub 的 `/api/v1/equipment` 刷新。

```powershell
# 新增或更新；省略 --access-token 时会安全提示输入，避免 Token 留在命令历史中
.\breakpoint-debugging-manager.exe targets upsert --scope project --project-root C:\path\to\project --url 127.0.0.1:18621

# 列出实时设备身份；输出不包含 URL 或 Token
.\breakpoint-debugging-manager.exe targets list --scope project --project-root C:\path\to\project

# 删除前先从 list 输出取得 connection_id，并明确确认
.\breakpoint-debugging-manager.exe targets remove --scope project --project-root C:\path\to\project --connection-id connection-xxxxxxxxxxxx --yes
```

全局安装时把以上命令中的 `--scope project` 改为 `--scope global`。

## 卸载

```powershell
.\breakpoint-debugging-manager.exe uninstall --scope project --project-root C:\path\to\project
```

默认保留连接配置、会话绑定和管理器，便于以后重装。只有确认不再需要这些数据时，才使用发布目录中的 EXE 执行 `uninstall ... --remove-data`。

如果 OpenCode 或 MCP 正在占用 `breakhub-mcp.exe`，管理器会先短暂重试。仍无法安全替换或删除时会返回 `RESOURCE_BUSY`，并保持 Skill 与 OpenCode 注册不变；关闭占用它的 OpenCode 任务后重试即可。管理器不会强制结束用户进程。
