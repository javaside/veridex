package io.veridex.configuration.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface ConfigurationProfileVersionRepository extends CrudRepository<ConfigurationProfileVersion, UUID> {
    List<ConfigurationProfileVersion> findByProfileIdOrderByVersionNoDesc(UUID profileId);
    Optional<ConfigurationProfileVersion> findTopByProfileIdOrderByVersionNoDesc(UUID profileId);
    Optional<ConfigurationProfileVersion> findByProfileIdAndVersionNo(UUID profileId, int versionNo);
}
