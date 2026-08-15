package io.veridex.iam.infrastructure;

import io.veridex.iam.application.ApiKeyService;
import io.veridex.iam.domain.PlatformUser;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JsonMapper jsonMapper;
    private final ApiKeyService apiKeyService;
    private final ApiKeyScopeAuthorizationManager scopeAuthorizationManager;

    public SecurityConfig(JsonMapper jsonMapper, ApiKeyService apiKeyService,
                          ApiKeyScopeAuthorizationManager scopeAuthorizationManager) {
        this.jsonMapper = jsonMapper;
        this.apiKeyService = apiKeyService;
        this.scopeAuthorizationManager = scopeAuthorizationManager;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").access(scopeAuthorizationManager::authorizeNonApiKey)
                .requestMatchers("/api/auth/login", "/api/auth/logout")
                    .access(scopeAuthorizationManager::authorizeNonApiKey)
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/v3/api-docs/**").access(scopeAuthorizationManager::authorizeAdminSession)
                .anyRequest().access(scopeAuthorizationManager))
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
                .failureHandler((req, res, exc) -> res.sendError(401)))
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
