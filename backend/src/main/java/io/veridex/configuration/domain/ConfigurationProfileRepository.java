package io.veridex.configuration.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface ConfigurationProfileRepository extends CrudRepository<ConfigurationProfile, UUID> {
    List<ConfigurationProfile> findAllByOrderByCreatedAtDesc();
}
