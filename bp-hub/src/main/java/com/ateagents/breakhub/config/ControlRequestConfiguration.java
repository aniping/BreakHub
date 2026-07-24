package com.ateagents.breakhub.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.ateagents.breakhub.api.ControlIdentityResolver;
import com.ateagents.breakhub.domain.DebugControlService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
public class ControlRequestConfiguration implements WebMvcConfigurer {

    private final ControlIdentityResolver identities;
    private final DebugControlService control;

    public ControlRequestConfiguration(ControlIdentityResolver identities, DebugControlService control) {
        this.identities = identities;
        this.control = control;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public void afterCompletion(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    Object handler,
                    Exception error) {
                if (error == null
                        && response.getStatus() < 400
                        && ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))) {
                    identities.resolve(request).ifPresent(control::touch);
                }
            }
        }).addPathPatterns("/api/**");
    }
}
