# BreakHub

BreakHub 是可独立部署的装备调试产品。当前产品只有一个 Java 17 / Spring Boot 权威后端，负责 Session、持久化、认证和 Web 管理页面；通用 Agent runtime 和 Agent-facing MCP Gateway 都不属于本产品执行模块。

旧 `breakhub` 仓库只提供行为证据。新产品不兼容旧 Flask/Python 后端、旧数据库、DebugCycle/BpRule API 或旧持久化文件。

## 当前产品基线

- 第一次使用时自动创建本机可写的“默认 Session”，并设为唯一 Current Session。
- “会话列表”可以创建、重命名、浏览、切换和删除非当前的本机 Session；浏览项与 Current Session 明确分离，选择结果会在产品重启后恢复。
- 业务接入端通过同一 `interaction_id` 上报 before、等待放行和 after；未启动调试时直接 fail-open，不产生调用记录。
- Interface 从 Current Session 的 Breakpoint 与真实 Interaction 派生，字段结构只使用最近一次完整 params 样本，并保留结构变化与样本引用。
- Breakpoint 是属于 Session 的持久规则；每条只选择 before 或 after，同一 Interface 可保存并同时启用多条无条件或字段规则，Web 局部编辑不会改变稳定 `breakpoint_id`。
- 每条条件显式使用 `source=params/result`，再以 `field_path + eq/contains_any` 按 AND 匹配原始入参或成功出参；before 使用 params，after 可混合检查 params 与 result。任何 before 写入都会丢弃无法求值的 result 条件并在 `discarded_conditions` 回报；若全部丢弃，保存的是会暂停每一次目标调用的无条件 before 断点。条件始终读取注入前首次捕获的原始内容，缺失字段不同于显式 `null`，JSON 类型严格且数字比较保持精度。字段路径只穿过点分对象字段，不支持根值、数组索引、JSONPath、JSON Pointer 或通配符；非对象根结果不能命中字段条件，但无条件 after 仍会暂停。Web 会无损保留大整数、高精度小数和指数形式的 JSON 数字。
- 同一 Interaction 在同一暂停点命中多条规则时只创建一个 Pause，并按稳定顺序保存全部不可变命中快照。每条快照保留完整条件定义及一一对应的 `condition_evidence`：`eq` 记录实际相等标量，`contains_any` 只记录实际交集，不复制完整 params、result 或来源数组；它是命中时的审计证据，不是当前规则副本，后续修改、屏蔽或删除规则均不会改变。业务 `wait` 会阻塞到 Web 按 `interaction_id + pause_point` 继续。
- 当前 Pause 支持直接注入：before 只改本次 params，after 只改本次 result；嵌套 `changes` 只能认领已有字段，类型以原始内容为锚，数组只做整体替换，原始 null 不能写入非 null 值。
- 同一 Pause 可以多次累积注入并保留有序审计；只有显式继续会把当前有效内容交给业务，停止、释放、租约失效、产品关闭或 Pause 超时都会丢弃待提交注入并返回原始内容。产品启动时还会把 Current Session 的崩溃遗留 Pause 以 `product_restart` 原因安全释放，旧记录只保留为历史，不能再勾选、注入或继续。
- Web 使用浅色“概览 + 接口列表 + 断点规则 + 调用记录 + 会话列表 + 设置”工作台；断点编辑器由用户明确选择一条参考调用，params 与 result 字段证据都只读取该 Interaction，运行中的调用会明确标记 result 证据不可用。参考调用找不到字段时只显示“未验证条件”告警，不阻止保存，也不会把验证状态写入断点。调用记录主页面按 100 条分页并在服务端筛选，列表只读取轻量摘要；点击记录或选择参考调用后才按需读取完整 Payload、阶段时间线、命中快照、注入与释放审计，新 Pause 不会自动打开、替换当前详情或自动勾选。
- 调用记录中只有当前活动且没有待提交注入的 Pause 可勾选；“继续所选”会先原子校验 Current Session、控制权、活动阶段和注入状态，再只继续明确勾选项。含待提交注入的记录必须打开详情复核。
- 单条继续是幂等操作；供其他调用方使用的“继续全部”接口仍不接受 ID 列表，只原子提交命令开始时 Current Session 的 Pause 快照。
- Web 与 Gateway 通过具体控制实例排他控制调试；首次成功写操作自动取得控制，不暴露租约 token。
- 开始调试会先向真实 Java Demo 建立 Reporting Lease，只有完整 ACK 有效后才提交本地调试状态；产品每 10 秒固定延迟续签，单次请求硬超时为 5 秒，上一请求完成、取消或超时前不会重叠续签。Reporting 与 Control 使用独立的本地到期调度资源，网络黑洞不会占用或推迟二者的到期线程。续签失败会保留最后确认和 30 秒服务端截止时间并显示降级；到期时先在本地转为空闲、释放 Pause、丢弃待提交注入，再尽力停止远端，但不会释放独立的 Control Lease。显式停止、释放、Control Lease 失效、Web 退出和产品关闭同样采用本地优先收尾，远端失败不会回滚本地结果。即使 Pause 持久化清理失败，结束路径也只认领当前调试代次一次并继续收敛本地状态与 Reporting Lease；显式操作会在收尾后返回原错误，Reporting 到期仍保留 Control，而 Control 到期仍会清除 Control。
- Demo 重启后，旧租约续签会明确返回不存在。只有本地调试意图和原租约代次仍有效、且尚未到达最后 ACK 的 30 秒截止时间时，产品才会无标识建立新租约；恢复 ACK 必须在原截止时间前再次通过代次与截止时间校验，接受后才从新 ACK 重算 30 秒。迟到或旧代次 ACK 只按其新租约标识尽力停止，响应丢失的未知孤立租约不被猜测接管并由 Demo 自行到期；窗口外不自动恢复。
- 等待建立 Reporting Lease 的远端 ACK 时不持有 Control 锁；ACK 返回后必须重新校验 start 代次、Control 归属与截止时间、Current Session 以及 Reporting Lease 仍然活动。等待期间发生到期、停止、释放、换控制方、切换 Session 或产品关闭时，迟到 ACK 只能触发远端租约清理，不能提交或复活本地调试状态；Reporting 协调器独立关闭时也会先使本地调试状态失效再返回。
- 设置页面只读，密钥只显示 `configured`，不会回显实际内容。
- Web 管理员使用 Cookie 会话和 CSRF；Gateway 使用独立 Bearer 密钥；业务接入密钥不能读取管理 API。
- SQLite 数据库位于配置的数据目录。检测到旧 `debug_cycle`、`bp_rule` 等数据库表时产品拒绝启动，不迁移也不删除旧数据。

