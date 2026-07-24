package com.ateagents.breakhub.api;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.ateagents.breakhub.domain.ControlIdentity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

@Component
public class ControlIdentityResolver {

    public static final String GATEWAY_INSTANCE_HEADER = "X-MBP-Control-Instance";

    public Optional<ControlIdentity> resolve(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        boolean gateway = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_GATEWAY".equals(authority.getAuthority()));
        if (gateway) {
            String instanceId = request.getHeader(GATEWAY_INSTANCE_HEADER);
            return valid(instanceId) ? Optional.of(new ControlIdentity("mcp", instanceId)) : Optional.empty();
        }
        boolean web = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        HttpSession session = request.getSession(false);
        if (web && session != null) {
            return Optional.of(new ControlIdentity("web", session.getId()));
        }
        return Optional.empty();
    }

    public ControlIdentity require(HttpServletRequest request) {
        return resolve(request).orElseThrow(() -> new ProductException(
                HttpStatus.BAD_REQUEST,
                "CONTROL_INSTANCE_REQUIRED",
                "写操作需要具体控制实例"));
    }

    private static boolean valid(String value) {
        return value != null && !value.isBlank() && value.length() <= 128;
    }
}
