package com.ateagents.breakhub.config;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
public class SecurityConfiguration {

    @Bean
    UserDetailsService userDetailsService(ProductProperties properties) {
        ProductProperties.Security security = properties.security();
        return new InMemoryUserDetailsManager(User.withUsername(security.webUsername())
                .password("{noop}" + security.webPassword())
                .roles("ADMIN")
                .build());
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    @Order(1)
    SecurityFilterChain bearerSecurityFilterChain(
            HttpSecurity http,
            ProductProperties properties) throws Exception {
        http
                .securityMatcher(SecurityConfiguration::hasBearerToken)
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/business/**").hasRole("BUSINESS")
                        .requestMatchers(HttpMethod.POST, "/api/v1/interactions/continue-selected").denyAll()
                        .requestMatchers("/api/**").hasRole("GATEWAY")
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> jsonError(
                                response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "接口密钥无效"))
                        .accessDeniedHandler((request, response, error) -> jsonError(
                                response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "无权访问该接口")))
                .addFilterBefore(new ApiTokenAuthenticationFilter(properties), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityContextRepository securityContextRepository) throws Exception {
        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieName("MBP-XSRF-TOKEN");
        csrfRepository.setHeaderName("X-MBP-XSRF-TOKEN");

        RequestMatcher browserWrite = request -> isUnsafe(request.getMethod())
                && !"/api/auth/login".equals(request.getRequestURI());

        http
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .securityContext(context -> context.securityContextRepository(securityContextRepository)
                        .requireExplicitSave(true))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .requireCsrfProtectionMatcher(browserWrite))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/auth/login", "/api/health", "/", "/index.html", "/assets/**").permitAll()
                        .requestMatchers("/api/business/**").hasRole("BUSINESS")
                        .requestMatchers("/api/auth/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/interactions/continue-selected").hasRole("ADMIN")
                        .requestMatchers("/api/**").hasAnyRole("ADMIN", "GATEWAY")
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, error) -> jsonError(
                                response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "请先登录"))
                        .accessDeniedHandler((request, response, error) -> jsonError(
                                response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "无权访问该接口")));
        return http.build();
    }

    private static boolean hasBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.startsWith("Bearer ");
    }

    private static boolean isUnsafe(String method) {
        return !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method) || "TRACE".equals(method));
    }

    private static void jsonError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
