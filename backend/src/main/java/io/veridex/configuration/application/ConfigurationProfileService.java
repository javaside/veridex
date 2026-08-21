package io.veridex.configuration.application;

import io.veridex.configuration.api.ProfileConfig;
import io.veridex.configuration.domain.ConfigurationProfile;
import io.veridex.configuration.domain.ConfigurationProfileRepository;
import io.veridex.configuration.domain.ConfigurationProfileVersion;
import io.veridex.configuration.domain.ConfigurationProfileVersionRepository;
import io.veridex.configuration.domain.ProfileDefaults;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional
public class ConfigurationProfileService {

    private final ConfigurationProfileRepository profiles;
    private final ConfigurationProfileVersionRepository versions;
    private final JsonMapper jsonMapper;

    public ConfigurationProfileService(ConfigurationProfileRepository profiles,
                                       ConfigurationProfileVersionRepository versions,
                                       JsonMapper jsonMapper) {
        this.profiles = profiles;
        this.versions = versions;
        this.jsonMapper = jsonMapper;
    }

    public ConfigurationProfile create(UUID actorId, String name, String description, ProfileConfig draft) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        ProfileConfig effective = draft == null ? ProfileDefaults.defaults() : draft;
        return profiles.save(new ConfigurationProfile(trimmed, description, serialize(effective), actorId));
    }

    public ConfigurationProfile require(UUID profileId) {
        return profiles.findById(profileId)
                .orElseThrow(() -> new IllegalArgumentException("unknown profile " + profileId));
    }

    public List<ConfigurationProfile> listAll() {
        return profiles.findAllByOrderByCreatedAtDesc();
    }

    public ConfigurationProfile update(UUID profileId, String name, String description, ProfileConfig draft) {
        ConfigurationProfile existing = require(profileId);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        validateConfig(draft);
        existing.update(trimmed, description, serialize(draft));
        return profiles.save(existing);
    }

    public ConfigurationProfileVersion publish(UUID profileId, UUID actorId) {
        ConfigurationProfile existing = require(profileId);
        ProfileConfig config = deserialize(existing.getDraftJson());
        validateConfig(config);
        int nextVersionNo = versions.findTopByProfileIdOrderByVersionNoDesc(profileId)
                .map(v -> v.getVersionNo() + 1)
                .orElse(1);
        return versions.save(new ConfigurationProfileVersion(profileId, nextVersionNo,
                serialize(config), actorId));
    }

    public List<ConfigurationProfileVersion> listVersions(UUID profileId) {
        require(profileId);
        return versions.findByProfileIdOrderByVersionNoDesc(profileId);
    }

    public ConfigurationProfileVersion requireVersion(UUID profileId, int versionNo) {
        return versions.findByProfileIdAndVersionNo(profileId, versionNo)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown version " + versionNo + " for profile " + profileId));
    }

    /** 把某个已发布版本设为「当前生效」，并清空其它 profile 的生效标记（全局唯一）。 */
    public void activateVersion(UUID profileId, int versionNo) {
        ConfigurationProfile profile = require(profileId);
        requireVersion(profileId, versionNo);
        for (ConfigurationProfile other : profiles.findAll()) {
            if (!other.getId().equals(profileId) && other.getActiveVersionNo() != null) {
                other.setActiveVersionNo(null);
                profiles.save(other);
            }
        }
        profile.setActiveVersionNo(versionNo);
        profiles.save(profile);
    }

    public Integer latestVersionNo(UUID profileId) {
        return versions.findTopByProfileIdOrderByVersionNoDesc(profileId)
                .map(ConfigurationProfileVersion::getVersionNo)
                .orElse(null);
    }

    private void validateConfig(ProfileConfig config) {
        if (config == null || config.chunking() == null) {
            throw new IllegalArgumentException("config.chunking is required");
        }
        if (config.retrieval() == null) {
            throw new IllegalArgumentException("config.retrieval is required");
        }
        if (config.generation() == null) {
            throw new IllegalArgumentException("config.generation is required");
        }
        if (config.prompt() == null || config.prompt().systemTemplate() == null
                || config.prompt().systemTemplate().isBlank()) {
            throw new IllegalArgumentException("config.prompt.systemTemplate is required");
        }
        if (config.model() == null || config.model().chatModel() == null
                || config.model().chatModel().isBlank()
                || config.model().embeddingModel() == null
                || config.model().embeddingModel().isBlank()) {
            throw new IllegalArgumentException("config.model.chatModel and embeddingModel are required");
        }
    }

    private String serialize(ProfileConfig config) {
        try {
            return jsonMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize profile config", e);
        }
    }

    private ProfileConfig deserialize(String json) {
        try {
            return jsonMapper.readValue(json, ProfileConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize profile config", e);
        }
    }
}
