package com.ateagents.breakhub.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ateagents.breakhub.domain.ControlIdentity;
import com.ateagents.breakhub.domain.CurrentSessionService;
import com.ateagents.breakhub.domain.DebugControlService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1")
public class DebugControlController {

    private final DebugControlService control;
    private final ControlIdentityResolver identities;
    private final CurrentSessionService sessions;

    public DebugControlController(
            DebugControlService control,
            ControlIdentityResolver identities,
            CurrentSessionService sessions) {
        this.control = control;
        this.identities = identities;
        this.sessions = sessions;
    }

    @GetMapping("/control")
    public Map<String, Object> control(HttpServletRequest request) {
        return control.controlSnapshot(identities.resolve(request));
    }

    @PostMapping("/control/heartbeat")
    public Map<String, Object> heartbeat(HttpServletRequest request) {
        return control.heartbeat(identities.require(request));
    }

    @PostMapping("/control/release")
    public Map<String, Object> release(HttpServletRequest request) {
        return control.release(identities.require(request));
    }

    @PostMapping("/debugging/start")
    public Map<String, Object> start(HttpServletRequest request) {
        ControlIdentity actor = identities.require(request);
        return control.start(actor, () -> sessions.current().sessionId());
    }

    @PostMapping("/debugging/stop")
    public Map<String, Object> stop(HttpServletRequest request) {
        ControlIdentity actor = identities.require(request);
        return control.stop(actor, sessions.current().sessionId());
    }
}
