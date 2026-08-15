package io.veridex.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.feedback.application.FeedbackService;
import io.veridex.feedback.domain.Feedback;
import io.veridex.feedback.domain.FeedbackRating;
import io.veridex.feedback.domain.FeedbackReasonCode;
import io.veridex.support.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class FeedbackIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    FeedbackService service;

    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void recordListAndMarkConverted() {
        Feedback recorded = service.record(USER, null, FeedbackRating.DOWN,
                FeedbackReasonCode.WRONG_ANSWER, "制度问题", "错误答案", List.of());
        assertThat(recorded.getRating()).isEqualTo(FeedbackRating.DOWN);

        Feedback converted = service.markConverted(recorded.getId(), UUID.randomUUID());
        assertThat(converted.getConvertedCaseId()).isNotNull();

        List<Feedback> down = service.listByRating(FeedbackRating.DOWN);
        assertThat(down).extracting(Feedback::getId).contains(recorded.getId());
    }
}
