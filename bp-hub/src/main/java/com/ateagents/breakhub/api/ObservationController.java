package com.ateagents.breakhub.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ateagents.breakhub.domain.InteractionObservationService;
import com.ateagents.breakhub.domain.ControlIdentity;
import com.ateagents.breakhub.domain.DebugControlService;
import com.ateagents.breakhub.domain.PauseService;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1")
public class ObservationController {

    private final InteractionObservationService observations;
    private final PauseService pauses;
    private final DebugControlService control;
    private final ControlIdentityResolver identities;

    public ObservationController(
            InteractionObservationService observations,
            PauseService pauses,
            DebugControlService control,
            ControlIdentityResolver identities) {
        this.observations = observations;
        this.pauses = pauses;
        this.control = control;
        this.identities = identities;
    }

    @GetMapping("/interfaces")
    public Map<String, Object> interfaces(@RequestParam(defaultValue = "all") String view) {
        return observations.interfaces(view);
    }

    @GetMapping("/interfaces/detail")
    public Map<String, Object> interfaceDetail(
            @RequestParam String object,
            @RequestParam String command) {
        return observations.interfaceDetail(required(object, "object"), required(command, "command"));
    }

    @GetMapping("/interactions")
    public Map<String, Object> interactions() {
        return observations.interactions();
    }

    @GetMapping("/interactions/{interactionId}")
    public Map<String, Object> interaction(@PathVariable String interactionId) {
        return observations.interaction(required(interactionId, "interaction_id"));
    }

    @PostMapping("/interactions/{interactionId}/continue")
    public Map<String, Object> continueInteraction(
            @PathVariable String interactionId,
            @RequestBody JsonNode body,
            HttpServletRequest request) {
        String pausePoint = required(body.path("pause_point").asText(""), "pause_point");
        if (!("before".equals(pausePoint) || "after".equals(pausePoint))) {
            throw BusinessInteractionController.invalid("pause_point 只能是 before 或 after");
        }
        ControlIdentity actor = identities.require(request);
        return control.performWrite(actor, () -> pauses.continuePause(required(interactionId, "interaction_id"), pausePoint));
    }

    @PostMapping("/interactions/continue-selected")
    public Map<String, Object> continueSelectedInteractions(
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
        List<PauseService.PauseTarget> targets = selectedTargets(body);
        ControlIdentity actor = identities.require(request);
        return control.performWrite(actor, () -> pauses.continueSelected(targets));
    }

    @PostMapping("/interactions/continue")
    public Map<String, Object> continueInteractions(
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
        if (body != null && !body.isNull() && !(body.isObject() && body.isEmpty())) {
            throw BusinessInteractionController.invalid("continue_interactions 不接受 ID 列表或其他请求参数");
        }
        ControlIdentity actor = identities.require(request);
        return control.performWrite(actor, pauses::continueAll);
    }

    @PostMapping("/interactions/{interactionId}/inject")
    public Map<String, Object> injectInteraction(
            @PathVariable String interactionId,
            @RequestBody JsonNode body,
            HttpServletRequest request) {
        String pausePoint = required(body.path("pause_point").asText(""), "pause_point");
        if (!("before".equals(pausePoint) || "after".equals(pausePoint))) {
            throw BusinessInteractionController.invalid("pause_point 只能是 before 或 after");
        }
        if (!body.has("changes")) {
            throw BusinessInteractionController.invalid("changes 必须存在");
        }
        ControlIdentity actor = identities.require(request);
        return control.performWrite(
                actor,
                () -> pauses.inject(
                        required(interactionId, "interaction_id"),
                        pausePoint,
                        body.get("changes")));
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw BusinessInteractionController.invalid(field + " 必须为 1 到 200 个字符");
        }
        return normalized;
    }

    private static List<PauseService.PauseTarget> selectedTargets(JsonNode body) {
        if (body == null || !body.isObject() || !body.path("targets").isArray()
                || body.path("targets").isEmpty()) {
            throw invalidSelection("targets 必须是非空数组");
        }
        List<PauseService.PauseTarget> targets = new ArrayList<>();
        Set<PauseService.PauseTarget> unique = new HashSet<>();
        for (JsonNode item : body.path("targets")) {
            if (!item.isObject()) {
                throw invalidSelection("targets 中的每一项都必须是对象");
            }
            String interactionId = selectedRequired(item.path("interaction_id").asText(""), "interaction_id");
            String pausePoint = selectedRequired(item.path("pause_point").asText(""), "pause_point");
            if (!("before".equals(pausePoint) || "after".equals(pausePoint))) {
                throw invalidSelection("pause_point 只能是 before 或 after");
            }
            PauseService.PauseTarget target = new PauseService.PauseTarget(interactionId, pausePoint);
            if (!unique.add(target)) {
                throw invalidSelection("targets 不能包含重复目标");
            }
            targets.add(target);
        }
        return List.copyOf(targets);
    }

    private static String selectedRequired(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw invalidSelection(field + " 必须为 1 到 200 个字符");
        }
        return normalized;
    }

    private static ProductException invalidSelection(String message) {
        return new ProductException(HttpStatus.BAD_REQUEST, "INVALID_CONTINUE_SELECTION", message);
    }
}
