package com.ateagents.breakhub.api;

import java.util.Map;

import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ateagents.breakhub.BreakHubApplication;
import com.ateagents.breakhub.config.ProductProperties;
import com.ateagents.breakhub.config.ServerBindingProperties;
import com.ateagents.breakhub.domain.CurrentSessionService;
import com.ateagents.breakhub.domain.CurrentSessionService.SessionWorkspace;
import com.ateagents.breakhub.domain.DebugControlService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1")
public class ProductOverviewController {

    private final ProductProperties properties;
    private final ServerBindingProperties server;
    private final CurrentSessionService sessions;
    private final JdbcTemplate jdbc;
    private final ApplicationContext applicationContext;
    private final DebugControlService control;
    private final ControlIdentityResolver identities;

    public ProductOverviewController(
            ProductProperties properties,
            ServerBindingProperties server,
            CurrentSessionService sessions,
            JdbcTemplate jdbc,
            ApplicationContext applicationContext,
            DebugControlService control,
            ControlIdentityResolver identities) {
        this.properties = properties;
        this.server = server;
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.applicationContext = applicationContext;
        this.control = control;
        this.identities = identities;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(HttpServletRequest request) {
        SessionWorkspace current = sessions.current();
        jdbc.queryForObject("SELECT 1", Integer.class);
        return Map.of(
                "product", Map.of(
                        "name", "BreakHub",
                        "version", productVersion()),
                "equipment", equipment(),
                "current_session", session(current),
                "connection", Map.of(
                        "status", "healthy",
                        "label", "产品后端在线"),
                "debugging", control.debuggingSnapshot(current.sessionId()),
                "control", control.controlSnapshot(identities.resolve(request)),
                "health", Map.of(
                        "status", "healthy",
                        "database", "healthy"));
    }

    @GetMapping("/sessions/current")
    public Map<String, Object> currentSession() {
        return session(sessions.current());
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        ProductProperties.Equipment equipment = properties.equipment();
        ProductProperties.Security security = properties.security();
        jdbc.queryForObject("SELECT 1", Integer.class);
        return Map.of(
                "configuration_source", "file",
                "restart_required", true,
                "server", Map.of(
                        "address", server.address(),
                        "port", effectivePort()),
                "equipment", Map.of(
                        "equipment_id", equipment.id(),
                        "display_name", equipment.displayName(),
                        "debugger_switch", Map.of(
                                "url", equipment.debuggerSwitch().url())),
                "security", Map.of(
                        "web_username", security.webUsername(),
                        "web_password", configured(security.webPassword()),
                        "gateway_token", configured(security.gatewayToken()),
                        "business_client_token", configured(security.businessClientToken())),
                "limits", Map.of(
                        "control_lease_timeout_seconds", properties.controlLease().timeout().toSeconds(),
                        "pause_timeout_seconds", properties.interaction().pauseTimeout().toSeconds(),
                        "max_payload_bytes", properties.interaction().maxPayloadSize().toBytes()),
                "storage", Map.of(
                        "data_directory", properties.dataDirectory()),
                "health", Map.of(
                        "status", "healthy",
                        "database", "healthy",
                        "debugger_switch", "configured"));
    }

    private Map<String, Object> equipment() {
        return Map.of(
                "equipment_id", properties.equipment().id(),
                "display_name", properties.equipment().displayName());
    }

    private int effectivePort() {
        if (applicationContext instanceof WebServerApplicationContext webServerContext
                && webServerContext.getWebServer() != null) {
            return webServerContext.getWebServer().getPort();
        }
        return server.port();
    }

    static Map<String, Object> session(SessionWorkspace session) {
        return Map.of(
                "session_id", session.sessionId(),
                "name", session.name(),
                "source", session.source(),
                "read_only", session.readOnly(),
                "current", session.current(),
                "created_at", session.createdAt(),
                "updated_at", session.updatedAt());
    }

    private static String productVersion() {
        String version = BreakHubApplication.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }

    private static String configured(String value) {
        return value == null || value.isBlank() ? "not_configured" : "configured";
    }
}
