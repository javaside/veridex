package io.veridex.shared.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

public interface OutboxEventRepository extends CrudRepository<OutboxEventEntity, UUID> {

    @Query("select e from OutboxEventEntity e where e.publishedAt is null order by e.occurredAt asc")
    List<OutboxEventEntity> findUnpublished();

    long countByPublishedAtIsNull();

    @Query("select min(e.occurredAt) from OutboxEventEntity e where e.publishedAt is null")
    Optional<Instant> findOldestUnpublishedAt();
}
