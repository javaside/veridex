package io.veridex.configuration.api;

import io.veridex.configuration.application.ConfigurationProfileService;
import io.veridex.configuration.domain.ConfigurationProfile;
import io.veridex.configuration.domain.ConfigurationProfileVersion;
import io.veridex.iam.api.CurrentActor;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/configuration/profiles")
public class ConfigurationController {

    private final ConfigurationProfileService service;
    private final ConfigurationAuthorization authorization;
    private final JsonMapper jsonMapper;

    public ConfigurationController(ConfigurationProfileService service,
                                   ConfigurationAuthorization authorization,
                                   JsonMapper jsonMapper) {
        this.service = service;
        this.authorization = authorization;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping
    public ResponseEntity<ProfileDetailView> create(@RequestBody CreateProfileRequest body) {
        requireAdmin();
        ConfigurationProfile profile = service.create(CurrentActor.id(), body.name(), body.description(), body.draft());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetail(profile));
    }

    @GetMapping
    public List<ProfileView> list() {
        requireAdmin();
        return service.listAll().stream().map(this::toView).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProfileDetailView> get(@PathVariable UUID id) {
        requireAdmin();
        return ResponseEntity.ok(toDetail(service.require(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProfileDetailView> update(@PathVariable UUID id, @RequestBody UpdateProfileRequest body) {
        requireAdmin();
        ConfigurationProfile profile = service.update(id, body.name(), body.description(), body.draft());
        return ResponseEntity.ok(toDetail(profile));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<PublishResult> publish(@PathVariable UUID id) {
        requireAdmin();
        ConfigurationProfileVersion version = service.publish(id, CurrentActor.id());
        return ResponseEntity.ok(new PublishResult(version.getId(), version.getVersionNo()));
    }

    @GetMapping("/{id}/versions")
    public List<VersionView> listVersions(@PathVariable UUID id) {
        requireAdmin();
        return service.listVersions(id).stream().map(this::toVersionView).toList();
    }

    @GetMapping("/{id}/versions/{versionNo}")
    public ResponseEntity<VersionDetailView> version(@PathVariable UUID id, @PathVariable int versionNo) {
        requireAdmin();
        ConfigurationProfileVersion version = service.requireVersion(id, versionNo);
        return ResponseEntity.ok(new VersionDetailView(version.getId(), version.getVersionNo(),
                version.getCreatedAt().toString(), deserialize(version.getConfigJson())));
    }

    private void requireAdmin() {
        if (!authorization.isAdmin()) {
            throw new SecurityException("configuration management requires admin role");
        }
    }

    private ProfileView toView(ConfigurationProfile p) {
        return new ProfileView(p.getId(), p.getName(), p.getDescription(),
                (int) service.listVersions(p.getId()).size(), service.latestVersionNo(p.getId()));
    }

    private ProfileDetailView toDetail(ConfigurationProfile p) {
        return new ProfileDetailView(p.getId(), p.getName(), p.getDescription(), deserialize(p.getDraftJson()));
    }

    private VersionView toVersionView(ConfigurationProfileVersion v) {
        return new VersionView(v.getId(), v.getVersionNo(), v.getCreatedAt().toString());
    }

    private ProfileConfig deserialize(String json) {
        try {
            return jsonMapper.readValue(json, ProfileConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize profile config", e);
        }
    }
}
