package io.veridex.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
    void markGeneratingPersistsGenerationRun() {
        var run = new QueryRun(USER, null, "q", "q", List.of());
        when(runs.findById(RUN)).thenReturn(Optional.of(run));
        recorder.markGenerating(RUN, new GenerationRecord("deterministic", 10, 20, 5, null, "abc"));
        verify(gens).save(argThat(g -> g.getModel().equals("deterministic")));
        assertThat(run.getStatus()).isEqualTo(QueryRun.Status.GENERATING);
    }

    @Test
    void addCitationsPersistsEachCitation() {
        recorder.addCitations(RUN, List.of(
                new CitationRecord(1, UUID.randomUUID(), 0, "t", "[1]", "VALID")));
        verify(citations).save(argThat(c -> c.getCitationIndex() == 1));
    }
}
