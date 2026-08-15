package io.veridex.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.veridex.feedback.application.FeedbackService;
import io.veridex.feedback.domain.Feedback;
import io.veridex.feedback.domain.FeedbackEvidence;
import io.veridex.feedback.domain.FeedbackRating;
import io.veridex.feedback.domain.FeedbackReasonCode;
import io.veridex.feedback.domain.FeedbackRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class FeedbackServiceTest {

    private FeedbackRepository repository;
    private FeedbackService service;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID FEEDBACK = UUID.randomUUID();
    private static final FeedbackEvidence EVIDENCE = new FeedbackEvidence(UUID.randomUUID(), List.of(0, 1));

    @BeforeEach
    void setUp() {
        repository = mock(FeedbackRepository.class);
        service = new FeedbackService(repository, new JsonMapper());
    }

    @Test
    void downRequiresReasonCode() {
        assertThatThrownBy(() -> service.record(USER, null, FeedbackRating.DOWN, null, "问题", "答案", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasonCode");
    }

    @Test
    void upNormalizesReasonAndEvidence() {
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Feedback saved = service.record(USER, null, FeedbackRating.UP, null, "问题", "答案", List.of(EVIDENCE));
        assertThat(saved.getReasonCode()).isNull();
        assertThat(saved.getEvidenceJson()).isEqualTo("[]");
    }

    @Test
    void downRequiresQuestion() {
        assertThatThrownBy(() -> service.record(USER, null, FeedbackRating.DOWN, FeedbackReasonCode.WRONG_ANSWER, "  ", "答案", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("question");
    }

    @Test
    void markConvertedSetsCaseId() {
        Feedback feedback = new Feedback(USER, null, FeedbackRating.DOWN, FeedbackReasonCode.WRONG_ANSWER,
                "问题", "答案", "[]");
        when(repository.findById(FEEDBACK)).thenReturn(Optional.of(feedback));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Feedback converted = service.markConverted(FEEDBACK, UUID.randomUUID());
        assertThat(converted.getConvertedCaseId()).isNotNull();
    }
}
