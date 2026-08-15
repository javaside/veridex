package io.veridex.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.veridex.configuration.application.ConfigurationProfileService;
import io.veridex.configuration.domain.ConfigurationProfile;
import io.veridex.configuration.domain.ConfigurationProfileRepository;
import io.veridex.configuration.domain.ConfigurationProfileVersion;
import io.veridex.configuration.domain.ConfigurationProfileVersionRepository;
import io.veridex.configuration.domain.ProfileConfig;
import io.veridex.configuration.domain.ProfileDefaults;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ConfigurationProfileServiceTest {

    private ConfigurationProfileRepository profiles;
    private ConfigurationProfileVersionRepository versions;
    private ConfigurationProfileService service;

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID PROFILE = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        profiles = mock(ConfigurationProfileRepository.class);
        versions = mock(ConfigurationProfileVersionRepository.class);
        service = new ConfigurationProfileService(profiles, versions, new JsonMapper());
    }

    private static String serialize(ProfileConfig config) {
        try {
            return new JsonMapper().writeValueAsString(config);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void createTrimsNameAndDefaultsMissingDraft() {
        when(profiles.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ConfigurationProfile created = service.create(ACTOR, "  检索基线  ", null, null);
        assertThat(created.getName()).isEqualTo("检索基线");
        assertThat(created.getDraftJson()).contains("\"maxChars\":2000");
    }

    @Test
    void createRejectsBlankName() {
        assertThatThrownBy(() -> service.create(ACTOR, "  ", null, ProfileDefaults.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishRejectsIncompleteConfig() {
        when(profiles.findById(PROFILE)).thenReturn(Optional.of(new ConfigurationProfile("p", null, "{}", ACTOR)));
        assertThatThrownBy(() -> service.publish(PROFILE, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunking");
    }

    @Test
    void publishFreezesDraftIntoVersion() {
        ConfigurationProfile profile = new ConfigurationProfile("p", null,
                serialize(ProfileDefaults.defaults()), ACTOR);
        when(profiles.findById(PROFILE)).thenReturn(Optional.of(profile));
        when(versions.findTopByProfileIdOrderByVersionNoDesc(PROFILE)).thenReturn(Optional.empty());
        when(versions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ConfigurationProfileVersion version = service.publish(PROFILE, ACTOR);

        assertThat(version.getVersionNo()).isEqualTo(1);
        assertThat(version.getConfigJson()).contains("\"chatModel\":\"deterministic\"");
    }

    @Test
    void publishIncrementsVersionNo() {
        ConfigurationProfile profile = new ConfigurationProfile("p", null,
                serialize(ProfileDefaults.defaults()), ACTOR);
        when(profiles.findById(PROFILE)).thenReturn(Optional.of(profile));
        when(versions.findTopByProfileIdOrderByVersionNoDesc(PROFILE)).thenReturn(Optional.of(
                new ConfigurationProfileVersion(PROFILE, 3, "{}", ACTOR)));
        when(versions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ConfigurationProfileVersion version = service.publish(PROFILE, ACTOR);
        assertThat(version.getVersionNo()).isEqualTo(4);
    }
}
