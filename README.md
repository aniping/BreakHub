# BreakHub

BreakHub 是一个可独立部署的断点调试产品。业务进程通过探针上报调用，Hub 负责断点、暂停、注入和审计，Agent 通过本地 stdio MCP 操作同一套权威状态。

## 目录

| 目录 | 用途 |
| --- | --- |
| `bp-hub/` | Java 17 / Spring Boot 后端和同目录 Vue Web |
| `bp-probe/java/` | Java 探针依赖；以后新增语言时在 `bp-probe/` 下增加同级目录 |
| `bp-mcp/` | 独立 Python MCP 项目，构建 `breakhub-mcp.exe` |
| `skills/breakpoint-debugging/` | Breakpoint Debugging Skill 源目录，发布时注入 MCP EXE |
| `example/java/` | Java 集成示例，仅用于验证，不作为发布产物 |
| `scripts/` | 仓库级构建、测试、打包和 Skill 安装入口 |

仓库根目录没有 `pom.xml`，各语言项目独立管理依赖和生命周期。OpenCode 的 MCP 服务键继续使用 `microbreakpoint`，因此已公开的工具前缀保持为 `microbreakpoint_*`。

## 环境

- Java 17+、Maven 3.9+
- Node.js 20+、npm
- Conda 环境 `breakhub`，Python 3.12
- PowerShell 7（`pwsh`）

Python 环境使用 `requirements.txt` 安装：

```powershell
conda activate breakhub
python -m pip install -r .\bp-mcp\requirements.txt
python -m pip install -e .\bp-mcp --no-deps
```

## 构建与验证

```powershell
pwsh -File .\scripts\build.ps1 -Python 'python'
pwsh -File .\scripts\test.ps1 -Python 'python'
pwsh -File .\scripts\package.ps1 -Python 'python'
```

`package.ps1` 生成三个独立发布物：Hub JAR、Java Probe JAR 和 `breakpoint-debugging.zip`。Skill ZIP 内含 `breakhub-mcp.exe`；`example/java` 不进入发布目录。

## 安装 Skill

优先让 Agent 执行安装。把 `dist/breakpoint-debugging.zip` 和 `dist/install-breakpoint-debugging.ps1` 放在一起，然后告诉 OpenCode：

```text
安装这个用于 BreakHub 的 Breakpoint Debugging Skill 到当前项目，并验证 MCP 连接。
```

手工兜底只需一条命令，默认安装到当前项目；也可以显式传 `-Scope Global`：

```powershell
pwsh -File .\dist\install-breakpoint-debugging.ps1
```

详细产品配置见 [bp-hub/README.md](bp-hub/README.md)，Agent 工作流与卸载说明见 [skills/breakpoint-debugging/references/opencode-setup.md](skills/breakpoint-debugging/references/opencode-setup.md)。
