package io.veridex.iam.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface PlatformUserRepository extends CrudRepository<PlatformUser, UUID> {
    Optional<PlatformUser> findByUsername(String username);
}
