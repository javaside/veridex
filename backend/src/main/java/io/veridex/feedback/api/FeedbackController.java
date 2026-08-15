package io.veridex.feedback.api;

import io.veridex.feedback.application.FeedbackService;
import io.veridex.feedback.domain.Feedback;
import io.veridex.feedback.domain.FeedbackEvidence;
import io.veridex.feedback.domain.FeedbackRating;
import io.veridex.iam.api.CurrentActor;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService service;
    private final FeedbackAuthorization authorization;
    private final JsonMapper jsonMapper;

    public FeedbackController(FeedbackService service, FeedbackAuthorization authorization, JsonMapper jsonMapper) {
        this.service = service;
        this.authorization = authorization;
        this.jsonMapper = jsonMapper;
    }

    @PostMapping
    public ResponseEntity<FeedbackView> record(@RequestBody RecordFeedbackRequest body) {
        Feedback feedback = service.record(CurrentActor.id(), body.queryRunId(), body.rating(),
                body.reasonCode(), body.question(), body.answer(), body.evidence());
        return ResponseEntity.status(HttpStatus.CREATED).body(toView(feedback));
    }

    @GetMapping
    public List<FeedbackView> list(@RequestParam(required = false) FeedbackRating rating) {
        requireAdmin();
        List<Feedback> feedbacks = rating == null ? service.listAll() : service.listByRating(rating);
        return feedbacks.stream().map(this::toView).toList();
    }

    @PostMapping("/{id}/converted")
    public ResponseEntity<FeedbackView> markConverted(@PathVariable UUID id, @RequestBody MarkConvertedRequest body) {
        requireAdmin();
        return ResponseEntity.ok(toView(service.markConverted(id, body.caseId())));
    }

    private void requireAdmin() {
        if (!authorization.isAdmin()) {
            throw new SecurityException("feedback management requires admin role");
        }
    }

    private FeedbackView toView(Feedback f) {
        return new FeedbackView(f.getId(), f.getQueryRunId(), f.getRating().name(),
                f.getReasonCode() == null ? null : f.getReasonCode().name(),
                f.getQuestion(), f.getAnswer(), deserializeEvidence(f.getEvidenceJson()),
                f.getConvertedCaseId(), f.getCreatedAt().toString());
    }

    private List<FeedbackEvidence> deserializeEvidence(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(json, new TypeReference<List<FeedbackEvidence>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize feedback evidence", e);
        }
    }
}
