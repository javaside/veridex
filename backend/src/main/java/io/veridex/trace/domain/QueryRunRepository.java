package io.veridex.trace.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface QueryRunRepository extends CrudRepository<QueryRun, UUID> {

    List<QueryRun> findByUserIdAndConversationIdOrderByCreatedAtDesc(UUID userId, UUID conversationId);
}
