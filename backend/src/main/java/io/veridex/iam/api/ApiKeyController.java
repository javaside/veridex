package io.veridex.iam.api;

import io.veridex.iam.application.ApiKeyService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/iam/keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyCreatedView create(@RequestBody CreateKeyRequest request) {
        requireAdmin();
        UUID actorId = CurrentActor.id();
        UUID targetUserId = request.userId() == null ? actorId : UUID.fromString(request.userId());
        return apiKeyService.createFor(actorId, CurrentActor.role().name(), targetUserId,
                request.name(), request.scopes());
    }

    @GetMapping
    public List<ApiKeyView> list() {
        requireAdmin();
        return apiKeyService.listFor(CurrentActor.id(), CurrentActor.role().name());
    }

    @DeleteMapping("/{keyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID keyId) {
        requireAdmin();
        apiKeyService.revoke(CurrentActor.id(), CurrentActor.role().name(), keyId);
    }

    private static void requireAdmin() {
        Role role = CurrentActor.role();
        if (role != Role.PLATFORM_ADMIN && role != Role.KNOWLEDGE_ADMIN) {
            throw new SecurityException("admin role required");
        }
    }
}
