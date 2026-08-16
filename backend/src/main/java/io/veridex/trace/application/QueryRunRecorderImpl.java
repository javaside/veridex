package io.veridex.trace.application;

import io.veridex.shared.RefusalReason;
import io.veridex.shared.observability.TelemetryErrorCode;
import io.veridex.trace.api.QueryRunRecorder;
import io.veridex.trace.domain.Citation;
import io.veridex.trace.domain.CitationRepository;
import io.veridex.trace.domain.GenerationRun;
import io.veridex.trace.domain.GenerationRunRepository;
import io.veridex.trace.domain.QueryRun;
import io.veridex.trace.domain.QueryRunRepository;
import io.veridex.trace.domain.RetrievalHit;
import io.veridex.trace.domain.RetrievalHitRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class QueryRunRecorderImpl implements QueryRunRecorder {

    private final QueryRunRepository runs;
    private final RetrievalHitRepository hits;
    private final GenerationRunRepository gens;
    private final CitationRepository citations;

    public QueryRunRecorderImpl(QueryRunRepository runs, RetrievalHitRepository hits,
                                GenerationRunRepository gens, CitationRepository citations) {
        this.runs = runs;
        this.hits = hits;
        this.gens = gens;
        this.citations = citations;
    }

    @Override
    public UUID start(UUID userId, UUID conversationId, List<UUID> knowledgeScope,
                      String normalizedQuestion) {
        QueryRun saved = runs.save(new QueryRun(userId, conversationId, "[REDACTED]", "[REDACTED]", knowledgeScope));
        return saved.getId();
    }

    @Override
    public void markRetrieving(UUID runId, List<RetrievalHitRecord> records) {
        for (var r : records) {
            hits.save(new RetrievalHit(runId, r.knowledgeBaseId(), r.documentVersionId(), r.chunkIndex(),
                    RetrievalHit.Channel.valueOf(r.channel()), r.bm25Score(), r.vectorScore(),
                    r.fusionScore(), r.rank(), r.enteredContext(), r.filterReason()));
        }
        runs.findById(runId).ifPresent(run -> run.mark(QueryRun.Status.RETRIEVING));
    }

    @Override
    public void markGenerating(UUID runId, GenerationRecord g) {
        gens.save(new GenerationRun(runId, g.model(), g.inputTokens(), g.outputTokens(),
                g.durationMs(), g.degradation(), g.contextHash()));
        runs.findById(runId).ifPresent(run -> run.mark(QueryRun.Status.GENERATING));
    }

    @Override
    public void addCitations(UUID runId, List<CitationRecord> cs) {
        for (var c : cs) {
            citations.save(new Citation(runId, c.citationIndex(), c.documentVersionId(), c.chunkIndex(),
                    c.sourceLocation(), c.citationText(), c.validationStatus()));
        }
    }

    @Override
    public void complete(UUID runId) {
        runs.findById(runId).ifPresent(QueryRun::complete);
    }

    @Override
    public void refuse(UUID runId, RefusalReason reason) {
        runs.findById(runId).ifPresent(run -> run.refuse(reason.name()));
    }

    @Override
    public void fail(UUID runId, String error) {
        runs.findById(runId).ifPresent(run -> run.fail(
                error != null && TelemetryErrorCode.persistedQueryRunCodes().contains(error)
                        ? error : "TRACE_FAILURE"));
    }

    @Override
    public void cancel(UUID runId) {
        runs.findById(runId).ifPresent(QueryRun::cancel);
    }
}
