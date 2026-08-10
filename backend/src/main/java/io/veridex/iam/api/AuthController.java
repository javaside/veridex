package io.veridex.iam.api;

import io.veridex.iam.domain.PlatformUser;
import io.veridex.iam.infrastructure.PlatformUserDetails;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @GetMapping("/me")
    public Map<String, Object> me(@AuthenticationPrincipal PlatformUserDetails principal) {
        PlatformUser user = principal.user();
        return Map.of(
                "id", user.getId().toString(),
                "username", user.getUsername(),
                "displayName", user.getDisplayName(),
                "role", user.getRole().name());
    }
}
