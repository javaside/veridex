package io.veridex.conversation.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface ConversationRepository extends CrudRepository<Conversation, UUID> {

    List<Conversation> findByUserIdOrderByUpdatedAtDesc(UUID userId);

    Optional<Conversation> findByIdAndUserId(UUID id, UUID userId);
}
