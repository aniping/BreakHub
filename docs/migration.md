# BreakHub 独立仓库迁移说明

## 目标

把断点调试能力从 LoopMind 中抽离为独立产品 BreakHub。迁移只保留断点产品、探针、MCP、Skill 和验证示例，不引入 LoopMind Agent runtime。

## 已确认结构

- `bp-hub/`：标准 Java 项目，后端与 Web 放在同一目录。
- `bp-probe/<language>/`：探针按编程语言分目录；当前只有标准 Java 项目 `java/`。
- `bp-mcp/`：独立 Python 项目，使用自己的 `pyproject.toml` 和 `requirements.txt`。
- `bp-skill/`：标准 Agent Skill 源目录。
- `example/<language>/`：只用于集成验证，不作为发布产物。
- 仓库根目录保持语言中立，不创建根级 `pom.xml`。

## 命名与兼容边界

- 产品和仓库名：BreakHub / `breakhub`。
- MCP 单文件程序：`breakhub-mcp.exe`。
- 目录名：`bp-hub`、`bp-probe`、`bp-mcp`、`bp-skill`。
- OpenCode MCP 服务键继续使用 `microbreakpoint`，工具前缀 `microbreakpoint_*` 不变。
- 源 Skill 不提交 EXE；打包时才把 EXE 注入 `bp-skill/scripts/mcp/`。

## 发布与安装

发布物分为三个：Hub JAR、Probe JAR、带 MCP EXE 的 Skill ZIP。Java Example 不进入发布目录。

Skill 安装支持当前项目和 OpenCode 全局目录，默认当前项目。安装器负责合并 MCP 与权限配置并验证连接；卸载器负责移除 Skill、MCP 注册和对应权限，除非用户明确要求，否则保留目标注册和绑定数据。

## 验收

- Hub 后端与 Web 测试通过。
- Java Probe 独立构建，Example 通过 Maven 依赖使用 Probe，二者测试通过。
- MCP 的合同测试、Ruff、严格 Mypy 通过，`breakhub-mcp.exe` 能完成 MCP 初始化握手。
- `bp-skill` 通过官方 Skill 校验，ZIP 只有一个顶层 Skill 目录。
- 临时 OpenCode 项目中的安装、连接和卸载集成测试通过。
