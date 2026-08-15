package io.veridex.configuration.application;

import io.veridex.configuration.api.ConfigurationProfileQuery;
import io.veridex.configuration.api.ProfileConfig;
import io.veridex.configuration.domain.ConfigurationProfileVersionRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ConfigurationProfileQueryImpl implements ConfigurationProfileQuery {

    private final ConfigurationProfileVersionRepository versions;
    private final JsonMapper jsonMapper;

    public ConfigurationProfileQueryImpl(ConfigurationProfileVersionRepository versions, JsonMapper jsonMapper) {
        this.versions = versions;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ProfileConfig requireVersionConfig(UUID profileId, int versionNo) {
        var version = versions.findByProfileIdAndVersionNo(profileId, versionNo)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown profile version " + versionNo + " for " + profileId));
        try {
            return jsonMapper.readValue(version.getConfigJson(), ProfileConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize profile config", e);
        }
    }
}
