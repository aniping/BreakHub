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

产物为 `dist/breakhub-mcp.exe`。目标注册表示例见
`breakhub_targets.example.json`；安装脚本会把可变配置放在 Skill 目录之外。
