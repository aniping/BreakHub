package com.ateagents.breakhub.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ateagents.breakhub.domain.DebugControlService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final ControlIdentityResolver identities;
    private final DebugControlService control;

    public AuthController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            ControlIdentityResolver identities,
            DebugControlService control) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.identities = identities;
        this.control = control;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody LoginRequest body,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);
            return ResponseEntity.ok(Map.of("authenticated", true, "username", authentication.getName()));
        } catch (AuthenticationException error) {
            return ResponseEntity.status(401).body(Map.of(
                    "code", "INVALID_CREDENTIALS",
                    "message", "用户名或密码错误"));
        }
    }

    @GetMapping("/session")
    public Map<String, Object> session(Authentication authentication, CsrfToken csrfToken) {
        return Map.of(
                "authenticated", true,
                "username", authentication.getName(),
                "csrf_token", csrfToken.getToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        identities.resolve(request).ifPresent(actor -> control.releaseIfOwner(actor, "web_logout"));
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    public record LoginRequest(String username, String password) {
    }
}
