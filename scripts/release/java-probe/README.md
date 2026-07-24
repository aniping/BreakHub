# BreakHub Java Probe 用户手册

本目录包含 Java Probe JAR。它用于把 Spring Boot 业务方法接入 BreakHub，支持调用上报、before/after 断点、参数注入和结果注入。

## 1. 安装到本机 Maven 仓库

要求 JDK 17 或更高版本，并已安装 Maven。在本目录打开 PowerShell 7，执行：

```powershell
mvn install:install-file `
  -Dfile=.\bp-probe-0.1.0-SNAPSHOT.jar `
  -DgroupId=com.ateagents `
  -DartifactId=bp-probe `
  -Dversion=0.1.0-SNAPSHOT `
  -Dpackaging=jar
```

安装成功后，在业务项目的 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>com.ateagents</groupId>
    <artifactId>bp-probe</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 2. 启用 Probe 组件

让 Spring 扫描业务包和 Probe 包：

```java
@SpringBootApplication(scanBasePackages = {
        "com.example.yourapp",
        "com.ateagents.breakhub.probe"
})
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

在业务项目的 `application.yml` 中配置 Hub 地址。`business-client-token` 必须与 Hub 的 `application.yml` 中 `breakhub.security.business-client-token` 一致：

```yaml
debugger:
  server-url: http://127.0.0.1:18621
  business-client-token: breakhub-local-business-token
  service-name: your-service-name
  connect-timeout-ms: 300
  read-timeout-ms: 1000
  breakpoint-timeout-ms: 300000
```

## 3. 提供调试开关入口

Hub 通过一个业务 HTTP 入口启停 Reporting Lease。以下示例入口是 `/api/debugger/enabled`：

```java
@RestController
@RequestMapping("/api")
public class DebuggerController {
    private final ReportingLeaseManager reportingLeaseManager;

    public DebuggerController(ReportingLeaseManager reportingLeaseManager) {
        this.reportingLeaseManager = reportingLeaseManager;
    }

    @PostMapping("/debugger/enabled")
    public ResponseEntity<Map<String, Object>> setDebuggerEnabled(
            @RequestBody(required = false) String body) {
        ReportingLeaseManager.HttpResult result = reportingLeaseManager.handle(body);
        return ResponseEntity.status(result.statusCode()).body(result.body());
    }
}
```

把 Hub `application.yml` 中的 `breakhub.equipment.debugger-switch.url` 指向该完整地址，例如：

```yaml
breakhub:
  equipment:
    debugger-switch:
      url: http://127.0.0.1:8080/api/debugger/enabled
```

## 4. 包装业务方法

用 `DebugInvoker.invoke` 包住原业务逻辑，并通过 `DebugMethodInfo` 描述调用：

```java
public Result control(String objectName, String command, Integer slotId,
        Map<String, Object> params) {
    DebugMethodInfo methodInfo = new DebugMethodInfo()
            .objectName(objectName)
            .cmdName(command)
            .slotId(slotId)
            .params(params)
            .serviceName(DebuggerSettings.serviceName)
            .className("InstrumentService")
            .methodName("control")
            .arg("params", params)
            .param("params", "操作传参", "java.util.Map");

    return DebugInvoker.invoke(methodInfo, () -> doControl(params));
}
```

Probe 未启用或与 Hub 通信失败时，会继续执行原业务逻辑。完整可运行示例见仓库的 `example/java/`。
