# Breakpoint Debugging 安装与配置

发布目录中的 `breakpoint-debugging-manager.exe` 只负责安装、修复和卸载。它是独立 EXE，不要求用户安装 Python、PowerShell 7 或新版 .NET。运行期连接由 Agent 在对话中通过 MCP 管理。

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

## 在对话中配置 BreakHub 连接

本地只保存 BreakHub URL 与访问 Token。设备 ID 和展示名不在本地重复配置；MCP 每次列举或连接时都从 BreakHub 的 `/api/v1/equipment` 刷新。

```text
列出当前 BreakHub 连接。
把 127.0.0.1 连接到 Breakpoint Debugging，访问 Token 是 <token>。
删除连接 connection-xxxxxxxxxxxx。
```

Agent 会分别调用 `microbreakpoint_list_connections`、`microbreakpoint_upsert_connection` 和 `microbreakpoint_remove_connection`。未提供端口时自动使用 BreakHub 默认端口 `18621`；显式端口保持不变。连接写入后会立即调用 `list_equipment` 获取 Hub 返回的设备身份。工具结果不会回显 URL 或 Token；删除前 Agent 必须获得明确确认。

## 卸载

```powershell
.\breakpoint-debugging-manager.exe uninstall --scope project --project-root C:\path\to\project
```

默认保留连接配置、会话绑定和管理器，便于以后重装。只有确认不再需要这些数据时，才使用发布目录中的 EXE 执行 `uninstall ... --remove-data`。

如果 OpenCode 或 MCP 正在占用 `breakhub-mcp.exe`，管理器会先短暂重试。仍无法安全替换或删除时会返回 `RESOURCE_BUSY`，并保持 Skill 与 OpenCode 注册不变；关闭占用它的 OpenCode 任务后重试即可。管理器不会强制结束用户进程。
