# Java 模拟业务服务

这是一个可独立构建和运行的 Java 17 / Spring Boot 模拟业务服务。Demo 在应用启动时把纯 Java `BreakHubProbe` 注册为单例 Bean，业务方法调用 `probe.invoke(methodInfo, callback)`；Probe 本身不依赖 Spring，也不提供 HTTP 接口。Demo 默认只监听 `127.0.0.1:18622`，默认连接本机 `18621` 的 BreakHub 产品服务。

## 构建与启动

从仓库根目录生成测试辅助包：

```powershell
.\scripts\package-java-demo.cmd
.\dist\java-demo\start.cmd
```

也可以在 Demo 模块内直接构建：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

cd example\java
mvn clean package
java -jar target\instrument-demo-0.1.0.jar
```

健康检查：

```powershell
curl.exe http://127.0.0.1:18622/api/demo/ping
```

返回 `pong` 表示 Demo 已启动。

### 与产品一起真实启动

最终联调必须分别保存本轮 Demo 与产品进程，故障注入和结束验收时只停止记录下来的精确 PID：

```powershell
$runStamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logRoot = Join-Path $env:TEMP "breakhub-acceptance-$runStamp"
New-Item -ItemType Directory -Path $logRoot | Out-Null

$demoRoot = (Resolve-Path I:\ai\cc\breakhub\example\java).Path
$demoStdout = Join-Path $logRoot "demo.stdout.log"
$demoStderr = Join-Path $logRoot "demo.stderr.log"
$demoProcess = Start-Process `
  -FilePath "$env:JAVA_HOME\bin\java.exe" `
  -WorkingDirectory $demoRoot `
  -ArgumentList @("-jar", "target\instrument-demo-0.1.0.jar") `
  -WindowStyle Hidden `
  -RedirectStandardOutput $demoStdout `
  -RedirectStandardError $demoStderr `
  -PassThru

$productRoot = (Resolve-Path I:\ai\cc\breakhub\bp-hub).Path
$productStdout = Join-Path $logRoot "product.stdout.log"
$productStderr = Join-Path $logRoot "product.stderr.log"
$productProcess = Start-Process `
  -FilePath "$env:JAVA_HOME\bin\java.exe" `
  -WorkingDirectory $productRoot `
  -ArgumentList @(
    "-jar",
    "target\breakhub-0.1.0-SNAPSHOT.jar",
    "--spring.config.location=file:.\application.yml"
  ) `
  -WindowStyle Hidden `
  -RedirectStandardOutput $productStdout `
  -RedirectStandardError $productStderr `
  -PassThru

Get-CimInstance Win32_Process -Filter "ProcessId = $($demoProcess.Id)" |
  Select-Object ProcessId, CreationDate, CommandLine
Get-CimInstance Win32_Process -Filter "ProcessId = $($productProcess.Id)" |
  Select-Object ProcessId, CreationDate, CommandLine

curl.exe --fail --silent --show-error http://127.0.0.1:18622/api/demo/ping
curl.exe --fail --silent --show-error --output NUL http://127.0.0.1:18621/
```

第一条应返回 `pong`，第二条应以 HTTP 200 退出；产品没有 `/api/health` 接口。登录后可再通过 `/api/v1/overview` 查看数据库与调试状态摘要。

停止本轮进程时只允许使用 `$demoProcess.Id` 或 `$productProcess.Id`。不要按 `java`、Maven、Node 等进程名批量终止，也不要把已有监听者当作本轮服务。

## 业务接口

- `GET /api/demo/ping`
- `POST /api/demo/debugger/enabled`
- `POST /api/demo/initialize`
- `POST /api/demo/control`

批量调用初始化和控制接口：

```powershell
.\scripts\call-all-demo-apis.cmd
```

使用非默认地址时：

```powershell
.\scripts\call-all-demo-apis.cmd -BaseUrl http://127.0.0.1:18622
```

控制请求示例：

```json
{
  "instType": "VNA",
  "cmdName": "start",
  "slotId": 1,
  "params": {
    "mode": "AUTO"
  }
}
```

```powershell
$controlBody = '{"instType":"VNA","cmdName":"start","slotId":1,"params":{"mode":"AUTO","durationMs":1000,"operator":"browser-acceptance"}}'
curl.exe -sS -H "Content-Type: application/json" --data-binary $controlBody `
  http://127.0.0.1:18622/api/demo/control
```

