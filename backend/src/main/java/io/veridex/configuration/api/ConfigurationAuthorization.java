package io.veridex.configuration.api;

import io.veridex.iam.api.Role;
import io.veridex.iam.api.SecurityContextRole;
import org.springframework.stereotype.Component;

@Component
public class ConfigurationAuthorization {

    public boolean isAdmin() {
        Role role = SecurityContextRole.currentRole();
        return role == Role.PLATFORM_ADMIN || role == Role.KNOWLEDGE_ADMIN;
    }
}
