package io.veridex.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.veridex.configuration.application.ConfigurationProfileService;
import io.veridex.configuration.domain.ChunkingConfig;
import io.veridex.configuration.domain.ConfigurationProfile;
import io.veridex.configuration.domain.ConfigurationProfileVersion;
import io.veridex.configuration.domain.GenerationConfig;
import io.veridex.configuration.domain.ModelConfig;
import io.veridex.configuration.domain.ProfileConfig;
import io.veridex.configuration.domain.ProfileDefaults;
import io.veridex.configuration.domain.PromptConfig;
import io.veridex.configuration.domain.RetrievalConfig;
import io.veridex.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ConfigurationProfileIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    ConfigurationProfileService service;

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static ProfileConfig config(int maxChars) {
        return new ProfileConfig(
                new ChunkingConfig(maxChars, 80),
                new RetrievalConfig(30, 60, 6, 3, 4000),
                new GenerationConfig(6, 50),
                new PromptConfig(ProfileDefaults.SYSTEM_TEMPLATE),
                new ModelConfig("deterministic", "deterministic"));
    }

    @Test
    void publishFreezesDraftAndIncrementsVersionNo() {
        ConfigurationProfile profile = service.create(ACTOR, "检索基线", "描述", config(2000));
        ConfigurationProfileVersion v1 = service.publish(profile.getId(), ACTOR);
        assertThat(v1.getVersionNo()).isEqualTo(1);

        service.update(profile.getId(), "检索基线", "描述", config(3000));
        ConfigurationProfileVersion v2 = service.publish(profile.getId(), ACTOR);
        assertThat(v2.getVersionNo()).isEqualTo(2);

        ConfigurationProfileVersion v1Loaded = service.requireVersion(profile.getId(), 1);
        assertThat(v1Loaded.getConfigJson()).contains("\"maxChars\": 2000");
        ConfigurationProfileVersion v2Loaded = service.requireVersion(profile.getId(), 2);
        assertThat(v2Loaded.getConfigJson()).contains("\"maxChars\": 3000");
    }

    @Test
    void publishRejectsIncompleteConfig() {
        ConfigurationProfile profile = service.create(ACTOR, "空配置", null,
                new ProfileConfig(null, null, null, null, null));
        assertThatThrownBy(() -> service.publish(profile.getId(), ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunking");
    }
}
