package io.veridex.configuration.application;

import io.veridex.configuration.api.ConfigurationProfileQuery;
import io.veridex.configuration.api.ProfileConfig;
import io.veridex.configuration.domain.ConfigurationProfileRepository;
import io.veridex.configuration.domain.ConfigurationProfileVersionRepository;
import io.veridex.configuration.domain.ProfileDefaults;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ConfigurationProfileQueryImpl implements ConfigurationProfileQuery {

    private final ConfigurationProfileRepository profiles;
    private final ConfigurationProfileVersionRepository versions;
    private final JsonMapper jsonMapper;

    public ConfigurationProfileQueryImpl(ConfigurationProfileRepository profiles,
                                         ConfigurationProfileVersionRepository versions, JsonMapper jsonMapper) {
        this.profiles = profiles;
        this.versions = versions;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ProfileConfig requireVersionConfig(UUID profileId, int versionNo) {
        var version = versions.findByProfileIdAndVersionNo(profileId, versionNo)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown profile version " + versionNo + " for " + profileId));
        return deserialize(version.getConfigJson());
    }

    @Override
    public ProfileConfig activeProfileConfig() {
        for (var profile : profiles.findAllByOrderByCreatedAtDesc()) {
            Integer activeVersionNo = profile.getActiveVersionNo();
            if (activeVersionNo == null) {
                continue;
            }
            var version = versions.findByProfileIdAndVersionNo(profile.getId(), activeVersionNo);
            if (version.isPresent()) {
                return deserialize(version.get().getConfigJson());
            }
        }
        return ProfileDefaults.defaults();
    }

    private ProfileConfig deserialize(String configJson) {
        try {
            return jsonMapper.readValue(configJson, ProfileConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize profile config", e);
        }
    }
}