## Reporting Lease

业务上报不是永久布尔开关，而是一轮进程内 Reporting Lease（业务上报租约）。开关唯一入口是 `POST /api/demo/debugger/enabled`，本阶段不校验鉴权信息，也没有兼容别名。

建立租约时不传 `lease_id`：

```powershell
curl.exe -X POST http://127.0.0.1:18622/api/demo/debugger/enabled `
  -H "Content-Type: application/json" `
  -d '{"enabled":true}'
```

成功响应会返回不可预测的 `lease_id`。续签和停止必须原样携带该标识：

```json
{"enabled":true,"lease_id":"<建立时返回的标识>"}
```

```json
{"enabled":false,"lease_id":"<建立时返回的标识>"}
```

- 产品应每 10 秒续签一次；Demo 自最后一次有效建立或续签起 30 秒未再收到有效续签时自动失效。
- 已有活动租约时，重复无标识建立会返回冲突，且不会暴露或替换当前租约。
- 携带错误或旧标识的请求不能改变当前租约；进程重启后旧标识不存在。
- 产品只有在旧标识明确不存在、本地仍保持原调试意图且最后确认的 30 秒窗口尚未结束时，才会建立一个全新的租约标识；跨过原截止时间的迟到建立响应不会恢复调试。Demo 不参与猜测或接管旧租约，未被产品确认和续签的孤立租约仍按自身 30 秒截止时间自动失效。
- 匹配停止、自动失效和应用关闭竞争时只会收尾当前租约代次一次：先停止新的上报，再取消已登记的活动 before/after wait；before 使用原参数、after 使用原结果安全放行。应用关闭还会有界等待租约到期调度器退出。
- 单次 before、wait 或 after 失败只把上报通道标记为 `degraded`，当前调用安全放行且本轮租约仍然有效；降级期间普通业务请求直接放行，不会让每次调用重复承担网络超时。
- 每次有效续签最多把一个探测额度补满为 1，多次续签不会累积；并发业务请求最多只有一个探测在途。探测成功后恢复 `healthy`，失败则继续降级并等待下一次有效续签。
- 成功开关响应的 `reporting_status` 只会是 `healthy`、`degraded` 或 `idle`；降级时可额外返回固定脱敏的 `last_error` 摘要，不包含产品地址、凭据、租约标识、Interaction 身份或业务内容。

Reporting Lease 只用于请求代次隔离，不是身份凭据，也不会持久化。

## 配置

默认配置位于 `src/main/resources/application.yml`：

- Demo 端口：`server.port=18622`
- 产品地址：`debugger.server-url=http://127.0.0.1:18621`
- 业务上报凭据：Demo 默认使用 `breakhub-local-business-token`；产品 `application.yml` 中的 `breakhub.security.business-client-token` 必须显式配置为相同值；不要把本地忽略的配置文件当作仓库默认事实来源
- 上报连接、读取和断点等待超时沿用 `debugger.*-timeout-ms`

租约失效时间固定为 30 秒，不通过业务配置改写。

Demo 使用产品当前的 `/api/business/interactions/before`、`/after` 与 `/wait` 契约。未命中 before Pause 时直接执行原业务；命中后，只有显式继续返回的有效参数才会在回调前写回本次调用传入的同一个 `Map`。写入前会校验完整结构和类型，写入失败时恢复原引用；不可变 `Map` 使用原参数放行，无法恢复的部分写入会中止业务回调并报告明确的接入错误。

业务回调正常返回后才会上报 after。命中 after Pause 时，显式继续返回的内容会转换回原业务结果类型，并通过 JSON 往返校验、递归 Java 运行时类型校验和实例字段覆盖审计，防止字段丢失、数值截断、弱类型容器污染或未序列化状态被清空；只有 Jackson 直接字段或经 record 元数据确认且直返组件字段的 accessor 才视为可证明映射，普通转换型 getter/setter 会安全回退。容器只接受无额外业务状态的普通 `HashMap`、默认顺序 `LinkedHashMap`、`ArrayList`、`LinkedList` 和数组；自定义容器、访问顺序 Map 或带 comparator/configuration 的容器返回原结果。上报失败、等待取消或转换不安全时返回原结果。业务回调抛出的运行时异常保持原实例，受检异常仍按既有方式包装，二者都不会进入正常 after 结果上报路径。

## 真实浏览器注入验收

