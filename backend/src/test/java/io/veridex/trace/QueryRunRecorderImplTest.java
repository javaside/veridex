package io.veridex.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.veridex.shared.RefusalReason;
import io.veridex.trace.api.QueryRunRecorder;
import io.veridex.trace.api.QueryRunRecorder.CitationRecord;
import io.veridex.trace.api.QueryRunRecorder.GenerationRecord;
import io.veridex.trace.api.QueryRunRecorder.RetrievalHitRecord;
import io.veridex.trace.application.QueryRunRecorderImpl;
import io.veridex.trace.domain.CitationRepository;
import io.veridex.trace.domain.GenerationRunRepository;
import io.veridex.trace.domain.QueryRun;
import io.veridex.trace.domain.QueryRunRepository;
import io.veridex.trace.domain.RetrievalHitRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueryRunRecorderImplTest {

    @Mock QueryRunRepository runs;
    @Mock RetrievalHitRepository hits;
    @Mock GenerationRunRepository gens;
    @Mock CitationRepository citations;
    @InjectMocks QueryRunRecorderImpl recorder;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();

    @Test
    void startPersistsReceivedRun() {
        when(runs.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UUID id = recorder.start(USER, null, List.of(UUID.randomUUID()), "问题");
        assertThat(id).isNotNull();
        verify(runs).save(argThat(r -> r.getStatus() == QueryRun.Status.RECEIVED
                && r.getQuestion().equals("[REDACTED]")));
    }

    @Test
    void refuseMarksRunRefusedWithReason() {
        var run = new QueryRun(USER, null, "q", "q", List.of());
        when(runs.findById(RUN)).thenReturn(Optional.of(run));
        recorder.refuse(RUN, RefusalReason.NO_RELEVANT_EVIDENCE);
        assertThat(run.getStatus()).isEqualTo(QueryRun.Status.REFUSED);
        assertThat(run.getRefusalReason()).isEqualTo("NO_RELEVANT_EVIDENCE");
    }

    @Test
    void failMarksRunFailedWithError() {
        var run = new QueryRun(USER, null, "q", "q", List.of());
        when(runs.findById(RUN)).thenReturn(Optional.of(run));
        recorder.fail(RUN, "boom");
        assertThat(run.getStatus()).isEqualTo(QueryRun.Status.FAILED);
        assertThat(run.getError()).isEqualTo("TRACE_FAILURE");
    }

    @Test
    void completeAndCancelTerminateRun() {
        var completed = new QueryRun(USER, null, "q", "q", List.of());
        var cancelled = new QueryRun(USER, null, "q", "q", List.of());
        when(runs.findById(RUN)).thenReturn(Optional.of(completed), Optional.of(cancelled));
        recorder.complete(RUN);
        assertThat(completed.getStatus()).isEqualTo(QueryRun.Status.COMPLETED);
        recorder.cancel(RUN);
        assertThat(cancelled.getStatus()).isEqualTo(QueryRun.Status.CANCELLED);
    }

    @Test
    void markRetrievingPersistsHitsAndTransitionsStatus() {
        var run = new QueryRun(USER, null, "q", "q", List.of());
        when(runs.findById(RUN)).thenReturn(Optional.of(run));
        var record = new RetrievalHitRecord(UUID.randomUUID(), UUID.randomUUID(), 0, "BM25",
                2.0, null, 2.0, 1, true, null);
        recorder.markRetrieving(RUN, List.of(record));
        verify(hits).save(argThat(h -> h.getRank() == 1));
        assertThat(run.getStatus()).isEqualTo(QueryRun.Status.RETRIEVING);
    }

    @Test
    void markGeneratingTransitionsStatusOnly() {
        var run = new QueryRun(USER, null, "q", "q", List.of());
        when(runs.findById(RUN)).thenReturn(Optional.of(run));
        recorder.markGenerating(RUN);
        assertThat(run.getStatus()).isEqualTo(QueryRun.Status.GENERATING);
        verify(gens, never()).save(any());
    }

    @Test
    void recordGenerationCreatesThenUpdatesSingleRow() {
        var first = new GenerationRecord("deterministic", "deterministic", 10, 20, 5, 3, null, "abc");
        var second = new GenerationRecord("deterministic", "deterministic", 30, 40, 9, 7, null, "abc");
        var existing = new io.veridex.trace.domain.GenerationRun(RUN, "deterministic", "deterministic",
                0, 0, 0, 0, null, null);
        when(gens.findFirstByQueryRunId(RUN)).thenReturn(Optional.empty(), Optional.of(existing));
        recorder.recordGeneration(RUN, first);
        recorder.recordGeneration(RUN, second);
        verify(gens, times(1)).save(any());
        assertThat(existing.getInputTokens()).isEqualTo(30);
        assertThat(existing.getOutputTokens()).isEqualTo(40);
        assertThat(existing.getFirstTokenLatencyMs()).isEqualTo(7);
    }

    @Test
    void cancelDoesNotOverrideCompleted() {
        var completed = new QueryRun(USER, null, "q", "q", List.of());
        completed.complete();
        when(runs.findById(RUN)).thenReturn(Optional.of(completed));
        recorder.cancel(RUN);
        assertThat(completed.getStatus()).isEqualTo(QueryRun.Status.COMPLETED);
    }

    @Test
    void failDoesNotOverrideRefused() {
        var refused = new QueryRun(USER, null, "q", "q", List.of());
        refused.refuse(RefusalReason.NO_RELEVANT_EVIDENCE.name());
        when(runs.findById(RUN)).thenReturn(Optional.of(refused));
        recorder.fail(RUN, "MODEL_ERROR");
        assertThat(refused.getStatus()).isEqualTo(QueryRun.Status.REFUSED);
    }

    @Test
    void addCitationsPersistsEachCitation() {
        recorder.addCitations(RUN, List.of(
                new CitationRecord(1, UUID.randomUUID(), 0, "t", "[1]", "VALID")));
        verify(citations).save(argThat(c -> c.getCitationIndex() == 1));
    }
}
