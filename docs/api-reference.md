# BreakHub HTTP API 参考文档（bp-hub）

本文档描述当前 bp-hub 源码实际暴露的 HTTP JSON 接口。这里的“公开接口”是指可由 bp-hub 进程外部调用的应用接口，不等于“无需鉴权”。

## 1. 范围与统计口径

- 当前共有 **41 条显式 HTTP 映射**：3 条认证接口、3 条业务上报接口、35 条 /api/v1 管理接口。映射入口集中在 8 个 RestController 中，见 [api 源码目录](../bp-hub/src/main/java/com/ateagents/breakhub/api/)。
- 按 HTTP 方法统计为：GET 16 条、POST 20 条、PATCH 2 条、DELETE 3 条；当前没有显式 PUT 映射。
- 不统计静态页面 /、/index.html、/assets/**，也不统计 Spring 隐式提供的 HEAD/OPTIONS、框架错误入口 /error。
- /api/health 只出现在安全白名单中，当前没有 Controller Handler，实际请求为 404，因此不计入接口总数。白名单位置见 [SecurityConfiguration.java:98](../bp-hub/src/main/java/com/ateagents/breakhub/config/SecurityConfiguration.java#L98)。
- 当前没有 WebSocket 或 SSE 接口。POST /api/business/interactions/wait 是普通的阻塞式 HTTP JSON 请求，服务端轮询等待 Pause 释放，见 [PauseService.java:73](../bp-hub/src/main/java/com/ateagents/breakhub/domain/PauseService.java#L73)。
- 旧版 /api/v1/debug-cycle/current、/api/v1/debug-cycles、/api/v1/bp-rules 已明确返回 404，不属于当前契约，见 [ProductBaselineApiTest.java:116](../bp-hub/src/test/java/com/ateagents/breakhub/ProductBaselineApiTest.java#L116)。

## 2. 通用约定

### 2.1 基础地址与数据格式

- 基础地址由 server.address 和 server.port 配置，例如 http://127.0.0.1:18621。
- 除 POST /api/auth/logout 成功时返回 204 且无响应体外，成功响应体均为直接 JSON；Session export 的 Body 仍是 application/json，只是额外带 attachment 下载 Header。带请求体的接口应发送 Content-Type: application/json。
- JSON 字段统一使用 snake_case。
- 时间字段是 ISO-8601 Instant 字符串，例如 2026-08-25T08:00:00Z。
- JSON 整数和小数在反序列化时使用任意精度类型，避免大整数及高精度小数丢失，见 [JsonConfiguration.java:12](../bp-hub/src/main/java/com/ateagents/breakhub/config/JsonConfiguration.java#L12)。
- 除明确返回 201 或 204 的接口外，成功状态通常为 200。
- 本文 JSON/HTTP 片段中的地址、端口、账号、装备 ID、Session ID、业务对象名、时间和容量均为结构示例，不代表默认值或当前部署的真实配置；实际值以 application.yml 和运行时数据为准。

### 2.2 调用方与鉴权

| 调用方 | 凭证 | 可访问范围 |
|---|---|---|
| 未登录调用方 | 无 | 仅 POST /api/auth/login |
| Web 管理员 | JSESSIONID Cookie | 管理 API；SessionController 全部接口仅 Web 可用 |
| Gateway | Authorization: Bearer {gateway_token} | 除 Web-only、Business-only 和 continue-selected 外的管理 API |
| Business Client | Authorization: Bearer {business_client_token} | 仅 /api/business/** 三条上报接口 |

Bearer 过滤器使用独立的 Gateway 和 Business 角色，且 Bearer 请求无状态、禁用 CSRF；浏览器链使用管理员 Session 和 CSRF。授权规则见 [SecurityConfiguration.java:49](../bp-hub/src/main/java/com/ateagents/breakhub/config/SecurityConfiguration.java#L49)，Token 比对与角色赋予见 [ApiTokenAuthenticationFilter.java:30](../bp-hub/src/main/java/com/ateagents/breakhub/config/ApiTokenAuthenticationFilter.java#L30)。

Web 登录后的所有非安全方法（POST、PUT、PATCH、DELETE 等，登录接口除外）必须同时发送：

~~~http
Cookie: JSESSIONID=...; MBP-XSRF-TOKEN=...
X-MBP-XSRF-TOKEN: <GET /api/auth/session 返回的 csrf_token>
~~~

CSRF Cookie 和 Header 名由 [SecurityConfiguration.java:80](../bp-hub/src/main/java/com/ateagents/breakhub/config/SecurityConfiguration.java#L80) 定义。

Gateway 的管理写请求还必须发送一个非空且不超过 128 字符的实例标识：

~~~http
Authorization: Bearer <gateway_token>
X-MBP-Control-Instance: <稳定的 Gateway 实例 ID>
~~~

该 Header 用于识别排他控制方，不是租约密钥。缺失或无效时写请求返回 400 CONTROL_INSTANCE_REQUIRED，见 [ControlIdentityResolver.java:18](../bp-hub/src/main/java/com/ateagents/breakhub/api/ControlIdentityResolver.java#L18)。

成功完成的 /api/** GET 或 HEAD 会尝试为当前请求身份续租，但只在该身份本来就是控制方时生效，不会因读取而抢占控制：

- Web Session 会自动解析成 Web 实例；
- Gateway 读取若希望识别为同一控制实例并续租，需要同时带 X-MBP-Control-Instance；
- Business Client 不解析成控制实例。

该副作用由 [ControlRequestConfiguration.java:25](../bp-hub/src/main/java/com/ateagents/breakhub/config/ControlRequestConfiguration.java#L25) 和 [DebugControlService.java:281](../bp-hub/src/main/java/com/ateagents/breakhub/domain/DebugControlService.java#L281) 实现。

### 2.3 排他控制

Breakpoint、Session、Pause、调试状态等管理写操作在统一控制门内执行：

- 除 heartbeat（仅续租已有控制）和 release（无人控制时幂等返回）外，无控制方时首次成功的管理写操作自动取得控制；
- 同一实例写入会续租；
- 其他实例写入返回 409 CONTROLLED_BY_WEB 或 CONTROLLED_BY_MCP；
- Web 身份由 JSESSIONID 对应的 Session ID 区分；
- Gateway 身份由 X-MBP-Control-Instance 区分。

控制取得、续租和冲突逻辑见 [DebugControlService.java:237](../bp-hub/src/main/java/com/ateagents/breakhub/domain/DebugControlService.java#L237) 与 [DebugControlService.java:368](../bp-hub/src/main/java/com/ateagents/breakhub/domain/DebugControlService.java#L368)。

### 2.4 标准错误

产品显式抛出的业务错误统一返回：

~~~json
{
  "code": "ERROR_CODE",
  "message": "可读错误说明"
}
~~~

映射实现见 [ApiExceptionHandler.java:9](../bp-hub/src/main/java/com/ateagents/breakhub/api/ApiExceptionHandler.java#L9)。

通用认证错误：

| HTTP | code | 条件 |
|---:|---|---|
| 401 | INVALID_CREDENTIALS | Web 用户名或密码错误 |
| 401 | INVALID_TOKEN | Bearer Token 无效 |
| 401 | UNAUTHENTICATED | 未登录且未提供有效 Bearer Token |
| 403 | FORBIDDEN | 角色不允许访问，或 Web 写请求 CSRF 校验失败 |
| 400 | CONTROL_INSTANCE_REQUIRED | Gateway 管理写请求缺少有效实例 Header |
| 409 | CONTROLLED_BY_WEB | 当前排他控制属于另一个 Web 实例 |
| 409 | CONTROLLED_BY_MCP | 当前排他控制属于另一个 Gateway 实例 |
| 503 | PRODUCT_SHUTTING_DOWN | 产品正在关闭，拒绝新写入 |

参数无法反序列化、缺少必填请求体、媒体类型不支持、路由或方法不存在等 Spring MVC 框架错误，不经过 ProductException 处理器；调用方不应依赖其响应体与上述二字段格式一致。

## 3. 接口总览

“Web/Gateway”表示两者均可读取；带“控制”的写操作还受 2.3 节约束。所有 SessionController 路由均为 Web-only，代码门禁见 [SessionController.java:147](../bp-hub/src/main/java/com/ateagents/breakhub/api/SessionController.java#L147)。continue-selected 也在安全链中被限制为 Web-only，见 [SecurityConfiguration.java:63](../bp-hub/src/main/java/com/ateagents/breakhub/config/SecurityConfiguration.java#L63)。

| # | 方法 | 路径 | 调用方 | 成功 | 功能 |
|---:|---|---|---|---:|---|
| 1 | POST | /api/auth/login | 未登录/Web | 200 | 建立 Web 管理 Session |
| 2 | GET | /api/auth/session | Web | 200 | 读取登录身份和 CSRF Token |
| 3 | POST | /api/auth/logout | Web | 204 | 释放本 Web 控制并退出 |
| 4 | GET | /api/v1/overview | Web/Gateway | 200 | 产品、装备、当前 Session、控制与健康总览 |
| 5 | GET | /api/v1/sessions/current | Web/Gateway | 200 | 读取 Current Session |
| 6 | GET | /api/v1/settings | Web/Gateway | 200 | 读取脱敏生效配置 |
| 7 | GET | /api/v1/equipment | Web/Gateway | 200 | 读取装备摘要 |
| 8 | GET | /api/v1/sessions | Web | 200 | 列出本机和导入 Session |
| 9 | GET | /api/v1/sessions/{session_id} | Web | 200 | 读取一个 Session 摘要 |
| 10 | POST | /api/v1/sessions | Web/控制 | 201 | 创建本机 Session |
| 11 | POST | /api/v1/sessions/import | Web/控制 | 201 | 导入 .mbsession JSON |
| 12 | POST | /api/v1/sessions/current/interactions/clear | Web/控制 | 200 | 清空当前调用证据，保留断点 |
| 13 | GET | /api/v1/sessions/{session_id}/archive | Web | 200 | 浏览完整 Session 归档 |
| 14 | GET | /api/v1/sessions/{session_id}/export | Web | 200 | 下载 .mbsession |
| 15 | PATCH | /api/v1/sessions/{session_id} | Web/控制 | 200 | 重命名本机 Session |
| 16 | POST | /api/v1/sessions/{session_id}/current | Web/控制 | 200 | 切换 Current Session |
| 17 | DELETE | /api/v1/sessions/{session_id} | Web/控制 | 200 | 删除非 Current Session |
| 18 | GET | /api/v1/interfaces | Web/Gateway | 200 | 列出 Current Session 的 Interface |
| 19 | GET | /api/v1/interfaces/detail | Web/Gateway | 200 | 精确读取 Interface 详情 |
| 20 | GET | /api/v1/breakpoints | Web/Gateway | 200 | 列出 Current Session 的 Breakpoint |
| 21 | GET | /api/v1/breakpoints/{breakpoint_id} | Web/Gateway | 200 | 读取一个 Breakpoint |
| 22 | POST | /api/v1/breakpoints | Web/Gateway/控制 | 200 | 创建或复用等价 Breakpoint |
| 23 | PATCH | /api/v1/breakpoints/{breakpoint_id} | Web/Gateway/控制 | 200 | 局部修改 Breakpoint |
| 24 | POST | /api/v1/breakpoints/{breakpoint_id}/enable | Web/Gateway/控制 | 200 | 启用 Breakpoint |
| 25 | POST | /api/v1/breakpoints/{breakpoint_id}/disable | Web/Gateway/控制 | 200 | 屏蔽 Breakpoint |
| 26 | DELETE | /api/v1/breakpoints/{breakpoint_id} | Web/Gateway/控制 | 200 | 幂等删除 Breakpoint |
| 27 | DELETE | /api/v1/breakpoints | Web/Gateway/控制 | 200 | 删除 Current Session 全部 Breakpoint |
| 28 | GET | /api/v1/interactions | Web/Gateway | 200 | 筛选、分页读取调用摘要 |
| 29 | GET | /api/v1/interactions/{interaction_id} | Web/Gateway | 200 | 读取完整调用证据 |
| 30 | POST | /api/v1/interactions/{interaction_id}/continue | Web/Gateway/控制 | 200 | 继续单个 Pause |
| 31 | POST | /api/v1/interactions/continue-selected | Web/控制 | 200 | 原子继续明确选择的 Pause |
| 32 | POST | /api/v1/interactions/continue | Web/Gateway/控制 | 200 | 原子继续当前全部 Pause |
| 33 | POST | /api/v1/interactions/{interaction_id}/inject | Web/Gateway/控制 | 200 | 向当前 Pause 注入字段变更 |
| 34 | GET | /api/v1/control | Web/Gateway | 200 | 读取脱敏控制摘要 |
| 35 | POST | /api/v1/control/heartbeat | Web/Gateway/控制 | 200 | 当前控制实例续租 |
| 36 | POST | /api/v1/control/release | Web/Gateway/控制 | 200 | 停止调试并释放控制 |
| 37 | POST | /api/v1/debugging/start | Web/Gateway/控制 | 200 | 建立 Reporting Lease 并开始调试 |
| 38 | POST | /api/v1/debugging/stop | Web/Gateway/控制 | 200 | 停止调试但保留控制 |
| 39 | POST | /api/business/interactions/before | Business | 200 | 上报调用前参数并判断是否暂停 |
| 40 | POST | /api/business/interactions/after | Business | 200 | 上报成功结果并判断是否暂停 |
| 41 | POST | /api/business/interactions/wait | Business | 200 | 阻塞等待 Pause 放行 |

## 4. 认证接口

映射及请求、响应实现见 [AuthController.java:46](../bp-hub/src/main/java/com/ateagents/breakhub/api/AuthController.java#L46)。

### 4.1 POST /api/auth/login

功能：校验管理员账号并建立服务器端 Web Session。该接口不要求 CSRF。

请求：

~~~json
{
  "username": "admin",
  "password": "secret"
}
~~~

成功 200：

~~~json
{
  "authenticated": true,
  "username": "admin"
}
~~~

同时由容器建立 JSESSIONID Cookie。凭证错误返回 401 INVALID_CREDENTIALS。

### 4.2 GET /api/auth/session

功能：读取当前管理员身份并生成/返回 CSRF Token。

请求：只需已登录的 JSESSIONID Cookie。

成功 200：

~~~json
{
  "authenticated": true,
  "username": "admin",
  "csrf_token": "..."
}
~~~

响应还设置可由浏览器读取的 MBP-XSRF-TOKEN Cookie。该流程由认证测试覆盖，见 [AuthenticationApiTest.java:67](../bp-hub/src/test/java/com/ateagents/breakhub/AuthenticationApiTest.java#L67)。

### 4.3 POST /api/auth/logout

功能：若当前 Web Session 持有控制，则安全停止调试并释放控制；随后使 Session 失效。

请求：JSESSIONID、MBP-XSRF-TOKEN Cookie 及 X-MBP-XSRF-TOKEN Header；无请求体。

成功：204 No Content。

## 5. 产品、装备与生效配置

### 5.1 GET /api/v1/overview

功能：一次读取产品、装备、Current Session、连接、调试、Reporting、控制和数据库健康状态。组装逻辑见 [ProductOverviewController.java:50](../bp-hub/src/main/java/com/ateagents/breakhub/api/ProductOverviewController.java#L50)。

请求：无查询参数或请求体。

成功 200：

~~~json
{
  "product": {
    "name": "BreakHub",
    "version": "<build-version 或 development>"
  },
  "equipment": {
    "equipment_id": "equipment-01",
    "display_name": "一号装备"
  },
  "current_session": {
    "session_id": "uuid",
    "name": "默认 Session",
    "source": "local",
    "read_only": false,
    "current": true,
    "created_at": "2026-08-25T08:00:00Z",
    "updated_at": "2026-08-25T08:00:00Z"
  },
  "connection": {
    "status": "healthy",
    "label": "产品后端在线"
  },
  "debugging": {
    "status": "idle",
    "session_id": "uuid",
    "reporting": {
      "status": "idle"
    }
  },
  "control": {
    "held": false,
    "controller": "none",
    "owned_by_requester": false
  },
  "health": {
    "status": "healthy",
    "database": "healthy"
  }
}
~~~

动态字段：

- debugging.status 为 idle 或 debugging；调试中额外有 started_at。
- debugging.reporting.status 为 idle、healthy、degraded 或 expired；可能带 channel_status、last_confirmed_at、server_deadline_at、last_error，但从不返回 Reporting Lease ID。状态定义见 [ReportingLeaseCoordinator.java:239](../bp-hub/src/main/java/com/ateagents/breakhub/domain/ReportingLeaseCoordinator.java#L239)。
- control.held=true 时还包含 controller=web|mcp、owned_by_requester 和 expires_at。

### 5.2 GET /api/v1/sessions/current

功能：读取唯一 Current Session。

成功 200：直接返回 Session 对象：

~~~json
{
  "session_id": "uuid",
  "name": "默认 Session",
  "source": "local",
  "read_only": false,
  "current": true,
  "created_at": "2026-08-25T08:00:00Z",
  "updated_at": "2026-08-25T08:00:00Z"
}
~~~

Session 投影定义见 [ProductOverviewController.java:122](../bp-hub/src/main/java/com/ateagents/breakhub/api/ProductOverviewController.java#L122)。

### 5.3 GET /api/v1/settings

功能：读取当前生效但经过脱敏的只读配置和诊断。实际密钥、密码不会回显。

成功 200：

~~~json
{
  "configuration_source": "file",
  "restart_required": true,
  "server": {
    "address": "127.0.0.1",
    "port": 18621
  },
  "equipment": {
    "equipment_id": "equipment-01",
    "display_name": "一号装备",
    "debugger_switch": {
      "url": "http://127.0.0.1:18622/api/demo/debugger/enabled"
    }
  },
  "security": {
    "web_username": "admin",
    "web_password": "configured",
    "gateway_token": "configured",
    "business_client_token": "configured"
  },
  "limits": {
    "control_lease_timeout_seconds": 1800,
    "pause_timeout_seconds": 1500,
    "max_payload_bytes": 16777216
  },
  "storage": {
    "data_directory": "..."
  },
  "health": {
    "status": "healthy",
    "database": "healthy",
    "debugger_switch": "configured"
  }
}
~~~

完整字段由 [ProductOverviewController.java:75](../bp-hub/src/main/java/com/ateagents/breakhub/api/ProductOverviewController.java#L75) 定义。

### 5.4 GET /api/v1/equipment

功能：读取本实例绑定的装备摘要。

成功 200：

~~~json
{
  "equipment_id": "equipment-01",
  "display_name": "一号装备"
}
~~~

映射见 [EquipmentController.java:21](../bp-hub/src/main/java/com/ateagents/breakhub/api/EquipmentController.java#L21)。

## 6. Session 管理

本节 10 条接口全部仅支持 Web 管理 Session。即使 Gateway Token 有权进入 /api/v1，也会由 Controller 返回 403 WEB_SESSION_MANAGEMENT_ONLY。

### 6.1 Session 对象

| 字段 | 类型 | 说明 |
|---|---|---|
| session_id | string | 本产品内 Session ID |
| name | string | 1 到 120 字符 |
| source | local 或 imported | 本机创建或归档导入 |
| read_only | boolean | imported 固定为 true |
| current | boolean | 是否为唯一 Current Session |
| created_at | ISO-8601 string | 创建时间 |
| updated_at | ISO-8601 string | 最后更新时间 |

本机 Session 的约束与错误定义见 [CurrentSessionService.java:54](../bp-hub/src/main/java/com/ateagents/breakhub/domain/CurrentSessionService.java#L54)。

### 6.2 GET /api/v1/sessions

功能：列出全部 Session；Current Session 排在首位，其余按更新时间倒序。

成功 200：

~~~json
{
  "current_session_id": "uuid-current",
  "items": [
    {
      "session_id": "uuid-current",
      "name": "默认 Session",
      "source": "local",
      "read_only": false,
      "current": true,
      "created_at": "...",
      "updated_at": "..."
    }
  ]
}
~~~

### 6.3 GET /api/v1/sessions/{session_id}

功能：读取指定 Session 摘要。

成功 200：Session 对象。不存在时返回 404 SESSION_NOT_FOUND。

### 6.4 POST /api/v1/sessions

功能：创建非 Current 的可写本机 Session。

请求：

~~~json
{
  "name": "版本 A"
}
~~~

name 去除首尾空白后必须为 1 到 120 字符。

成功 201：新 Session 对象，source=local、read_only=false、current=false。创建接口状态码见 [SessionController.java:69](../bp-hub/src/main/java/com/ateagents/breakhub/api/SessionController.java#L69)。

错误：400 INVALID_SESSION_NAME；控制冲突错误。

### 6.5 POST /api/v1/sessions/import

功能：原子校验并导入完整 breakhub-session-v1 JSON；它不是 multipart 上传。

请求：6.8 节定义的完整归档对象。

成功 201：新建的本地 Session 摘要；生成新的 session_id，source=imported、read_only=true、current=false。原归档内容保持不变，因此之后浏览/导出的归档内 session.session_id 仍是来源归档 ID。

错误：

| HTTP | code | 条件 |
|---:|---|---|
| 400 | UNSUPPORTED_SESSION_ARCHIVE | format 不是 breakhub-session-v1 |
| 400 | INVALID_SESSION_ARCHIVE | 字段、类型、引用、状态或审计证据不满足完整校验 |

校验先于 Session 创建，因此失败不会留下半导入数据；实现见 [SessionArchiveService.java:87](../bp-hub/src/main/java/com/ateagents/breakhub/domain/SessionArchiveService.java#L87) 和 [SessionArchiveService.java:223](../bp-hub/src/main/java/com/ateagents/breakhub/domain/SessionArchiveService.java#L223)。

### 6.6 POST /api/v1/sessions/current/interactions/clear

功能：删除 Current Session 的全部 Interaction、Pause、注入和继续审计，保留全部 Breakpoint。

请求：无请求体。

成功 200：

~~~json
{
  "session_id": "uuid",
  "cleared_interaction_count": 12,
  "cleared_pause_count": 4,
  "retained_breakpoint_count": 3
}
~~~

仍有 status=paused 的 Pause 时返回 409 SESSION_HAS_PAUSED_INTERACTIONS。事务与计数字段见 [CurrentSessionService.java:83](../bp-hub/src/main/java/com/ateagents/breakhub/domain/CurrentSessionService.java#L83)。

### 6.7 GET /api/v1/sessions/{session_id}/archive

功能：以 JSON 响应浏览完整归档证据。

成功 200：6.8 节归档对象。不存在时返回 404 SESSION_NOT_FOUND。

### 6.8 GET /api/v1/sessions/{session_id}/export

功能：下载与 archive 相同的完整 JSON。

成功 200：

- Content-Type: application/json
- Content-Disposition: attachment; filename="{session_id}.mbsession"
- Body：以下结构

~~~json
{
  "format": "breakhub-session-v1",
  "exported_at": "2026-08-25T08:00:00Z",
  "session": {
    "session_id": "uuid",
    "name": "版本 A",
    "created_at": "...",
    "updated_at": "..."
  },
  "source_equipment": {
    "equipment_id": "equipment-01",
    "display_name": "一号装备"
  },
  "breakpoints": [],
  "interactions": [],
  "pauses": []
}
~~~

顶层和各嵌套对象使用精确字段集合；归档不会包含 Current 标记、控制租约、调试状态、服务密钥或产品配置。顶层及嵌套字段集合见 [SessionArchiveService.java:28](../bp-hub/src/main/java/com/ateagents/breakhub/domain/SessionArchiveService.java#L28)，导出 Header 见 [SessionController.java:98](../bp-hub/src/main/java/com/ateagents/breakhub/api/SessionController.java#L98)。

breakpoints 每项字段：

~~~text
breakpoint_id, name, object, command, pause_point, enabled,
conditions, hit_count, last_hit_at, created_at, updated_at
~~~

interactions 每项字段：

~~~text
interaction_id, object, command, params, field_schema, schema_changed,
lifecycle, before_at, after_at, result, updated_at
~~~

pauses 每项字段：

~~~text
interaction_id, pause_point, status, breakpoint_snapshots, content_kind,
original_content, effective_content, injection_audit, injection_status,
paused_at, resolved_at, resolution, released_content
~~~

### 6.9 PATCH /api/v1/sessions/{session_id}

功能：重命名本机 Session。

请求：

~~~json
{
  "name": "版本 A · 已整理"
}
~~~

成功 200：更新后的 Session 对象。

错误：400 INVALID_SESSION_NAME；404 SESSION_NOT_FOUND；409 IMPORTED_SESSION_READ_ONLY。

### 6.10 POST /api/v1/sessions/{session_id}/current

功能：把指定本机 Session 设为唯一 Current Session。重复选择当前项是幂等成功。

请求：无请求体。

成功 200：被选中的 Session 对象，current=true。

错误：

- 404 SESSION_NOT_FOUND；
- 409 IMPORTED_SESSION_READ_ONLY；
- 409 SESSION_SWITCH_WHILE_DEBUGGING：调试运行或正在开始调试时禁止切换。

切换前置门见 [SessionController.java:121](../bp-hub/src/main/java/com/ateagents/breakhub/api/SessionController.java#L121)。

### 6.11 DELETE /api/v1/sessions/{session_id}

功能：删除非 Current Session 及其 Breakpoint、Interaction、Pause 和归档。

成功 200：

~~~json
{
  "deleted": true,
  "session_id": "uuid"
}
~~~

错误：404 SESSION_NOT_FOUND；409 CURRENT_SESSION_DELETE_FORBIDDEN。

## 7. Interface 查询

映射见 [ObservationController.java:50](../bp-hub/src/main/java/com/ateagents/breakhub/api/ObservationController.java#L50)，聚合规则见 [InteractionObservationService.java:191](../bp-hub/src/main/java/com/ateagents/breakhub/domain/InteractionObservationService.java#L191)。

### 7.1 GET /api/v1/interfaces

功能：从 Current Session 的真实 Interaction 和 Breakpoint 派生 Interface 列表。field_schema 只从该 Interface 最近一次完整 params 样本生成，不包含 result 字段结构。

查询参数：

| 参数 | 必填 | 默认 | 允许值 | 说明 |
|---|---|---|---|---|
| view | 否 | all | all、current | current 只保留本轮观察到、存在启用断点或存在活动 Pause 的项 |

成功 200：

~~~json
{
  "current_session_id": "uuid",
  "view": "all",
  "items": [
    {
      "object": "OrderService",
      "command": "create",
      "current_related": true,
      "field_schema": [
        {"path": "customer", "type": "object"},
        {"path": "customer.id", "type": "string"},
        {"path": "tags", "type": "array", "item_types": ["string"]}
      ],
      "schema_changed": false,
      "last_seen_at": "...",
      "sample_ref": {
        "interaction_id": "call-001",
        "content": "params"
      },
      "interaction_count": 4,
      "breakpoint_count": 2,
      "enabled_breakpoint_count": 1,
      "breakpoints": [
        {
          "breakpoint_id": "uuid",
          "name": "...",
          "pause_point": "before",
          "enabled": true
        }
      ]
    }
  ]
}
~~~

只有 Breakpoint、尚无调用样本的 Interface，其 field_schema=[]、schema_changed=false、last_seen_at=null、sample_ref=null。字段结构生成规则见 [InteractionFieldSchema.java:24](../bp-hub/src/main/java/com/ateagents/breakhub/domain/InteractionFieldSchema.java#L24)。

错误：400 INVALID_INTERFACE_VIEW。

### 7.2 GET /api/v1/interfaces/detail

功能：按 object + command 精确读取与列表项相同的完整 Interface 投影。

查询参数：

| 参数 | 必填 | 约束 |
|---|---|---|
| object | 是 | 去空白后 1 到 200 字符 |
| command | 是 | 去空白后 1 到 200 字符 |

成功 200：单个 Interface 对象，不包含列表外壳。

错误：400 INVALID_INTERACTION_REPORT（参数格式无效）；404 INTERFACE_NOT_FOUND。

## 8. Breakpoint 管理

映射见 [BreakpointController.java:41](../bp-hub/src/main/java/com/ateagents/breakhub/api/BreakpointController.java#L41)，持久化投影见 [BreakpointService.java:271](../bp-hub/src/main/java/com/ateagents/breakhub/domain/BreakpointService.java#L271)。

### 8.1 Breakpoint 对象

~~~json
{
  "breakpoint_id": "uuid",
  "name": "检查用户状态",
  "object": "OrderService",
  "command": "create",
  "pause_point": "before",
  "enabled": true,
  "conditions": [
    {
      "source": "params",
      "field_path": "customer.status",
      "operator": "eq",
      "value": "blocked"
    }
  ],
  "hit_count": 0,
  "last_hit_at": null,
  "created_at": "...",
  "updated_at": "..."
}
~~~

条件规则：

| 字段 | 约束 |
|---|---|
| source | params 或 result，必填 |
| field_path | 1 到 500 字符的点分对象字段路径；分段不能留空或全为数字，不支持根值、数组索引、JSONPath、JSON Pointer 或通配符 |
| operator | eq 或 contains_any |
| value | eq 时为 JSON 标量/null；contains_any 时为非空 JSON 标量/null 数组 |

所有条件按 AND 匹配。空 conditions 表示无条件匹配目标 Interface。eq 严格区分 JSON 类型，但数字按任意精度数值相等；contains_any 要求实际字段是数组。归一化与校验见 [BreakpointConditionEngine.java:31](../bp-hub/src/main/java/com/ateagents/breakhub/domain/BreakpointConditionEngine.java#L31)。

before Breakpoint 中 source=result 的条件无法求值，会从实际规则中删除并在本次写响应的 discarded_conditions 完整返回；如果全部被删除，结果是无条件 before Breakpoint。见 [BreakpointService.java:230](../bp-hub/src/main/java/com/ateagents/breakhub/domain/BreakpointService.java#L230)。

### 8.2 GET /api/v1/breakpoints

功能：列出 Current Session 全部规则。

成功 200：

~~~json
{
  "current_session_id": "uuid",
  "items": []
}
~~~

### 8.3 GET /api/v1/breakpoints/{breakpoint_id}

功能：读取 Current Session 中的单条规则。

成功 200：Breakpoint 对象。

错误：400 INVALID_BREAKPOINT_ID；404 BREAKPOINT_NOT_FOUND。

### 8.4 POST /api/v1/breakpoints

功能：创建默认启用的 Breakpoint；若 Current Session 已存在完整规范定义相同的对象，则返回该对象而不重复创建，也不会自动启用已屏蔽的等价对象。

请求：

~~~json
{
  "name": "可选；空时自动生成",
  "object": "OrderService",
  "command": "create",
  "pause_point": "before",
  "conditions": []
}
~~~

object、command 去除首尾空白后必须为 1 到 200 字符，pause_point 必须为 before 或 after；name 可省略且最长 200 字符。conditions 省略时默认为空数组，显式 null 不是合法数组并返回 400 INVALID_BREAKPOINT_CONDITION。

成功 200：Breakpoint 对象外加：

~~~json
{
  "created": true,
  "discarded_conditions": []
}
~~~

created=false 表示复用了等价对象。

### 8.5 PATCH /api/v1/breakpoints/{breakpoint_id}

功能：局部修改规则并保留 breakpoint_id 和 enabled 状态。

请求：可包含 name、object、command、pause_point、conditions 的任意子集。客户端应省略未修改字段；显式 null 的处理来自当前内部合并逻辑，不属于稳定请求契约。

成功 200：更新后的 Breakpoint 对象外加 discarded_conditions；没有 created 字段。

### 8.6 POST .../{breakpoint_id}/enable 与 /disable

功能：幂等启用或屏蔽规则。

请求：无请求体。

成功 200：Breakpoint 对象外加 changed:boolean。目标状态已经满足时 changed=false。

错误：400 INVALID_BREAKPOINT_ID；404 BREAKPOINT_NOT_FOUND。

### 8.7 DELETE /api/v1/breakpoints/{breakpoint_id}

功能：幂等删除 Current Session 中的规则。历史 Pause 内的不可变命中快照不受影响。

成功 200：

~~~json
{
  "breakpoint_id": "uuid",
  "deleted": true,
  "result": "deleted"
}
~~~

规则已不存在时仍为 200，deleted=false、result=already_absent。

### 8.8 DELETE /api/v1/breakpoints

功能：在单次事务中删除 Current Session 全部规则。

成功 200：

~~~json
{
  "deleted_count": 3
}
~~~

### 8.9 Breakpoint 写入错误

| HTTP | code | 条件 |
|---:|---|---|
| 400 | INVALID_BREAKPOINT_ID | 路径 ID 为空或超过 200 字符 |
| 400 | INVALID_BREAKPOINT_DEFINITION | object/command 缺失、超长或字段类型错误 |
| 400 | INVALID_BREAKPOINT_PAUSE_POINT | pause_point 不是 before/after |
| 400 | INVALID_BREAKPOINT_NAME | name 超过 200 字符 |
| 400 | INVALID_BREAKPOINT_CONDITION | conditions 或任一条件不满足 8.1 节规范 |
| 404 | BREAKPOINT_NOT_FOUND | get、patch、enable、disable 的目标不在 Current Session |

## 9. Interaction 查询与 Pause 操作

### 9.1 GET /api/v1/interactions

功能：在 Current Session 中服务端筛选、分页读取轻量摘要。暂停项优先，其余按更新时间倒序。列表不会返回完整 params、result 或 timeline，见 [InteractionListService.java:28](../bp-hub/src/main/java/com/ateagents/breakhub/domain/InteractionListService.java#L28)。

查询参数：

| 参数 | 默认 | 约束/语义 |
|---|---|---|
| page | 0 | 大于等于 0 |
| size | 100 | 1 到 100 |
| query | 空 | interaction_id、object、command 的不区分大小写包含搜索；最长 200 |
| object | 空 | 精确匹配；最长 200 |
| command | 空 | 精确匹配；最长 200 |
| status | 空 | paused、in_progress、completed |
| pause_point | 空 | before、after；匹配任一历史 Pause |
| from | 空 | before_at 大于等于该 ISO-8601 Instant |
| to | 空 | before_at 小于等于该 ISO-8601 Instant |

解析与边界见 [InteractionListQuery.java:17](../bp-hub/src/main/java/com/ateagents/breakhub/domain/InteractionListQuery.java#L17)。未知查询参数当前会被忽略。

status=in_progress 会筛选 lifecycle=running 且当前未暂停的记录，但返回项的 status 值是 running。total 是应用全部筛选后的数量，session_total 是 Current Session 未筛选总数，paused_total 是 Current Session 当前活动 Pause 数量；pauses 只包含最近一条 Pause 的紧凑摘要，完整历史必须读取详情接口。

成功 200：

~~~json
{
  "current_session_id": "uuid",
  "items": [
    {
      "interaction_id": "call-001",
      "object": "OrderService",
      "command": "create",
      "lifecycle": "running",
      "phase": "before",
      "before_at": "...",
      "schema_changed": false,
      "status": "paused",
      "pause_count": 1,
      "hit_count": 2,
      "injection_count": 0,
      "payload_metadata": {
        "params": {"truncated": false}
      },
      "pauses": [
        {
          "pause_point": "before",
          "status": "paused",
          "breakpoint_snapshots": [{"name": "规则名"}]
        }
      ],
      "current_pause": {
        "pause_point": "before",
        "status": "paused",
        "injection_status": "none",
        "has_pending_injection": false,
        "paused_at": "...",
        "breakpoint_snapshots": [{"name": "规则名"}]
      }
    }
  ],
  "total": 1,
  "session_total": 12,
  "paused_total": 1,
  "page": 0,
  "size": 100,
  "total_pages": 1
}
~~~

after_at 仅完成 after 后出现；current_pause 仅活动暂停存在时出现。错误为 400 INVALID_INTERACTION_FILTER。

### 9.2 GET /api/v1/interactions/{interaction_id}

功能：读取 Current Session 中单次调用的完整证据。

成功 200：

~~~json
{
  "interaction_id": "call-001",
  "object": "OrderService",
  "command": "create",
  "lifecycle": "completed",
  "phase": "after",
  "original_params": {},
  "schema_changed": false,
  "before_at": "...",
  "after_at": "...",
  "result": {},
  "status": "completed",
  "pauses": [],
  "timeline": [
    {
      "event": "before_reported",
      "phase": "before",
      "at": "2026-08-25T08:00:00Z"
    },
    {
      "event": "after_reported",
      "phase": "after",
      "at": "2026-08-25T08:00:01Z"
    }
  ],
  "payload_metadata": {
    "params": {
      "truncated": false,
      "original_size_bytes": 2,
      "captured_size_bytes": 2
    },
    "result": {
      "truncated": false,
      "original_size_bytes": 2,
      "captured_size_bytes": 2
    }
  }
}
~~~

current_pause 仅活动暂停存在时出现；after_at 和 result 仅 after 已上报时出现。详情投影见 [InteractionObservationService.java:307](../bp-hub/src/main/java/com/ateagents/breakhub/domain/InteractionObservationService.java#L307)。

错误：400 INVALID_INTERACTION_REPORT（ID 无效）；404 INTERACTION_NOT_FOUND。

### 9.3 Pause、命中快照与注入审计

完整 Pause 对象会出现在 Interaction 详情的 pauses，以及活动时的 current_pause：

~~~json
{
  "interaction_id": "call-001",
  "pause_point": "before",
  "status": "paused",
  "breakpoint_snapshots": [
    {
      "breakpoint_id": "uuid",
      "name": "规则名",
      "object": "OrderService",
      "command": "create",
      "pause_point": "before",
      "enabled": true,
      "conditions": [],
      "condition_evidence": [],
      "matched_at": "..."
    }
  ],
  "content_kind": "params",
  "original_content": {},
  "effective_content": {},
  "injection_status": "none",
  "injection_audit": [],
  "effective_change_count": 0,
  "has_pending_injection": false,
  "paused_at": "..."
}
~~~

已解决 Pause 还包含 resolved_at、resolution、released_content。status 可能为 paused、continued、timed_out、safe_released；injection_status 可能为 none、pending、committed、discarded。投影定义见 [PauseService.java:447](../bp-hub/src/main/java/com/ateagents/breakhub/domain/PauseService.java#L447)。

每条 condition_evidence 与 conditions 同位置一一对应：

~~~json
{
  "source": "params",
  "field_path": "customer.status",
  "operator": "eq",
  "expected_value": "blocked",
  "actual_value": "blocked"
}
~~~

contains_any 的 actual_value 只保存实际交集，不复制完整源数组。快照在命中时固化，后续修改或删除 Breakpoint 不改变历史证据，见 [BreakpointService.java:150](../bp-hub/src/main/java/com/ateagents/breakhub/domain/BreakpointService.java#L150)。

### 9.4 POST /api/v1/interactions/{interaction_id}/continue

功能：按 interaction_id + pause_point 继续一个 Pause；不公开内部 pause_id。

请求：

~~~json
{
  "pause_point": "before"
}
~~~

首次成功 200：

~~~json
{
  "interaction_id": "call-001",
  "pause_point": "before",
  "continued": true,
  "result": "continued",
  "resolved_at": "..."
}
~~~

重复继续已解决 Pause 仍为 200：

~~~json
{
  "interaction_id": "call-001",
  "pause_point": "before",
  "continued": false,
  "result": "already_resolved",
  "status": "continued",
  "resolution": "continued_by_controller",
  "resolved_at": "...",
  "content_kind": "params",
  "released_content": {}
}
~~~

错误：400 INVALID_INTERACTION_REPORT；404 INTERACTION_NOT_FOUND；404 PAUSE_NOT_FOUND。

幂等处理见 [PauseService.java:112](../bp-hub/src/main/java/com/ateagents/breakhub/domain/PauseService.java#L112)。

### 9.5 POST /api/v1/interactions/continue-selected

功能：仅供 Web 原子继续明确选择、当前仍活动且没有待提交注入的 Pause。任一目标不再满足条件时整批不执行。

请求：

~~~json
{
  "targets": [
    {
      "interaction_id": "call-001",
      "pause_point": "before"
    }
  ]
}
~~~

targets 必须是非空数组；每项必须是对象；ID 1 到 200 字符；pause_point 为 before/after；不能重复。

成功 200：

~~~json
{
  "result": "continued",
  "continued_count": 1,
  "resolved_at": "...",
  "interactions": [
    {
      "interaction_id": "call-001",
      "pause_point": "before"
    }
  ]
}
~~~

错误：

| HTTP | code | 条件 |
|---:|---|---|
| 400 | INVALID_CONTINUE_SELECTION | targets 结构、取值或唯一性无效 |
| 404 | INTERACTION_NOT_FOUND | 目标不在 Current Session |
| 409 | INTERACTION_NOT_PAUSED | 目标已不再暂停 |
| 409 | PAUSE_POINT_MISMATCH | 活动暂停阶段已变化 |
| 409 | PENDING_INJECTION_REVIEW_REQUIRED | 有待提交注入，必须打开详情复核 |
| 409 | CONTINUE_SELECTION_CHANGED | 选择状态在执行前或事务中变化 |
| 500 | SELECTED_CONTINUE_FAILED | 数据库操作失败，事务未提交 |

原子校验与响应见 [PauseService.java:147](../bp-hub/src/main/java/com/ateagents/breakhub/domain/PauseService.java#L147)。

### 9.6 POST /api/v1/interactions/continue

功能：原子继续命令开始时属于 Current Session 的全部活动 Pause 快照。命令期间新增的 Pause 不纳入本次操作。

请求：无请求体、JSON null 或空对象 {}；任何非空对象、数组或其他值返回 400 INVALID_INTERACTION_REPORT。

成功 200：

~~~json
{
  "result": "continued",
  "continued_count": 2,
  "pending_injection_count": 1,
  "command_started_at": "...",
  "resolved_at": "...",
  "interactions": [
    {
      "interaction_id": "call-001",
      "pause_point": "before",
      "had_pending_injection": true
    }
  ]
}
~~~

没有活动 Pause 时 result=nothing_to_continue、continued_count=0。与 continue-selected 不同，此接口会提交待注入内容，并在 pending_injection_count 中计数。错误 500 BULK_CONTINUE_FAILED 表示事务未提交。实现见 [PauseService.java:241](../bp-hub/src/main/java/com/ateagents/breakhub/domain/PauseService.java#L241)。

### 9.7 POST /api/v1/interactions/{interaction_id}/inject

功能：在不解除 Pause 的前提下，对本次有效 params（before）或 result（after）累积注入。

请求：

~~~json
{
  "pause_point": "before",
  "changes": {
    "customer": {
      "status": "active"
    }
  }
}
~~~

changes 字段必须存在且规范请求必须是 JSON 对象。当前实现对非对象 changes 没有在入口拒绝，而是返回 no_effect；这是 12 节记录的实现缺陷，调用方不得依赖。

成功 200：

~~~json
{
  "interaction_id": "call-001",
  "pause_point": "before",
  "result": "applied",
  "modified": ["/customer/status"],
  "unchanged": [],
  "skipped": {
    "missing": [],
    "type_mismatch": [],
    "original_null": []
  },
  "effective_changed": true,
  "effective_change_count": 1,
  "injected_at": "...",
  "effective_content": {
    "customer": {
      "status": "active"
    }
  }
}
~~~

result 为 applied、partial 或 no_effect。字段路径采用 JSON Pointer。只可修改原始内容已有字段；嵌套对象递归修改；数组整体替换；非 null 值类型以原始内容为锚；原始 null 不能改成非 null。规则见 [JsonChangeEngine.java:14](../bp-hub/src/main/java/com/ateagents/breakhub/domain/JsonChangeEngine.java#L14)。

effective_change_count 统计的是当前 Pause 的注入审计中“至少产生过一个有效字段修改”的操作次数，不是被修改字段的总数。

错误：400 INVALID_INTERACTION_REPORT；404 INTERACTION_NOT_FOUND；409 INTERACTION_NOT_PAUSED；409 PAUSE_POINT_MISMATCH。

## 10. 控制与调试生命周期

映射见 [DebugControlController.java:33](../bp-hub/src/main/java/com/ateagents/breakhub/api/DebugControlController.java#L33)。

### 10.1 GET /api/v1/control

功能：读取不含租约密钥的控制摘要。

无人控制时：

~~~json
{
  "held": false,
  "controller": "none",
  "owned_by_requester": false
}
~~~

有人控制时：

~~~json
{
  "held": true,
  "controller": "web",
  "owned_by_requester": true,
  "expires_at": "..."
}
~~~

Gateway 若要让 owned_by_requester 正确识别并在成功读取后续租，需携带 X-MBP-Control-Instance。响应定义见 [DebugControlService.java:303](../bp-hub/src/main/java/com/ateagents/breakhub/domain/DebugControlService.java#L303)。

### 10.2 POST /api/v1/control/heartbeat

功能：仅当前控制实例可显式续租。

请求：无请求体。

成功 200：

~~~json
{
  "renewed": true,
  "control": {
    "held": true,
    "controller": "mcp",
    "owned_by_requester": true,
    "expires_at": "..."
  }
}
~~~

错误：409 CONTROL_NOT_HELD、CONTROLLED_BY_WEB 或 CONTROLLED_BY_MCP。

### 10.3 POST /api/v1/control/release

功能：若正在调试，先在本地安全释放 Pause 并停止调试，再释放控制。

请求：无请求体。

成功 200，已释放：

~~~json
{
  "released": true,
  "result": "released",
  "control": {
    "held": false,
    "controller": "none",
    "owned_by_requester": false
  }
}
~~~

无人控制时幂等返回 released=false、result=already_released。控制属于其他实例时返回 409。

### 10.4 POST /api/v1/debugging/start

功能：自动取得/续租控制，向配置的 Demo debugger-switch 创建 Reporting Lease；只有完整 ACK 校验成功后才把 Current Session 提交为调试中。

请求：无请求体。

成功 200：

~~~json
{
  "result": "started",
  "changed": true,
  "debugging": true,
  "session_id": "uuid",
  "control": {
    "held": true,
    "controller": "mcp",
    "owned_by_requester": true,
    "expires_at": "..."
  }
}
~~~

重复开始为 200，result=already_started、changed=false。开始提交门见 [DebugControlService.java:67](../bp-hub/src/main/java/com/ateagents/breakhub/domain/DebugControlService.java#L67)。

远端 Reporting 错误：

| HTTP | code | 条件 |
|---:|---|---|
| 409 | REPORTING_LEASE_ALREADY_ACTIVE | Demo 报告已有租约 |
| 409 | REPORTING_LEASE_CONFLICT | Demo 报告租约冲突 |
| 502 | REPORTING_LEASE_NOT_FOUND | Demo 返回规范的租约不存在 |
| 502 | REPORTING_LEASE_PROTOCOL_ERROR | Demo 拒绝了租约请求格式 |
| 502 | REPORTING_LEASE_UNAVAILABLE | Demo 返回不可用或网络不可达 |
| 502 | INVALID_REPORTING_LEASE_ACK | 2xx/200 响应不满足完整 ACK 契约 |
| 502 | REPORTING_LEASE_REQUEST_FAILED | 非规范错误响应 |
| 504 | REPORTING_LEASE_TIMEOUT | 请求超过固定 5 秒超时 |
| 409 | DEBUG_START_CANCELLED | 等待 ACK 时控制、Session 或生命周期变化 |

映射和固定超时见 [HttpReportingLeaseRemote.java:27](../bp-hub/src/main/java/com/ateagents/breakhub/domain/HttpReportingLeaseRemote.java#L27) 与 [HttpReportingLeaseRemote.java:157](../bp-hub/src/main/java/com/ateagents/breakhub/domain/HttpReportingLeaseRemote.java#L157)。

### 10.5 POST /api/v1/debugging/stop

功能：停止 Current 调试、释放活动 Pause，但保留并续租当前控制，便于继续管理同一 Session。远端停止为 best-effort，不回滚已完成的本地停止。

请求：无请求体。

成功 200：

~~~json
{
  "result": "stopped",
  "changed": true,
  "debugging": false,
  "session_id": "uuid",
  "control": {
    "held": true,
    "controller": "mcp",
    "owned_by_requester": true,
    "expires_at": "..."
  }
}
~~~

已经停止时为 200，result=already_stopped、changed=false；若此前无人控制，本调用仍会取得控制。实现见 [DebugControlService.java:173](../bp-hub/src/main/java/com/ateagents/breakhub/domain/DebugControlService.java#L173)。

## 11. Business Interaction 上报

三条接口只接受 Business Bearer Token。业务接入端应使用同一 interaction_id 完成 before → 可选 wait → 业务调用 → after → 可选 wait。

请求长度约束：

- interaction_id、object、command：去空白后 1 到 200 字符；
- pause_point：before 或 after；
- params 必须是 JSON 对象；
- result 必须存在，但值允许为任意 JSON，包括 null；
- params 或 result 自身序列化后不能超过 settings.limits.max_payload_bytes。

Controller 校验见 [BusinessInteractionController.java:24](../bp-hub/src/main/java/com/ateagents/breakhub/api/BusinessInteractionController.java#L24)，Payload 上限见 [InteractionObservationService.java:441](../bp-hub/src/main/java/com/ateagents/breakhub/domain/InteractionObservationService.java#L441)。

### 11.1 POST /api/business/interactions/before

请求：

~~~json
{
  "interaction_id": "call-001",
  "object": "OrderService",
  "command": "create",
  "params": {
    "customer_id": "C-001"
  }
}
~~~

调试中且首次捕获：

~~~json
{
  "interaction_id": "call-001",
  "operation": "created",
  "tracked": true,
  "proceed": false,
  "wait_required": true
}
~~~

没有命中 before Breakpoint 时 proceed=true、wait_required=false。调试未启动且不是已存在请求的重试时：

~~~json
{
  "interaction_id": "call-001",
  "operation": "skipped",
  "tracked": false,
  "proceed": true,
  "wait_required": false,
  "reason": "debugging_inactive"
}
~~~

完全相同的 before 可重试，operation=replayed；目标或 params 与首次上报不同则返回 409 INTERACTION_REPORT_CONFLICT。响应分支见 [InteractionObservationService.java:64](../bp-hub/src/main/java/com/ateagents/breakhub/domain/InteractionObservationService.java#L64)。

### 11.2 POST /api/business/interactions/after

请求：

~~~json
{
  "interaction_id": "call-001",
  "result": {
    "order_id": "O-001"
  }
}
~~~

result 为 null 也合法：

~~~json
{
  "interaction_id": "call-001",
  "result": null
}
~~~

成功捕获：

~~~json
{
  "interaction_id": "call-001",
  "operation": "completed",
  "tracked": true,
  "proceed": false,
  "wait_required": true,
  "lifecycle": "completed"
}
~~~

如果 before 从未被追踪，返回 operation=skipped、tracked=false、proceed=true、wait_required=false、reason=interaction_not_tracked。相同 result 可重试并返回 operation=replayed；不同 result 返回 409 INTERACTION_REPORT_CONFLICT。分支见 [InteractionObservationService.java:137](../bp-hub/src/main/java/com/ateagents/breakhub/domain/InteractionObservationService.java#L137)。

### 11.3 POST /api/business/interactions/wait

功能：按 interaction_id + pause_point 等待；这是一条普通同步 HTTP 请求。调用方只应在 before/after 响应 wait_required=true 时调用，但未暂停时也能安全返回。

请求：

~~~json
{
  "interaction_id": "call-001",
  "pause_point": "before"
}
~~~

未产生对应 Pause 时立即返回：

~~~json
{
  "tracked": true,
  "proceed": true,
  "released": true,
  "result": "not_paused",
  "interaction_id": "call-001",
  "pause_point": "before"
}
~~~

正常继续、超时或安全释放后返回：

~~~json
{
  "tracked": true,
  "proceed": true,
  "released": true,
  "result": "continued",
  "interaction_id": "call-001",
  "pause_point": "before",
  "resolved_at": "...",
  "resolution": "continued_by_controller",
  "content_kind": "params",
  "content": {
    "customer_id": "C-002"
  }
}
~~~

- continued 时 content 是注入后的 effective_content；
- timed_out 或 safe_released 时 content 是原始内容，待提交注入会被丢弃；
- 等待线程被中断时 released=false、result=wait_interrupted，且没有 content；
- Interaction 未被追踪时返回通用 skipped 响应。

释放内容选择见 [PauseService.java:470](../bp-hub/src/main/java/com/ateagents/breakhub/domain/PauseService.java#L470)。

### 11.4 Business 上报错误与故障策略

| HTTP | code | 条件 |
|---:|---|---|
| 400 | INVALID_INTERACTION_REPORT | 字段缺失、类型错误、长度无效或 pause_point 无效 |
| 409 | INTERACTION_REPORT_CONFLICT | 相同 interaction_id 的重试内容与首次上报不一致 |
| 413 | INTERACTION_PAYLOAD_TOO_LARGE | params 或 result 超过配置上限 |

业务接入代码必须把 BreakHub 不可达、超时或 5xx 视为 fail-open，继续原业务调用；调试产品故障不应破坏被调试业务。该产品边界由 [bp-hub README:120](../bp-hub/README.md#L120) 说明。

## 12. 已知实现边界

1. **/api/health 不是接口。** 它当前只被安全规则 permitAll，没有 Handler；不要把白名单配置当作路由清单。
2. **认证 URL 与 Bearer 安全链存在宽匹配。** 设计和本文档把 /api/auth/** 定义为 Web 流程，但当前第一条 Bearer 安全链对除 Business 和 continue-selected 外的所有 /api/** 授予 Gateway 角色。因此，携带 Gateway Bearer 调用部分 /api/auth/** 可能进入 Controller，而不是在安全层被明确拒绝。该行为没有合同测试支撑，不应作为公开契约依赖；若要消除歧义，应在安全链中显式限制 /api/auth/**。
3. **框架错误体未统一。** 只有 ProductException 和安全处理器保证 code/message；Spring MVC 自身产生的 400、404、405、415 等响应体不是本文档稳定契约。
4. **读取可能续租。** 成功的管理 GET/HEAD 在能解析出当前控制实例时会续租。客户端做“纯只读探测”时需意识到这一控制生命周期副作用。
5. **Interaction 状态筛选名与返回值不完全同名。** 查询参数 status=in_progress 实际筛选 lifecycle=running 且未暂停的记录，但列表项 item.status 返回 running，不返回 in_progress。该映射来自 [InteractionListService.java:87](../bp-hub/src/main/java/com/ateagents/breakhub/domain/InteractionListService.java#L87) 和 [InteractionListService.java:146](../bp-hub/src/main/java/com/ateagents/breakhub/domain/InteractionListService.java#L146)；客户端不能把筛选枚举直接当作响应枚举。
6. **非对象 changes 会产生不可再导入的审计。** inject 的 Controller 当前只检查 changes 字段存在，JsonChangeEngine 会把非对象根记为 no_effect，但审计仍持久化原值；SessionArchive 导入又要求 injection_audit[].changes 必须是对象，因此这类导出归档无法重新导入。规范请求始终要求 changes 为对象；服务端应后续在入口返回 400，而不是接受。相关实现见 [ObservationController.java:105](../bp-hub/src/main/java/com/ateagents/breakhub/api/ObservationController.java#L105)、[JsonChangeEngine.java:14](../bp-hub/src/main/java/com/ateagents/breakhub/domain/JsonChangeEngine.java#L14) 和 [SessionArchiveService.java:423](../bp-hub/src/main/java/com/ateagents/breakhub/domain/SessionArchiveService.java#L423)。
7. **wait 会占用一个普通 HTTP 请求直到释放或超时。** 它没有流式事件、心跳帧或异步任务 ID；部署层超时必须长于产品 pause_timeout，或者业务接入端按 fail-open 处理连接中断。

## 13. 源码与测试依据

主要一手依据：

- 路由与请求 DTO：[api Controller 目录](../bp-hub/src/main/java/com/ateagents/breakhub/api/)
- 鉴权、CSRF 与角色边界：[SecurityConfiguration.java](../bp-hub/src/main/java/com/ateagents/breakhub/config/SecurityConfiguration.java)
- 控制身份与读取续租：[ControlIdentityResolver.java](../bp-hub/src/main/java/com/ateagents/breakhub/api/ControlIdentityResolver.java)、[ControlRequestConfiguration.java](../bp-hub/src/main/java/com/ateagents/breakhub/config/ControlRequestConfiguration.java)
- Session 与归档：[CurrentSessionService.java](../bp-hub/src/main/java/com/ateagents/breakhub/domain/CurrentSessionService.java)、[SessionArchiveService.java](../bp-hub/src/main/java/com/ateagents/breakhub/domain/SessionArchiveService.java)
- Breakpoint 与条件：[BreakpointService.java](../bp-hub/src/main/java/com/ateagents/breakhub/domain/BreakpointService.java)、[BreakpointConditionEngine.java](../bp-hub/src/main/java/com/ateagents/breakhub/domain/BreakpointConditionEngine.java)
- Interaction、Pause 与注入：[InteractionObservationService.java](../bp-hub/src/main/java/com/ateagents/breakhub/domain/InteractionObservationService.java)、[InteractionListService.java](../bp-hub/src/main/java/com/ateagents/breakhub/domain/InteractionListService.java)、[PauseService.java](../bp-hub/src/main/java/com/ateagents/breakhub/domain/PauseService.java)
- 控制与 Reporting：[DebugControlService.java](../bp-hub/src/main/java/com/ateagents/breakhub/domain/DebugControlService.java)、[HttpReportingLeaseRemote.java](../bp-hub/src/main/java/com/ateagents/breakhub/domain/HttpReportingLeaseRemote.java)
- HTTP 合同测试：[AuthenticationApiTest.java](../bp-hub/src/test/java/com/ateagents/breakhub/AuthenticationApiTest.java)、[ProductBaselineApiTest.java](../bp-hub/src/test/java/com/ateagents/breakhub/ProductBaselineApiTest.java)、[BusinessObservationHttpTest.java](../bp-hub/src/test/java/com/ateagents/breakhub/BusinessObservationHttpTest.java)、[InteractionListApiTest.java](../bp-hub/src/test/java/com/ateagents/breakhub/InteractionListApiTest.java)、[LocalSessionWorkspaceApiTest.java](../bp-hub/src/test/java/com/ateagents/breakhub/LocalSessionWorkspaceApiTest.java)、[SessionArchiveApiTest.java](../bp-hub/src/test/java/com/ateagents/breakhub/SessionArchiveApiTest.java)、[ControlLifecycleApiTest.java](../bp-hub/src/test/java/com/ateagents/breakhub/ControlLifecycleApiTest.java)
