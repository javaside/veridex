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
@Table(name = "configuration_profile")
public class ConfigurationProfile {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft", nullable = false, columnDefinition = "jsonb")
    private String draftJson;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "active_version_no")
    private Integer activeVersionNo;

    protected ConfigurationProfile() {
    }

    public ConfigurationProfile(String name, String description, String draftJson, UUID createdBy) {
        this.name = name;
        this.description = description;
        this.draftJson = draftJson;
        this.createdBy = createdBy;
    }

    public void update(String name, String description, String draftJson) {
        this.name = name;
        this.description = description;
        this.draftJson = draftJson;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getDraftJson() { return draftJson; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Integer getActiveVersionNo() { return activeVersionNo; }

    /** 标记该 profile 的某个版本为「当前生效」；null 表示未生效。 */
    public void setActiveVersionNo(Integer versionNo) { this.activeVersionNo = versionNo; }
}