## 配置

复制示例文件并填写真实值：

```powershell
Copy-Item application.example.yml application.yml
```

`application.yml` 被 Git 忽略，应使用文件权限保护。装备身份、服务绑定、外部地址、认证密钥、超时、容量限制和数据库位置都只从该文件读取；环境变量、JVM 属性、`SPRING_APPLICATION_JSON` 和普通命令行参数不能覆盖它们。

启动命令中的 `spring.config.location` 只负责定位配置文件，不承载产品配置值。

一个产品实例只服务配置中的一台装备。`breakhub.equipment.debugger-switch.url` 必须指向业务接入端唯一入口，例如 Demo 的 `http://127.0.0.1:18622/api/demo/debugger/enabled`；本阶段该入口不鉴权，5 秒硬超时也不是部署配置项。`breakhub.security.business-client-token` 必须与 Demo 的 `debugger.business-client-token` 显式配置为相同值，仓库中 Demo 的默认值是 `breakhub-local-business-token`。Reporting Lease ID 只保存在产品协调器内存中，不进入公开 API、Web、MCP、Agent、配置或日志。

## 构建与启动

要求 Java 17+、Maven 3.9+、Node.js 和 npm。先构建 Web，再构建并启动 18621 产品：

```powershell
cd I:\ai\cc\breakhub\bp-hub\web
npm ci
npm test
npm run build

cd ..
mvn clean package
java -jar target\breakhub-0.1.0-SNAPSHOT.jar --spring.config.location=file:.\application.yml
```

