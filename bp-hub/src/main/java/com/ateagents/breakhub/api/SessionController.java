package com.ateagents.breakhub.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ateagents.breakhub.domain.ControlIdentity;
import com.ateagents.breakhub.domain.CurrentSessionService;
import com.ateagents.breakhub.domain.CurrentSessionService.SessionListSnapshot;
import com.ateagents.breakhub.domain.CurrentSessionService.SessionWorkspace;
import com.ateagents.breakhub.domain.DebugControlService;
import com.ateagents.breakhub.domain.SessionArchiveService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final CurrentSessionService sessions;
    private final ControlIdentityResolver identities;
    private final DebugControlService control;
    private final SessionArchiveService archives;

    public SessionController(
            CurrentSessionService sessions,
            ControlIdentityResolver identities,
            DebugControlService control,
            SessionArchiveService archives) {
        this.sessions = sessions;
        this.identities = identities;
        this.control = control;
        this.archives = archives;
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest request) {
        requireWeb(request);
        SessionListSnapshot snapshot = sessions.snapshot();
        List<Map<String, Object>> items = snapshot.items().stream()
                .map(ProductOverviewController::session)
                .toList();
        return Map.of(
                "current_session_id", snapshot.currentSessionId(),
                "items", items);
    }

    @GetMapping("/{sessionId}")
    public Map<String, Object> get(@PathVariable String sessionId, HttpServletRequest request) {
        requireWeb(request);
        return ProductOverviewController.session(sessions.get(sessionId));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody SessionNameRequest body,
            HttpServletRequest request) {
        SessionWorkspace created = control.performWrite(requireWeb(request), () -> sessions.create(body.name()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductOverviewController.session(created));
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importArchive(
            @RequestBody JsonNode body,
            HttpServletRequest request) {
        SessionWorkspace imported = control.performWrite(
                requireWeb(request),
                () -> archives.importArchive(body));
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductOverviewController.session(imported));
    }

    @PostMapping("/current/interactions/clear")
    public Map<String, Object> clearCurrentInteractions(HttpServletRequest request) {
        return control.performWrite(requireWeb(request), sessions::clearCurrentInteractions);
    }

    @GetMapping("/{sessionId}/archive")
    public ObjectNode archive(@PathVariable String sessionId, HttpServletRequest request) {
        requireWeb(request);
        return archives.archive(sessionId);
    }

    @GetMapping("/{sessionId}/export")
    public ResponseEntity<ObjectNode> export(
            @PathVariable String sessionId,
            HttpServletRequest request) {
        requireWeb(request);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(sessionId + ".mbsession")
                .build());
        return new ResponseEntity<>(archives.archive(sessionId), headers, HttpStatus.OK);
    }

    @PatchMapping("/{sessionId}")
    public Map<String, Object> rename(
            @PathVariable String sessionId,
            @RequestBody SessionNameRequest body,
            HttpServletRequest request) {
        return ProductOverviewController.session(control.performWrite(
                requireWeb(request),
                () -> sessions.rename(sessionId, body.name())));
    }

    @PostMapping("/{sessionId}/current")
    public Map<String, Object> selectCurrent(
            @PathVariable String sessionId,
            HttpServletRequest request) {
        return ProductOverviewController.session(control.performWrite(requireWeb(request), () -> {
            SessionWorkspace selected = sessions.get(sessionId);
            if (selected.readOnly() || !"local".equals(selected.source())) {
                return sessions.selectCurrent(sessionId);
            }
            if (!selected.current()) {
                control.requireSessionSwitchAllowed();
            }
            return sessions.selectCurrent(sessionId);
        }));
    }

    @DeleteMapping("/{sessionId}")
    public Map<String, Object> delete(
            @PathVariable String sessionId,
            HttpServletRequest request) {
        SessionWorkspace deleted = control.performWrite(requireWeb(request), () -> sessions.delete(sessionId));
        return Map.of(
                "deleted", true,
                "session_id", deleted.sessionId());
    }

    private ControlIdentity requireWeb(HttpServletRequest request) {
        return identities.resolve(request)
                .filter(identity -> "web".equals(identity.controller()))
                .orElseThrow(() -> new ProductException(
                        HttpStatus.FORBIDDEN,
                        "WEB_SESSION_MANAGEMENT_ONLY",
                        "Session 管理只提供给 Web 管理界面"));
    }

    public record SessionNameRequest(String name) {
    }
}
