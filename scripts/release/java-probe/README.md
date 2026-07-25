# BreakHub Java Probe 用户手册

本目录包含面向 Java 17 的纯 Java Probe JAR。Probe 不依赖 Spring 或 Jakarta，不启动 HTTP 服务，也不要求用户使用特定 Web 框架。

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

## 2. 创建应用级 Probe

应用启动时创建一次，所有接口和业务方法复用同一个实例。不要在每次请求中重新创建 Probe。

```java
import com.ateagents.breakhub.probe.BreakHubProbe;
import com.ateagents.breakhub.probe.ProbeConfig;

public final class ProbeRuntime {
    private static final BreakHubProbe PROBE = BreakHubProbe.open(
            ProbeConfig.of(
                    "http://127.0.0.1:18621",
                    "breakhub-local-business-token"));

    private ProbeRuntime() {
    }

    public static BreakHubProbe probe() {
        return PROBE;
    }

    public static void close() {
        PROBE.close();
    }
}
```

在应用退出时调用 `ProbeRuntime.close()`。普通 Java 程序可注册关闭钩子：

```java
Runtime.getRuntime().addShutdownHook(new Thread(ProbeRuntime::close));
```

需要自定义超时时可使用完整配置：

```java
ProbeConfig config = new ProbeConfig(
        "http://127.0.0.1:18621",
        "breakhub-local-business-token",
        300,
        1000,
        300000);
```

Hub 地址、业务上报 Token 和超时只在创建 Probe 时加载一次。业务调用只读取当前实例的内存状态。

## 3. 由用户提供 Reporting Lease 接口

Hub 需要调用用户应用提供的 HTTP 接口启停 Reporting Lease。Probe 不监听端口、不定义路由，也不处理该入口的鉴权。

接口收到请求后，把原始 JSON 请求体交给同一个 Probe，并原样写回结果：

```java
LeaseResult result = ProbeRuntime.probe().handleLease(requestBody);
writeResponse(
        result.statusCode(),
        "application/json",
        result.responseBody());
```

该入口可以不鉴权。`businessClientToken` 是 Probe 向 Hub 上报 before、after 和 wait 请求时使用的 Bearer Token，与 Hub 调用用户入口是两个方向。

把 Hub `application.yml` 中的 `breakhub.equipment.debugger-switch.url` 指向用户提供的完整地址，例如：

```yaml
breakhub:
  equipment:
    debugger-switch:
      url: http://127.0.0.1:8080/api/debugger/enabled
```

Spring MVC Controller 也只负责转调，不需要扫描 Probe 包：

```java
@PostMapping("/api/debugger/enabled")
public ResponseEntity<String> setDebuggerEnabled(
        @RequestBody(required = false) String body) {
    LeaseResult result = probe.handleLease(body);
    return ResponseEntity.status(result.statusCode())
            .contentType(MediaType.APPLICATION_JSON)
            .body(result.responseBody());
}
```

## 4. 包装业务方法

通过 `DebugMethodInfo` 描述业务调用，再使用应用级 Probe 包住原业务逻辑：

```java
public Result control(String objectName, String command, Integer slotId,
        Map<String, Object> params) {
    DebugMethodInfo methodInfo = new DebugMethodInfo()
            .objectName(objectName)
            .cmdName(command)
            .slotId(slotId)
            .params(params)
            .serviceName("your-service-name")
            .className("InstrumentService")
            .methodName("control")
            .arg("params", params)
            .param("params", "操作传参", "java.util.Map");

    return ProbeRuntime.probe().invoke(methodInfo, () -> doControl(params));
}
```

未建立 Reporting Lease 时，`invoke(...)` 只做一次内存状态判断并直接执行原业务。与 Hub 通信失败时也会安全放行业务逻辑。Spring Boot 只需把同一个 `BreakHubProbe` 注册成应用 Bean；Probe 本身没有 Spring 专用实现。
