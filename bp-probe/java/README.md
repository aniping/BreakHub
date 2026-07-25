# BreakHub Java Probe

Java Probe 是一个面向 Java 17 的纯 Java Maven 依赖，不依赖 Spring、Jakarta，也不会启动 HTTP 服务。它提供 Reporting Lease、before/after 上报、暂停等待、参数注入和结果注入。

```xml
<dependency>
    <groupId>com.ateagents</groupId>
    <artifactId>bp-probe</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

应用启动时创建一个 `BreakHubProbe`，所有业务调用和用户提供的 Reporting Lease 接口都复用该实例：

```java
BreakHubProbe probe = BreakHubProbe.open(
        ProbeConfig.of("http://127.0.0.1:18621", "business-token"));
Runtime.getRuntime().addShutdownHook(new Thread(probe::close));
```

业务方法通过 `probe.invoke(...)` 显式接入：

```java
return probe.invoke(methodInfo, () -> doBusiness(params));
```

Hub 调用的 HTTP 接口由用户应用自行提供。接口只需把原始 JSON 请求体传给同一个 Probe，并把 `statusCode`、`responseBody` 和 `application/json` 响应类型写回：

```java
LeaseResult result = probe.handleLease(requestBody);
writeJsonResponse(result.statusCode(), result.responseBody());
```

Probe 不监听端口、不定义路由，也不校验这个用户入口的鉴权。`businessClientToken` 只用于 Probe 向 Hub 上报业务调用。配置只在 `BreakHubProbe.open(...)` 时加载一次；`invoke(...)` 读取实例内存状态，不会按业务请求重新加载配置。

```powershell
mvn test
mvn install
```

Spring Boot 复用纯 Java Core 的完整示例见 `../../example/java/`。
