package io.veridex.iam.domain;

import io.veridex.iam.api.Role;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class PlatformUser implements java.io.Serializable {

    /**
     * 登录态经 Spring Session JDBC 以 Java 序列化落到 SPRING_SESSION_ATTRIBUTES，
     * principal（PlatformUserDetails）内嵌本实体，必须可序列化（spec §4.4）。
     */
    private static final long serialVersionUID = 1L;

    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Role role;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PlatformUser() {
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public Role getRole() { return role; }
    public boolean isEnabled() { return enabled; }
}
