package io.veridex.shared.observability;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class BoundedModelTags {

    private static final String UNKNOWN = "unknown";

    private final Set<String> approvedModels;

    public BoundedModelTags(Set<String> approvedModels) {
        this.approvedModels = Set.copyOf(Objects.requireNonNull(approvedModels));
    }

    public ModelTags resolve(String model) {
        String resolved = approvedModels.contains(model) ? model : UNKNOWN;
        return new ModelTags(TelemetryTag.model(resolved));
    }

    public ModelTags resolve(String provider, String model) {
        String resolvedProvider = approvedModels.contains(provider) ? provider : UNKNOWN;
        String resolvedModel = approvedModels.contains(model) ? model : UNKNOWN;
        return new ModelTags(TelemetryTag.provider(resolvedProvider.toLowerCase(Locale.ROOT)),
                TelemetryTag.model(resolvedModel.toLowerCase(Locale.ROOT)));
    }

    public record ModelTags(TelemetryTag... tags) {
        public ModelTags(TelemetryTag model) {
            this(new TelemetryTag[] {model});
        }

        public String model() {
            for (TelemetryTag tag : tags) {
                if (tag.key().equals("model")) {
                    return tag.value();
                }
            }
            return UNKNOWN;
        }
    }
}
