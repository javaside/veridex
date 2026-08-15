package io.veridex.iam;

import io.veridex.iam.api.ApiKeyCreatedView;
import io.veridex.iam.application.ApiKeyService;
import io.veridex.iam.domain.ApiKey;
import io.veridex.iam.domain.ApiKeyRepository;
import io.veridex.iam.domain.ApiKeyScope;
import io.veridex.iam.domain.ApiKeyTokenGenerator;
import io.veridex.iam.domain.PlatformUserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyServiceTest {

    private static final UUID ACTOR_ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR_KADMIN = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void issuedTokenHasVdPrefixHashAndSafePrefix() {
        ApiKeyTokenGenerator.PlainToken t = new ApiKeyTokenGenerator().issue();
        assertThat(t.token()).startsWith("vd_").hasSize(46); // vd_ + 43
        assertThat(t.token()).matches("vd_[A-Za-z0-9_-]{43}");
        assertThat(t.hash()).hasSize(64).doesNotContain(t.token());
        assertThat(t.prefix()).isEqualTo(t.token().substring(0, 8));
    }

    @Test
    void sameTokenAlwaysHashesIdentically() {
        ApiKeyTokenGenerator gen = new ApiKeyTokenGenerator();
        String token = gen.issue().token();
        assertThat(gen.hash(token)).isEqualTo(gen.hash(token)).hasSize(64);
    }

    @Test
    void scopeMatchesNamespacePaths() {
        assertThat(ApiKeyScope.QA.allows("POST", "/api/qa/ask")).isTrue();
        assertThat(ApiKeyScope.QA.allows("GET", "/api/qa/conversations")).isTrue();
        assertThat(ApiKeyScope.QA.allows("GET", "/api/evaluation/datasets")).isFalse();

        assertThat(ApiKeyScope.KNOWLEDGE_READ.allows("GET", "/api/knowledge-bases")).isTrue();
        assertThat(ApiKeyScope.KNOWLEDGE_READ.allows("GET", "/api/documents/x/versions/y/chunks")).isTrue();
        assertThat(ApiKeyScope.KNOWLEDGE_READ.allows("POST", "/api/knowledge-bases")).isFalse();
        assertThat(ApiKeyScope.KNOWLEDGE_WRITE.allows("POST", "/api/knowledge-bases")).isTrue();
        assertThat(ApiKeyScope.KNOWLEDGE_WRITE.allows("GET", "/api/knowledge-bases")).isFalse();

        assertThat(ApiKeyScope.CONFIGURATION.allows("PUT", "/api/configuration/profiles/x")).isTrue();
        assertThat(ApiKeyScope.EVALUATION.allows("POST", "/api/evaluation/runs")).isTrue();
        assertThat(ApiKeyScope.FEEDBACK.allows("POST", "/api/feedback")).isTrue();
    }

    @Test
    void scopeNeverMatchesKeyManagementOrDocsPaths() {
        for (ApiKeyScope scope : ApiKeyScope.values()) {
            assertThat(scope.allows("POST", "/api/iam/keys")).isFalse();
            assertThat(scope.allows("GET", "/api/iam/keys")).isFalse();
            assertThat(scope.allows("GET", "/v3/api-docs")).isFalse();
            assertThat(scope.allows("GET", "/actuator/health")).isFalse();
        }
    }

    @Test
    void createHashesTokenAndStoresScopes() {
        ApiKeyRepository repo = mock(ApiKeyRepository.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ApiKeyService service = new ApiKeyService(repo, mock(PlatformUserRepository.class), new ApiKeyTokenGenerator());

        ApiKeyCreatedView created = service.createFor(ACTOR_ADMIN, "PLATFORM_ADMIN", ACTOR_ADMIN, "集成用", List.of("qa"));

        assertThat(created.token()).startsWith("vd_");
        assertThat(created.scopes()).isEqualTo(List.of("QA"));
        verify(repo).save(argThat((ApiKey k) ->
                k.getTokenHash().length() == 64
                        && !k.getTokenHash().equals(created.token())
                        && k.getTokenPrefix().equals(created.token().substring(0, 8))));
    }

    @Test
    void createRejectsUnknownScope() {
        ApiKeyService service = new ApiKeyService(mock(ApiKeyRepository.class),
                mock(PlatformUserRepository.class), new ApiKeyTokenGenerator());
        assertThatThrownBy(() -> service.createFor(ACTOR_ADMIN, "PLATFORM_ADMIN", ACTOR_ADMIN, "x", List.of("root")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createRejectsBlankNameAndEmptyScopes() {
        ApiKeyService service = new ApiKeyService(mock(ApiKeyRepository.class),
                mock(PlatformUserRepository.class), new ApiKeyTokenGenerator());
        assertThatThrownBy(() -> service.createFor(ACTOR_ADMIN, "PLATFORM_ADMIN", ACTOR_ADMIN, " ", List.of("qa")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createFor(ACTOR_ADMIN, "PLATFORM_ADMIN", ACTOR_ADMIN, "x", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void knowledgeAdminCanOnlyCreateForSelf() {
        ApiKeyService service = new ApiKeyService(mock(ApiKeyRepository.class),
                mock(PlatformUserRepository.class), new ApiKeyTokenGenerator());

        assertThatThrownBy(() -> service.createFor(ACTOR_KADMIN, "KNOWLEDGE_ADMIN", ACTOR_ADMIN, "x", List.of("qa")))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("targetUserId");
    }

    @Test
    void knowledgeAdminCanOnlyRevokeOwnKeys() {
        ApiKeyRepository repo = mock(ApiKeyRepository.class);
        ApiKeyService service = new ApiKeyService(repo, mock(PlatformUserRepository.class), new ApiKeyTokenGenerator());

        ApiKey others = new ApiKey(ACTOR_ADMIN, "n", "h", "vd_12345", "qa");
        when(repo.findById(any())).thenReturn(Optional.of(others));
        assertThatThrownBy(() -> service.revoke(ACTOR_KADMIN, "KNOWLEDGE_ADMIN", UUID.randomUUID()))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void authenticateReturnsEmptyForRevokedOrUnknown() {
        ApiKeyRepository repo = mock(ApiKeyRepository.class);
        PlatformUserRepository users = mock(PlatformUserRepository.class);
        ApiKeyTokenGenerator generator = new ApiKeyTokenGenerator();
        ApiKeyService service = new ApiKeyService(repo, users, generator);

        String token = generator.issue().token();
        when(repo.findByTokenHash(generator.hash(token))).thenReturn(Optional.empty());
        assertThat(service.authenticate(token)).isEmpty();

        ApiKey revoked = new ApiKey(ACTOR_ADMIN, "n", "h", "vd_12345", "qa");
        revoked.revoke();
        when(repo.findByTokenHash(any())).thenReturn(Optional.of(revoked));
        assertThat(service.authenticate(token)).isEmpty();
    }
}
