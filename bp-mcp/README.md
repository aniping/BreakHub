# BreakHub MCP

`bp-mcp` 是 BreakHub 的本地 stdio MCP 网关。OpenCode 中的 MCP 服务键继续使用
`microbreakpoint`，因此 Agent 看到的工具前缀仍为 `microbreakpoint_*`。

## 开发

```powershell
conda activate breakhub
python -m pip install -r requirements.txt
python -m pytest -q
python -m ruff check .
python -m mypy src/bp_mcp
```

## 构建单文件 EXE

```powershell
pwsh -File .\scripts\build-exe.ps1
```

产物为 `dist/breakhub-mcp.exe`。连接注册表示例见
`breakhub_targets.example.json`。安装管理器会把可变配置放在 Skill 目录之外；配置只包含 BreakHub URL 与访问 Token。Agent 通过 `list_connections`、`upsert_connection` 和 `remove_connection` 在 MCP 对话中管理连接，MCP 在每次列举或连接时从 `/api/v1/equipment` 刷新权威设备身份。
