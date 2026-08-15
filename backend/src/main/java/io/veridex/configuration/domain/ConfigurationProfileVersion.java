package io.veridex.configuration.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "configuration_profile_version")
public class ConfigurationProfileVersion {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    private String configJson;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ConfigurationProfileVersion() {
    }

    public ConfigurationProfileVersion(UUID profileId, int versionNo, String configJson, UUID createdBy) {
        this.profileId = profileId;
        this.versionNo = versionNo;
        this.configJson = configJson;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public UUID getProfileId() { return profileId; }
    public int getVersionNo() { return versionNo; }
    public String getConfigJson() { return configJson; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