1. 打开 `http://127.0.0.1:18621/`，使用产品 `application.yml` 中的 Web 账号登录。
2. 创建并启用两条 `VNA.start` 规则：一条暂停在 before，一条暂停在 after。
3. 点击“开始调试”，确认状态为“运行中 · 业务上报正常”。
4. 在另一个终端调用 `POST http://127.0.0.1:18622/api/demo/control`：

```json
{
  "instType": "VNA",
  "cmdName": "start",
  "slotId": 1,
  "params": {
    "mode": "AUTO",
    "durationMs": 1000,
    "operator": "browser-acceptance"
  }
}
```

5. 在“调用记录”点击暂停记录，确认右侧详情抽屉覆盖列表且页面没有跳转。
6. 在 before Pause 勾选已有字段 `mode`，把 `AUTO` 改为 `MANUAL`；先点“注入但保持暂停”，再点“继续此调用”。
7. 同一 Interaction 进入 after Pause 后，勾选返回结果已有字段 `message`，改为 `after-injected`；再次注入并继续。
8. 业务响应必须同时包含 `message=after-injected` 和 `data.mode=MANUAL`。`data.mode` 证明原业务回调读取了本次调用传入的同一个可变 `Map`，`message` 证明 after 内容转换回声明的 `ValueResult`。
9. 再次点击已释放记录；抽屉只能显示原始内容、有效内容、Pause、注入与继续审计，不得再显示活动注入编辑器。

### 继续所选

先屏蔽 after 规则，只保留 `VNA.start` before 规则，同时发起三条带不同 `operator` 的控制请求。三条都暂停后：

1. 只勾选其中两条没有待提交注入的记录。
2. 点击“继续所选”，只允许这两条请求返回。
3. 未勾选请求必须继续暂停，且不能新增注入或继续审计。
4. 打开剩余记录，用“继续此调用”单独放行。

含待提交注入的记录不能参与“继续所选”，必须进入详情逐条复核。未勾选就是不处理，本流程不增加“暂不处理”按钮或状态。

### 业务异常

调用 `VNA.error`：

```json
{
  "instType": "VNA",
  "cmdName": "error",
  "slotId": 1,
  "params": {
    "mode": "AUTO"
  }
}
```

```powershell
$errorBody = '{"instType":"VNA","cmdName":"error","slotId":1,"params":{"mode":"AUTO"}}'
curl.exe -sS -w "`nHTTP_STATUS:%{http_code}`n" -H "Content-Type: application/json" `
  --data-binary $errorBody http://127.0.0.1:18622/api/demo/control

$measureBody = '{"instType":"SA","cmdName":"measure","slotId":2,"params":{"frequencyHz":1000000000,"spanHz":10000000,"points":201}}'
curl.exe -sS -w "`nHTTP_STATUS:%{http_code}`n" -H "Content-Type: application/json" `
  --data-binary $measureBody http://127.0.0.1:18622/api/demo/control
```

`VNA.error` 应保持 HTTP 500，Demo 日志仍应出现 `simulated instrument control error`，不得伪装成成功结果，也不得进入正常 after 注入路径。紧随其后的 `SA.measure` 应返回 HTTP 200，证明一次业务异常不会永久关闭后续调试。

```powershell
Select-String -Path $demoStdout,$demoStderr -Pattern "simulated instrument control error"
```

## 双向失联与恢复验收

恢复窗口从最后一次成功 ACK 计算，不从进程停止、首次报错或重启时间计算。每轮都先记录产品 `/api/v1/overview` 中的 `last_confirmed_at` 与 `server_deadline_at`。

共享的精确 PID 停止方式如下。每个场景只执行对应的一段，不要同时停止两端；重启时重复“与产品一起真实启动”中的对应 `Start-Process` 命令，并用新返回对象覆盖原变量。

产品离线场景：

```powershell
$oldProductPid = $productProcess.Id
Stop-Process -Id $oldProductPid
Wait-Process -Id $oldProductPid -Timeout 10 -ErrorAction SilentlyContinue
```

Demo 离线场景：

```powershell
$oldDemoPid = $demoProcess.Id
Stop-Process -Id $oldDemoPid
Wait-Process -Id $oldDemoPid -Timeout 10 -ErrorAction SilentlyContinue

# 重启后必须重新赋值，后续只使用新 PID：
$demoProcess = Start-Process `
  -FilePath "$env:JAVA_HOME\bin\java.exe" `
  -WorkingDirectory $demoRoot `
  -ArgumentList @("-jar", "target\instrument-demo-0.1.0.jar") `
  -WindowStyle Hidden `
  -RedirectStandardOutput $demoStdout `
  -RedirectStandardError $demoStderr `
  -PassThru
