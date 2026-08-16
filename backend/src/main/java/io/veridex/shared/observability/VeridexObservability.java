package io.veridex.shared.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class VeridexObservability {

    private final MeterRegistry meters;
    private final ObservationRegistry observations;

    public VeridexObservability(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations;
    }

    public ObservationScope start(ObservationName name, TelemetryTag... tags) {
        try {
            return ObservationScope.started(name, meters, observations, tags);
        } catch (RuntimeException ignored) {
            return ObservationScope.noop();
        }
    }

    public void increment(MetricName name, TelemetryTag... tags) {
        try {
            Counter.builder(name.value()).tags(toTags(tags)).register(meters).increment();
        } catch (RuntimeException ignored) {
        }
    }

    public void record(MetricName name, double value, TelemetryTag... tags) {
        try {
            Timer.builder(name.value()).tags(toTags(tags)).register(meters).record((long) value, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
        }
    }

    static String[] toTags(TelemetryTag... tags) {
        List<String> values = new ArrayList<>(tags.length * 2);
        for (TelemetryTag tag : tags) {
            values.add(tag.key());
            values.add(tag.value());
        }
        return values.toArray(String[]::new);
    }

    public static final class ObservationScope implements AutoCloseable {

        private final ObservationName name;
        private final MeterRegistry meters;
        private final Observation observation;
        private final long startedNanos;
        private final List<TelemetryTag> tags;
        private boolean closed;

        private ObservationScope(ObservationName name, MeterRegistry meters, Observation observation,
                                 TelemetryTag... tags) {
            this.name = name;
            this.meters = meters;
            this.observation = observation;
            this.startedNanos = System.nanoTime();
            this.tags = new ArrayList<>(List.of(tags));
        }

        static ObservationScope started(ObservationName name, MeterRegistry meters, ObservationRegistry observations,
                                        TelemetryTag... tags) {
            Observation observation = Observation.createNotStarted(name.value(), observations);
            for (TelemetryTag tag : tags) {
                observation.lowCardinalityKeyValue(tag.key(), tag.value());
            }
            observation.start();
            return new ObservationScope(name, meters, observation, tags);
        }

        static ObservationScope noop() {
            return new ObservationScope(null, null, Observation.NOOP);
        }

        public void success(TelemetryTag... outcomeTags) {
            finish(withNoError(outcomeTags));
        }

        private TelemetryTag[] withNoError(TelemetryTag... outcomeTags) {
            TelemetryTag[] tagsWithError = new TelemetryTag[outcomeTags.length + 2];
            tagsWithError[0] = TelemetryTag.noErrorOutcome();
            tagsWithError[1] = TelemetryTag.noError();
            System.arraycopy(outcomeTags, 0, tagsWithError, 2, outcomeTags.length);
            return tagsWithError;
        }

        public void failure(TelemetryErrorCode errorCode) {
            finish(TelemetryTag.error(), TelemetryTag.errorCode(errorCode), failureOutcome());
        }

        private TelemetryTag failureOutcome() {
            return switch (name) {
                case QA_RUN -> TelemetryTag.qaOutcome(TelemetryOutcome.Qa.FAILED);
                case RETRIEVAL_RUN -> TelemetryTag.retrievalOutcome(TelemetryOutcome.Retrieval.FAILED);
                case GENERATION_MODEL -> TelemetryTag.generationOutcome(TelemetryOutcome.Generation.ERROR);
                case OUTBOX_PUBLISH -> TelemetryTag.outboxOutcome(TelemetryOutcome.Outbox.ERROR);
                case INGESTION_RUN -> TelemetryTag.ingestionOutcome(TelemetryOutcome.Ingestion.FAILED);
                case INDEXING_PUBLISH -> TelemetryTag.indexingOutcome(TelemetryOutcome.Indexing.ERROR);
            };
        }

        private void finish(TelemetryTag... outcomeTags) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                tags.addAll(List.of(outcomeTags));
                for (TelemetryTag tag : outcomeTags) {
                    observation.lowCardinalityKeyValue(tag.key(), tag.value());
                }
                Timer.builder(name.value()).tags(toTags(tags.toArray(TelemetryTag[]::new))).register(meters)
                        .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
            } catch (RuntimeException ignored) {
            } finally {
                observation.stop();
            }
        }

        @Override
        public void close() {
            if (!closed && name != null) {
                finish(withNoError());
            }
        }
    }
}
