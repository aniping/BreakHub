package com.ateagents.breakhub.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ateagents.breakhub.domain.BreakpointService;
import com.ateagents.breakhub.domain.BreakpointService.BreakpointDefinition;
import com.ateagents.breakhub.domain.BreakpointService.BreakpointPatch;
import com.ateagents.breakhub.domain.ControlIdentity;
import com.ateagents.breakhub.domain.DebugControlService;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/breakpoints")
public class BreakpointController {

    private final BreakpointService breakpoints;
    private final DebugControlService control;
    private final ControlIdentityResolver identities;

    public BreakpointController(
            BreakpointService breakpoints,
            DebugControlService control,
            ControlIdentityResolver identities) {
        this.breakpoints = breakpoints;
        this.control = control;
        this.identities = identities;
    }

    @GetMapping
    public Map<String, Object> list() {
        return breakpoints.list();
    }

    @GetMapping("/{breakpointId}")
    public Map<String, Object> get(@PathVariable String breakpointId) {
        return breakpoints.get(requiredId(breakpointId));
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody JsonNode body, HttpServletRequest request) {
        ControlIdentity actor = identities.require(request);
        BreakpointDefinition definition = new BreakpointDefinition(
                optionalText(body, "name"),
                optionalText(body, "object"),
                optionalText(body, "command"),
                optionalText(body, "pause_point"),
                body.get("conditions"));
        return control.performWrite(actor, () -> breakpoints.create(definition));
    }

    @PatchMapping("/{breakpointId}")
    public Map<String, Object> update(
            @PathVariable String breakpointId,
            @RequestBody JsonNode body,
            HttpServletRequest request) {
        ControlIdentity actor = identities.require(request);
        BreakpointPatch patch = new BreakpointPatch(
                body.has("name"),
                optionalText(body, "name"),
                optionalText(body, "object"),
                optionalText(body, "command"),
                optionalText(body, "pause_point"),
                body.get("conditions"));
        return control.performWrite(actor, () -> breakpoints.update(requiredId(breakpointId), patch));
    }

    @PostMapping("/{breakpointId}/enable")
    public Map<String, Object> enable(@PathVariable String breakpointId, HttpServletRequest request) {
        return setEnabled(requiredId(breakpointId), true, request);
    }

    @PostMapping("/{breakpointId}/disable")
    public Map<String, Object> disable(@PathVariable String breakpointId, HttpServletRequest request) {
        return setEnabled(requiredId(breakpointId), false, request);
    }

    @DeleteMapping("/{breakpointId}")
    public Map<String, Object> delete(@PathVariable String breakpointId, HttpServletRequest request) {
        ControlIdentity actor = identities.require(request);
        return control.performWrite(actor, () -> breakpoints.delete(requiredId(breakpointId)));
    }

    @DeleteMapping
    public Map<String, Object> deleteAll(HttpServletRequest request) {
        ControlIdentity actor = identities.require(request);
        return control.performWrite(actor, breakpoints::deleteAll);
    }

    private Map<String, Object> setEnabled(String breakpointId, boolean enabled, HttpServletRequest request) {
        ControlIdentity actor = identities.require(request);
        return control.performWrite(actor, () -> breakpoints.setEnabled(breakpointId, enabled));
    }

    private static String optionalText(JsonNode body, String field) {
        if (!body.has(field) || body.get(field).isNull()) {
            return null;
        }
        if (!body.get(field).isTextual()) {
            throw new ProductException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_BREAKPOINT_DEFINITION",
                    field + " 必须是字符串");
        }
        return body.get(field).asText();
    }

    private static String requiredId(String value) {
        String id = value == null ? "" : value.trim();
        if (id.isEmpty() || id.length() > 200) {
            throw new ProductException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_BREAKPOINT_ID",
                    "breakpoint_id 必须为 1 到 200 个字符");
        }
        return id;
    }
}