```

| 场景 | 操作 | 必须记录的结果 |
| --- | --- | --- |
| 产品离线 | 活动 before Pause 存在时停止本轮 `$productProcess.Id` | Demo 最迟在最后有效续签后 30 秒取消等待，以原始参数安全放行；产品重启后旧 Pause 以 `product_restart` 安全释放且只能读历史，后续业务直接放行 |
| Demo 离线 | 停止本轮 `$demoProcess.Id` | 产品先显示“运行中 · 业务服务续签异常”，到最后 ACK 后 30 秒转为空闲，丢弃待提交注入并安全释放 Pause；Control Lease 保留 |
| 窗口内恢复 | 留出下一次 10 秒续签和建租 ACK 的时间重启 Demo | 新代次 ACK 必须在原 `server_deadline_at` 前被产品接受；随后恢复 healthy，新的确认时间与截止时间向后推进 |
| 窗口外恢复 | 等产品 expired/idle 后再重启 Demo | 不自动恢复；必须再次点击“开始调试” |
| 旧代次隔离 | 把三轮本地租约记为 L1/L2/L3，交叉执行续签和停止 | 旧续签返回 404，旧停止不能关闭新租约，当前代次续签与停止成功 |

真实租约标识只保存在本地验收变量中，不写入 Web 截图、公开日志或交付文档。

### HTTP 黑洞硬边界

连接拒绝只能证明“不可达”，不能替代 HTTP 黑洞。完成真实 Demo 正常链路后，按精确 PID 停止本轮产品与 Demo，再运行：

```powershell
$blackholeStdout = Join-Path $logRoot "blackhole.jsonl"
$blackholeStderr = Join-Path $logRoot "blackhole.stderr.log"
$blackholeProcess = Start-Process `
  -FilePath (Resolve-Path .\scripts\reporting-http-blackhole.cmd).Path `
  -WindowStyle Hidden `
  -RedirectStandardOutput $blackholeStdout `
  -RedirectStandardError $blackholeStderr `
  -PassThru

Get-Content -LiteralPath $blackholeStdout -Wait
```

脚本只监听 loopback：首次合法建租请求返回完整 ACK，后续续签连接会被接受并读完，但永不返回 HTTP 响应。重新启动产品并开始调试后，在另一个终端直接建立并等待一个业务 Pause：

```powershell
$businessToken = "breakhub-local-business-token" # 必须与产品 application.yml 一致
$interactionId = [guid]::NewGuid().ToString()
$headers = @{ Authorization = "Bearer $businessToken" }
$beforeBody = @{
  interaction_id = $interactionId
  object = "VNA"
  command = "start"
  params = @{ mode = "AUTO"; operator = "blackhole-acceptance" }
} | ConvertTo-Json -Compress

Invoke-RestMethod -Method Post -ContentType "application/json" -Headers $headers `
  -Uri http://127.0.0.1:18621/api/business/interactions/before `
  -Body $beforeBody

$waitBody = @{
  interaction_id = $interactionId
  pause_point = "before"
} | ConvertTo-Json -Compress

# 此调用保持阻塞；回到 Web 给 mode 注入 MANUAL，但不要点击继续。
$released = Invoke-RestMethod -Method Post -ContentType "application/json" -Headers $headers `
  -Uri http://127.0.0.1:18621/api/business/interactions/wait `
  -Body $waitBody
$released | ConvertTo-Json -Depth 8
```

`wait` 最终必须返回 `safe_released` 和原始 `mode=AUTO`，详情中的待提交 `MANUAL` 注入必须标记为 `discarded`。同时记录：

- 首次 ACK 与 `last_confirmed_at`；
- 约 10 秒后的首个续签请求；
- 续签开始后约 5 秒的硬超时与 degraded；
- 不变的 `server_deadline_at`；
- 最后 ACK 后 30 秒内的 expired、原始内容安全放行和注入 `discarded`。

黑洞不得把本地到期推迟到 `server_deadline_at` 之后。结束时使用 `taskkill.exe /PID $blackholeProcess.Id /T /F` 精确停止该 CMD 及其 Python 子进程，不要按进程名批量终止。
