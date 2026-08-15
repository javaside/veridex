package io.veridex.feedback.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

public interface FeedbackRepository extends CrudRepository<Feedback, UUID> {
    List<Feedback> findAllByOrderByCreatedAtDesc();
    List<Feedback> findByRatingOrderByCreatedAtDesc(FeedbackRating rating);
}
