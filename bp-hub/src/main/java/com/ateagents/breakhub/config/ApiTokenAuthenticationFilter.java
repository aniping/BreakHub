package com.ateagents.breakhub.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public final class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final ProductProperties properties;

    public ApiTokenAuthenticationFilter(ProductProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authorization.substring(PREFIX.length());
        ProductProperties.Security security = properties.security();
        UsernamePasswordAuthenticationToken authentication;
        if (same(token, security.gatewayToken())) {
            authentication = authenticated("mcp-gateway", "ROLE_GATEWAY");
        } else if (same(token, security.businessClientToken())) {
            authentication = authenticated("business-client", "ROLE_BUSINESS");
        } else {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"INVALID_TOKEN\",\"message\":\"接口密钥无效\"}");
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        filterChain.doFilter(request, response);
    }

    private static UsernamePasswordAuthenticationToken authenticated(String principal, String role) {
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority(role)));
    }

    private static boolean same(String actual, String expected) {
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
