package io.veridex.feedback.application;

import io.veridex.feedback.domain.Feedback;
import io.veridex.feedback.domain.FeedbackEvidence;
import io.veridex.feedback.domain.FeedbackRating;
import io.veridex.feedback.domain.FeedbackReasonCode;
import io.veridex.feedback.domain.FeedbackRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional
public class FeedbackService {

    private final FeedbackRepository repository;
    private final JsonMapper jsonMapper;

    public FeedbackService(FeedbackRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    public Feedback record(UUID userId, UUID queryRunId, FeedbackRating rating,
                           FeedbackReasonCode reasonCode, String question, String answer,
                           List<FeedbackEvidence> evidence) {
        if (rating == null) {
            throw new IllegalArgumentException("rating is required");
        }
        String normalizedQuestion = question == null ? "" : question.trim();
        if (normalizedQuestion.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        String normalizedAnswer = answer == null ? "" : answer;
        List<FeedbackEvidence> normalizedEvidence = evidence == null ? List.of() : evidence;
        if (rating == FeedbackRating.DOWN) {
            if (reasonCode == null) {
                throw new IllegalArgumentException("reasonCode is required for DOWN feedback");
            }
        } else {
            reasonCode = null;
            normalizedEvidence = List.of();
        }
        return repository.save(new Feedback(userId, queryRunId, rating, reasonCode,
                normalizedQuestion, normalizedAnswer, serializeEvidence(normalizedEvidence)));
    }

    public List<Feedback> listAll() {
        return repository.findAllByOrderByCreatedAtDesc();
    }

    public List<Feedback> listByRating(FeedbackRating rating) {
        return repository.findByRatingOrderByCreatedAtDesc(rating);
    }

    public Feedback require(UUID feedbackId) {
        return repository.findById(feedbackId)
                .orElseThrow(() -> new IllegalArgumentException("unknown feedback " + feedbackId));
    }

    public Feedback markConverted(UUID feedbackId, UUID caseId) {
        Feedback existing = require(feedbackId);
        if (caseId == null) {
            throw new IllegalArgumentException("caseId is required");
        }
        existing.markConverted(caseId);
        return repository.save(existing);
    }

    private String serializeEvidence(List<FeedbackEvidence> evidence) {
        try {
            return jsonMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize feedback evidence", e);
        }
    }
}
