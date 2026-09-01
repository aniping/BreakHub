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
| `scripts/` | 仓库级 CMD 入口、内部构建实现、发布模板和管理器源码 |

仓库根目录没有 `pom.xml`，各语言项目独立管理依赖和生命周期。OpenCode 与 AteAgent 的 MCP 服务键都使用 `microbreakpoint`，因此已公开的工具前缀保持为 `microbreakpoint_*`。

## 环境

- Java 17+、Maven 3.9+
- Node.js 20+、npm
- Conda 环境 `breakhub`，Python 3.12
- 生成 Windows 安装包时需要带 `jpackage` 的 JDK 17 和 NSIS 3；这些只属于构建机依赖
- `vendor/jdk/` 可携带一个 Temurin 17 Windows x64 ZIP；打包脚本会优先自动解压使用，复制工程到其他构建机时无需另装 JDK

Python 环境使用 `requirements.txt` 安装：

```powershell
conda activate breakhub
python -m pip install -r .\bp-mcp\requirements.txt
python -m pip install -e .\bp-mcp --no-deps
```

## 构建与验证

```powershell
.\scripts\build.cmd -Python python
.\scripts\test.cmd -Python python
.\scripts\package.cmd -Python python
.\scripts\package-java-demo.cmd
```

`scripts/` 根目录只保留上述四个公开 CMD 入口；复杂的发布实现位于 `scripts/internal/`，由 Python 标准库负责 JSON、ZIP、SHA-256 和安全路径校验。仓库不再跟踪 PowerShell 脚本。Python 路径包含空格时必须使用双引号，例如 `-Python "C:\Program Files\Python312\python.exe"`。

`package.cmd` 生成三个正式发布分类：`dist/hub/`、`dist/java-probe/` 和 `dist/breakpoint-debugging/`。Hub 目录包含集成 Java Runtime 的 `BreakHub-Setup-0.1.0.exe`，以及供开发联调的 JAR、`application.yml` 与 `start.cmd`；Java Probe 目录包含本地 Maven 安装命令和用户手册；Breakpoint Debugging 目录同时包含 OpenCode Skill ZIP、独立管理 EXE、用户手册，以及可由 AteAgent 直接上传的 `breakpoint-debugging-ateagent-0.1.0.zip`。

只构建 Hub 安装包时运行 `bp-hub\scripts\build-installer.cmd`。默认安装目录是 `C:\Program Files\BreakHub`，安装时仍可选择其他目录；写入 Program Files 需要管理员授权，卸载项和快捷方式按整机范围创建。内置 Java 17 Runtime 只放在所选目录中，不安装系统 JDK/JRE，也不修改 `JAVA_HOME` 或 `PATH`。配置、数据和日志分别位于安装目录的 `application.yml`、`data\` 和 `logs\`；启动与停止程序会请求管理员权限。卸载时会询问是否一并删除这些数据，默认保留。安装后使用独立的 `BreakHub.exe` 和 `BreakHub-Stop.exe`；双击启动程序会在服务就绪后自动打开默认浏览器。MCP 不进入 Hub 安装包，它仍由 Agent 侧的 OpenCode Skill 或 AteAgent 集成包管理。

`package-java-demo.cmd` 独立编译 Probe、测试 Java Demo，并生成测试辅助目录 `dist/java-demo/`；该 Demo 不属于正式发布物。

## 启动本地联调

先生成正式包和 Demo 测试包，再分别在两个 PowerShell 7 终端启动：

```powershell
.\scripts\package.cmd -Python python
.\scripts\package-java-demo.cmd

# 终端 1
.\dist\java-demo\start.cmd

# 终端 2
.\dist\hub\start.cmd
```

Hub 使用 [scripts/release/hub/application.yml](scripts/release/hub/application.yml) 生成的本机联调配置，只监听 `127.0.0.1:18621`；Demo 监听 `127.0.0.1:18622`。该配置中的明文凭据只用于本机联调，非本机场景必须全部替换。

## 安装 Skill

`dist/breakpoint-debugging/` 已把 Skill ZIP 和独立管理器放在一起。管理器不依赖 PowerShell 7、新版 .NET 或用户本机 Python，可双击运行，也可以显式执行：

```powershell
.\dist\breakpoint-debugging\breakpoint-debugging-manager.exe install --scope project --project-root .
```

运行期连接直接在 Agent 对话中通过 MCP 管理，不需要用户运行外部配置命令。本地只保存 BreakHub URL 与访问 Token，MCP 会从 Hub 实时刷新设备 ID 和展示名，避免双份身份数据不一致。安装、对话配置、卸载和占用处理见 [scripts/release/breakpoint-debugging/README.md](scripts/release/breakpoint-debugging/README.md)；产品配置见 [bp-hub/README.md](bp-hub/README.md)。

在 AteAgent 中无需运行管理器。打开 Skill 安装页，上传同目录下的 `breakpoint-debugging-ateagent-0.1.0.zip`；AteAgent 会校验固定清单、逐文件 SHA-256、Skill 和 MCP 启动配置，再安装 Skill、运行时和 `microbreakpoint` 配置。工具由 MCP `tools/list` 自动发现，不在安装清单中重复枚举。该 ZIP 不包含安装脚本，目标连接仍在 Agent 对话中通过 MCP 管理。
