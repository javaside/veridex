package io.veridex.iam.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByTokenHash(String tokenHash);

    List<ApiKey> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
