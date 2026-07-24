# AGENTS.md

## 环境

- Windows 11，默认 shell 为 PowerShell 7（`pwsh`）。
- Python 使用 Conda 环境 `breakhub`，依赖文件为 `bp-mcp/requirements.txt`。
- Java、Python、Node.js 项目相互独立；仓库根目录不得增加聚合 `pom.xml`。

## 模块边界

- `bp-hub/`：BreakHub 权威后端与 Web。
- `bp-probe/<language>/`：按语言组织的业务探针。
- `bp-mcp/`：本地 stdio MCP；EXE 名为 `breakhub-mcp.exe`。
- `bp-skill/`：标准 Skill 源码；不得提交生成的 EXE。
- `example/`：集成验证，不作为发布产物。

OpenCode MCP 服务键和工具前缀 `microbreakpoint_*` 是保留的外部契约，不得随产品重命名而修改。

修改后运行与影响范围对应的最小测试；发布前运行 `scripts/test.ps1`。提交信息使用中文，并说明修改摘要与验证方式。