仓库级发布脚本会把 Hub JAR、本机联调配置和启动脚本归类到 `dist/hub/`，可直接运行：

```powershell
.\dist\hub\start.cmd
```

打开配置文件中 `server.address` 与 `server.port` 对应的地址，例如 `http://127.0.0.1:18621/`，使用 `security.web-username` 和 `security.web-password` 登录。Web 会区分“运行中 · 业务上报正常”“运行中 · 业务服务续签异常”和“空闲 · 业务上报租约已失效”；Demo 已确认的业务上报通道异常也会单独显示。任何状态都不会显示租约 ID。

### Windows 安装包

构建机需要 JDK 17 的 `jpackage`、NSIS 3、Node.js、Maven 和 Python。执行：

```powershell
.\bp-hub\scripts\build-installer.cmd
```

需要离线搬到其他电脑打包时，把 Temurin 17 Windows x64 JDK ZIP 放在 `vendor\jdk\`。仓库当前本地已准备对应压缩包；构建脚本会校验并优先解压到 `build\jdk\`，不会向系统安装 JDK。ZIP 因体积较大而不进入 Git，复制工程时需要一并复制该目录。

产物为 `dist\hub\BreakHub-Setup-0.1.0.exe`。安装界面允许用户选择安装目录，默认目录是 `C:\Program Files\BreakHub`，因此使用默认目录安装或卸载时 Windows 会请求管理员授权。裁剪后的 Java 17 Runtime 只作为 BreakHub 私有文件放在所选目录的 `runtime\` 下，不安装系统 JDK/JRE，也不修改 `JAVA_HOME` 或 `PATH`。为避免误删其他文件，安装器只接受空目录或已有的 BreakHub 安装目录。桌面只创建“BreakHub - 启动”快捷方式；开始菜单提供启动、停止和卸载入口。对应程序是 `BreakHub.exe` 与 `BreakHub-Stop.exe`；启动程序会等待 Spring 服务就绪后自动打开默认浏览器，重复双击会打开正在运行的 Hub 地址，启动失败时则显示诊断日志位置。停止入口通过仅限本机且带随机令牌的控制通道触发 Spring 优雅关闭。

首次启动把配置模板生成到 `%LOCALAPPDATA%\BreakHub\application.yml`，数据和日志分别位于同目录的 `data\` 与 `logs\`。升级不会覆盖已有配置；卸载只删除程序和快捷方式，不删除配置、日志或业务数据。默认密钥仅用于本机联调，真实环境必须先修改。MCP 属于 Agent 侧 stdio runtime，不随 Hub 安装。

### 真实 Demo 联调

配套 Java Demo 已迁入 `example/java/`，实现 Reporting Lease 和 before/wait/after 契约。先按 [Java Demo 说明](../example/java/README.md) 构建并启动 Demo，再启动产品。产品侧重点核对：

- 产品 `/` 返回 HTTP 200，Demo `/api/demo/ping` 返回 `pong`；登录后 `/api/v1/overview` 返回产品、数据库与调试状态摘要；
- 开始调试后显示“运行中 · 业务上报正常”；
- Demo 离线后先显示“运行中 · 业务服务续签异常”；
- 到达最后 ACK 后 30 秒截止时间时转为空闲并安全释放 Pause；
- Reporting Lease 失效不得释放现有 Control Lease；
- Web 和公开 API 均不得显示 Reporting Lease ID。

## 当前公开 API

- `POST /api/auth/login`：建立 Web 管理会话。
- `GET /api/auth/session`：读取已登录管理员和 CSRF token。
- `POST /api/auth/logout`：结束 Web 管理会话。
- `GET /api/v1/overview`：读取产品、装备、Current Session 和健康摘要；`debugging.reporting.status` 公开 `idle/healthy/degraded/expired`，活动或失效诊断包含 `channel_status`、`last_confirmed_at`、`server_deadline_at` 和可选脱敏 `last_error`，不公开租约 ID。
- `GET /api/v1/settings`：读取脱敏的生效配置与诊断。
- `GET /api/v1/equipment`：读取本产品服务的装备摘要。
- `GET /api/v1/sessions/current`：读取唯一 Current Session。
- `GET /api/v1/sessions`、`GET /api/v1/sessions/{session_id}`：Web 浏览本机与导入 Session；浏览不会隐式切换 Current Session。
- `POST /api/v1/sessions`：Web 创建本机 Session。
- `POST /api/v1/sessions/import`：Web 导入经过完整校验的 `breakhub-session-v1` JSON；成功后生成新的本地 Session ID，并永久保持只读、非 Current。
- `PATCH /api/v1/sessions/{session_id}`：Web 重命名本机 Session。
- `POST /api/v1/sessions/{session_id}/current`：Web 显式切换 Current Session；调试运行时拒绝。
- `DELETE /api/v1/sessions/{session_id}`：Web 删除非 Current Session。
- `POST /api/v1/sessions/current/interactions/clear`：清空 Current Session 的 Interaction、Pause、注入与继续审计并保留全部 Breakpoint；仍有暂停调用时拒绝。
- `GET /api/v1/sessions/{session_id}/archive`：Web 浏览完整 Session 证据。
- `GET /api/v1/sessions/{session_id}/export`：把本机或导入 Session 导出为 `.mbsession` 文件。
- `GET /api/v1/interfaces`：读取 Current Session 中由 Breakpoint 与调用派生的 Interface；`view=current` 保留本轮已观察、存在启用断点或存在 Pause 的当前相关项。
- `GET /api/v1/interfaces/detail?object=...&command=...`：精确读取最新完整字段结构、`schema_changed`、`last_seen_at` 和样本引用。
- `GET /api/v1/breakpoints`、`GET /api/v1/breakpoints/{breakpoint_id}`：读取 Current Session 的持久规则。
- `POST /api/v1/breakpoints`：创建默认启用的 before/after Breakpoint；`conditions` 为空表示接口规则，非空条件支持 `eq` 与 `contains_any`。完整规范定义相同时返回已有对象，已屏蔽规则不会自动启用；响应始终把本次 before 归一化丢弃的完整条件放在 `discarded_conditions`，不把它们写入规则身份。
- `PATCH /api/v1/breakpoints/{breakpoint_id}`：Web 局部修改名称、目标、暂停点和条件并保留原 ID；响应同样始终包含本次写入的 `discarded_conditions`。
- `POST /api/v1/breakpoints/{breakpoint_id}/enable`、`POST /api/v1/breakpoints/{breakpoint_id}/disable`：启用或屏蔽规则。
- `DELETE /api/v1/breakpoints/{breakpoint_id}`：删除规则；历史 Pause 的命中快照不受影响。
- `DELETE /api/v1/breakpoints`：在排他控制门内以单次事务删除 Current Session 全部规则并返回 `deleted_count`。
- `GET /api/v1/interactions`：分页读取 Current Session 的轻量调用摘要，支持 `page`、最大为 100 的 `size`、`query`、`object`、`command`、`status`、`pause_point`、`from` 和 `to` 筛选，返回 `total`、`session_total`、`paused_total` 与 `total_pages`，并优先返回暂停项。`GET /api/v1/interactions/{interaction_id}` 按需读取完整阶段时间线、原始 params、result、Pause 历史、当前有效内容、条件命中证据、注入审计以及 payload 捕获大小。
- `POST /api/v1/interactions/{interaction_id}/inject`：对当前 `pause_point` 提交嵌套 `changes` 部分对象；稳定返回 `modified`、`unchanged`、分类 `skipped`、`applied/partial/no_effect` 和当前有效内容，注入后仍保持暂停。
- `POST /api/v1/interactions/{interaction_id}/continue`：按请求体中的 `pause_point` 继续当前 Pause，不暴露底层 `pause_id`；已继续、超时或安全释放时返回 `already_resolved` 和原释放信息。
- `POST /api/v1/interactions/continue-selected`：仅供 Web 按非空 `targets=[{interaction_id,pause_point}]` 原子继续明确勾选且无待提交注入的活动 Pause；任一目标失效、阶段变化或需要复核时整批不执行。
- `POST /api/v1/interactions/continue`：无参数原子继续命令开始时 Current Session 的全部 Pause 快照；命令期间新增 Pause 不纳入本次操作，没有暂停项时 `continued_count` 为 0。
- `GET /api/v1/control`：读取脱敏控制摘要和当前请求方是否持有控制。
- `POST /api/v1/debugging/start`：首次写入时自动取得控制，只有真实 Demo 返回完整建租约 ACK 后才启动 Current Session 调试；重复调用幂等。
- `POST /api/v1/debugging/stop`：停止调试但保留当前控制权，便于继续操作同一 Session。
- `POST /api/v1/control/heartbeat`：Web 内部心跳；只有当前持有实例可以续租。
- `POST /api/v1/control/release`：安全停止调试并主动释放控制权。

业务接入密钥只允许访问以下三个契约：

- `POST /api/business/interactions/before`：上报 `interaction_id`、`object`、`command` 和完整 `params` 对象。
- `POST /api/business/interactions/wait`：按 `interaction_id` 和 `pause_point` 等待放行；未命中时立即返回 `not_paused`，命中时阻塞到继续、超时或安全释放，并以 `content_kind + content` 返回业务应实际使用的 params 或 result。
- `POST /api/business/interactions/after`：用同一 `interaction_id` 上报业务 `result`，值允许为 JSON `null`；after Breakpoint 按每条条件的 source 对原始 params 与本次原始成功 result 做 AND 匹配，命中时返回 `wait_required=true`。业务异常不会进入正常 after 上报，也不能命中 result 条件。

相同内容的 before 或 after 可以安全重试；同一 `interaction_id` 改变目标、params 或 result 会返回 `INTERACTION_REPORT_CONFLICT`。业务接入代码必须把产品不可达、超时或 5xx 当作 `proceed`，不得让调试产品故障破坏原业务调用。

Web 管理会话或配置的 Gateway Bearer 密钥可以读取产品状态 API；Session 列表与管理 API 只提供给 Web。业务接入 Bearer 密钥不能读取这些管理信息。

`.mbsession` 的顶层格式固定为 `breakhub-session-v1`，包含 Session 元数据、来源装备摘要、Breakpoint、Interaction、Pause、捕获 payload 与注入/继续审计。导出与导入的断点、命中快照条件都必须显式包含 `source`，命中快照还完整保存并校验 `condition_evidence`；不提供缺少 `source` 的旧归档兼容路径。文件不会携带 Current 标记、控制租约、调试状态、服务 URL、密钥、账号、数据库路径或产品配置，也不兼容旧 `.mbrec`。导入会先校验完整文件，任一结构错误都会拒绝整个文件且不创建残留 Session；导入证据只能查看、再次导出或删除。

Gateway 的 HTTP 适配器在写请求和需要续租的读取请求中使用 `X-MBP-Control-Instance` 标识自身实例。该值不是租约凭证，不进入 Agent 工具参数或对话；Web 直接使用登录会话身份，并每 5 分钟在页面内部续租。

当前 Agent-facing Gateway 已公开装备连接、调试启动以及 Interface、Breakpoint、Interaction 的 18 个工具。`find_interfaces`、`find_breakpoints` 与 `find_interactions` 始终查询 Current Session，每页最多 50 条，并使用签名 cursor 绑定 Session 与过滤条件；Interaction 列表只返回紧凑摘要，`get_interaction` 返回时间线、原始与当前有效内容、结果、含 `condition_evidence` 的不可变命中快照、注入与继续审计和 payload 截断元数据。单个 Agent 证据值超过 64 KiB 时，Gateway 只在输出投影中返回合法 JSON preview，并以 `truncated`、`original_size_bytes` 和 `captured_size_bytes` 明确边界；条件证据仍保留来源、路径和操作符，产品内的完整持久化证据不变。超过产品 16 MB 业务上报上限的请求仍会拒绝捕获并由业务接入 fail-open。Interaction 写工具只接受 `interaction_id + pause_point` 和嵌套 `changes`；批量继续不接受 ID 列表，并原子处理命令开始快照中的全部 Pause。全量删除和全量继续都标记为危险动作，调用前必须展示数量与影响并取得用户确认。Gateway 从自己的装备注册 JSON 读取产品地址与 `gateway_token`，同一可信 MCP 会话的控制实例由 Gateway 隐式生成。Web 持有控制时 MCP 仍可查询，但所有写操作都会返回 `CONTROLLED_BY_WEB`。

## 故障诊断

- 启动即退出并提示旧表：当前产品拒绝包含 `debug_cycle`、`bp_rule` 等旧结构的数据库。保留旧文件作证据，改用新的空数据目录；产品不会迁移或删除旧数据。
- “产品后端在线”但开始调试失败：确认 18622 Java Demo 已启动，并检查 `debugger-switch.url` 是否为唯一入口 `/api/demo/debugger/enabled`。冲突、无效 ACK、不可达或超过 5 秒都会保持产品空闲并返回可区分错误；本阶段没有 switch Bearer 密钥或可调超时。
- Web 显示“业务服务续签异常”：产品后端仍在线，但最近一次对 Demo 的续签失败；在最后成功确认后的 30 秒截止前恢复 Demo 可继续续签，否则产品会安全释放 Pause 并显示租约已失效。该收尾不会抢走原 Control Lease，原控制方可以继续排障或重新开始调试。
- “连接拒绝”只能验证不可达，不能替代 HTTP 黑洞；5 秒请求硬超时和最后 ACK 后 30 秒的本地到期边界必须使用 accept-but-never-respond 故障服务另行验证。
- Web 显示“当前由 MCP 控制”：这是排他控制的正常状态。Web 仍可查看，但不能抢占；让当前 MCP 会话调用 `disconnect_equipment`，或等待租约失效后再操作。
- MCP 返回 `CONTROLLED_BY_WEB`：Web 当前持有控制。MCP 仍可调用只读工具；由 Web 主动“释放控制”后再重试写工具。
- 无法切换 Current Session：先停止调试；仍有 Pause 时先继续或通过停止、释放等安全路径结束 Pause。导入 Session 永远只读，不能设为 Current。
- `.mbsession` 导入失败：只接受完整的 `breakhub-session-v1` JSON，不兼容旧 `.mbrec`。任一结构错误都会原子拒绝，不会留下半导入 Session。
- 修改配置未生效：产品只读取显式配置文件。修改 `application.yml` 后重启；环境变量、JVM 属性和普通命令行参数不会覆盖产品配置。
- 需要定位运行状态：先看 Web 的“概览”和只读“设置”页；接口排查可在完成 Web 登录后请求 `GET /api/v1/overview`。设置页只显示密钥是否已配置，不回显密钥内容。

## 验证

```powershell
cd I:\ai\cc\breakhub\bp-hub\web
npm test
npm run build

cd I:\ai\cc\breakhub\bp-hub
mvn test
mvn clean package
```

产品测试从公开 HTTP 和真实启动边界验证认证、Current Session、排他控制、Reporting Lease 完整 ACK 门、固定延迟续签、同 ID 停止、应用关闭与 ID 防泄漏，以及业务 before/wait/after、并发重试冲突、Breakpoint 生命周期、数值精度与严格类型条件、`contains_any`、混合来源、多规则单 Pause、after 原始入参与出参匹配、直接注入的字段与类型边界、多次累积和审计、单条继续幂等性、批量快照边界与原子失败、全部安全丢弃原因、不可变命中快照、Interface 字段结构变化、跨 Session 隔离、配置文件优先级和旧数据库拒绝启动。
