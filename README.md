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
| `scripts/` | 仓库级构建、测试、打包和管理器源码 |

仓库根目录没有 `pom.xml`，各语言项目独立管理依赖和生命周期。OpenCode 的 MCP 服务键继续使用 `microbreakpoint`，因此已公开的工具前缀保持为 `microbreakpoint_*`。

## 环境

- Java 17+、Maven 3.9+
- Node.js 20+、npm
- Conda 环境 `breakhub`，Python 3.12
- PowerShell 7（`pwsh`）；正式打包脚本同时兼容 Windows PowerShell 5.1

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
pwsh -File .\scripts\package-java-demo.ps1
```

Windows PowerShell 5.1 也可以执行正式打包：

```powershell
powershell.exe -NoProfile -File .\scripts\package.ps1 -Python 'python'
```

`package.ps1` 生成三个正式发布分类：`dist/hub/`、`dist/java-probe/` 和 `dist/breakpoint-debugging/`。Hub 目录包含可直接本机联调的 `application.yml` 与 `start.ps1`；Java Probe 目录包含本地 Maven 安装命令和用户手册；Breakpoint Debugging 目录包含 Skill ZIP、独立管理 EXE 和用户手册，Skill ZIP 内含 `breakhub-mcp.exe`。

`package-java-demo.ps1` 独立编译 Probe、测试 Java Demo，并生成测试辅助目录 `dist/java-demo/`；该 Demo 不属于正式发布物。

## 启动本地联调

先生成正式包和 Demo 测试包，再分别在两个 PowerShell 7 终端启动：

```powershell
pwsh -File .\scripts\package.ps1 -Python 'python'
pwsh -File .\scripts\package-java-demo.ps1

# 终端 1
pwsh -File .\dist\java-demo\start.ps1

# 终端 2
pwsh -File .\dist\hub\start.ps1
```

Hub 使用 [scripts/release/hub/application.yml](scripts/release/hub/application.yml) 生成的本机联调配置，只监听 `127.0.0.1:18621`；Demo 监听 `127.0.0.1:18622`。该配置中的明文凭据只用于本机联调，非本机场景必须全部替换。

## 安装 Skill

`dist/breakpoint-debugging/` 已把 Skill ZIP 和独立管理器放在一起。管理器不依赖 PowerShell 7、新版 .NET 或用户本机 Python，可双击运行，也可以显式执行：

```powershell
.\dist\breakpoint-debugging\breakpoint-debugging-manager.exe install --scope project --project-root .
```

运行期连接直接在 Agent 对话中通过 MCP 管理，不需要用户运行外部配置命令。本地只保存 BreakHub URL 与访问 Token，MCP 会从 Hub 实时刷新设备 ID 和展示名，避免双份身份数据不一致。安装、对话配置、卸载和占用处理见 [scripts/release/breakpoint-debugging/README.md](scripts/release/breakpoint-debugging/README.md)；产品配置见 [bp-hub/README.md](bp-hub/README.md)。
