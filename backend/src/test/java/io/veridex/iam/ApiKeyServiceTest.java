package io.veridex.iam;

import io.veridex.iam.domain.ApiKeyScope;
import io.veridex.iam.domain.ApiKeyTokenGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyServiceTest {

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
}
