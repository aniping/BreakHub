# BreakHub Java Probe

Java 探针是独立 Maven 依赖，产物坐标为：

```xml
<dependency>
    <groupId>com.ateagents</groupId>
    <artifactId>bp-probe</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

当前适配 Spring Boot 3 / Java 17，提供 Reporting Lease、before/after 上报、暂停等待以及参数和结果注入。业务侧负责使用 `DebugMethodInfo` 描述自己的 Object、Command、方法与参数；探针本身不包含示例业务对象。

```powershell
mvn test
mvn install
```

完整接入示例见 `../../example/java/`。
