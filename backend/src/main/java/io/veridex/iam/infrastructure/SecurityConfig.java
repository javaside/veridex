package io.veridex.iam.infrastructure;

import io.veridex.iam.application.ApiKeyService;
import io.veridex.iam.domain.PlatformUser;
import io.veridex.shared.infrastructure.security.SecurityProperties;
import io.veridex.shared.infrastructure.security.SecurityResponseHeaders;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JsonMapper jsonMapper;
    private final ApiKeyService apiKeyService;
    private final ApiKeyScopeAuthorizationManager scopeAuthorizationManager;
    private final SecurityProperties securityProperties;

    public SecurityConfig(JsonMapper jsonMapper, ApiKeyService apiKeyService,
                          ApiKeyScopeAuthorizationManager scopeAuthorizationManager,
                          SecurityProperties securityProperties) {
        this.jsonMapper = jsonMapper;
        this.apiKeyService = apiKeyService;
        this.scopeAuthorizationManager = scopeAuthorizationManager;
        this.securityProperties = securityProperties;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        SecurityResponseHeaders.configure(http);
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf
                .spa()
                .ignoringRequestMatchers("/api/auth/login", "/api/auth/logout")
                .ignoringRequestMatchers(this::isBearerApiKeyRequest))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").access(scopeAuthorizationManager::authorizeNonApiKey)
                .requestMatchers("/api/auth/login", "/api/auth/logout", "/api/auth/csrf")
                    .access(scopeAuthorizationManager::authorizeNonApiKey)
                .requestMatchers(HttpMethod.OPTIONS, "/**").access(scopeAuthorizationManager::authorizeOptions)
                .requestMatchers("/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**")
                    .access(scopeAuthorizationManager::authorizeAdminSession)
                .requestMatchers(HttpMethod.GET, "/api/traces/**")
                    .access(scopeAuthorizationManager::authorizePlatformAdminSession)
                .anyRequest().access(scopeAuthorizationManager))
            .exceptionHandling(exceptions -> exceptions
                .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        request -> request.getRequestURI().startsWith("/v3/api-docs"))
                .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"),
                        request -> request.getRequestURI().startsWith("/api/"))
                .accessDeniedHandler((request, response, denied) -> {
                if (org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()
                        instanceof ApiKeyAuthFilter.ApiKeyAuthentication) {
                    response.setStatus(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"error\":\"insufficient_scope\"}");
                    return;
                }
                response.sendError(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
            }))
            .addFilterBefore(new ApiKeyAuthFilter(apiKeyService), UsernamePasswordAuthenticationFilter.class)
            .formLogin(form -> form
                .loginProcessingUrl("/api/auth/login")
                .successHandler((req, res, auth) -> {
                    res.setStatus(200);
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    if (auth.getPrincipal() instanceof PlatformUserDetails details) {
                        PlatformUser user = details.user();
                        res.getWriter().write(jsonMapper.writeValueAsString(Map.of(
                                "id", user.getId().toString(),
                                "username", user.getUsername(),
                                "displayName", user.getDisplayName(),
                                "role", user.getRole().name())));
                    }
                })
                .failureHandler((req, res, exc) -> res.setStatus(401)))
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)));
        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(securityProperties.corsAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Authorization", "X-Request-Id"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    private boolean isBearerApiKeyRequest(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.startsWith("Bearer vd_");
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
