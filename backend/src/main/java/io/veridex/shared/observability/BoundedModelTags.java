package io.veridex.shared.observability;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 模型指标标签控制器：provider 只允许配置的白名单；模型名做受控/归一化
 * （字母数字开头、仅含 [A-Za-z0-9:_.-]、长度 ≤64，否则 unknown），
 * 禁止把任意 URL、prompt 或异常文本放入指标标签（设计 §6）。
 */
public final class BoundedModelTags {

    private static final String UNKNOWN = "unknown";
    private static final Pattern MODEL_NAME =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9:_.-]{0,63}$");

    private final Set<String> approvedProviders;

    public BoundedModelTags(Set<String> approvedProviders) {
        this.approvedProviders = Set.copyOf(Objects.requireNonNull(approvedProviders));
    }

    public ModelTags resolve(String model) {
        String resolved = approvedProviders.contains(model) ? model : UNKNOWN;
        return new ModelTags(TelemetryTag.model(resolved));
    }

    public ModelTags resolve(String provider, String model) {
        String resolvedProvider = approvedProviders.contains(provider) ? provider : UNKNOWN;
        String normalizedModel = MODEL_NAME.matcher(model == null ? "" : model).matches() ? model : UNKNOWN;
        return new ModelTags(TelemetryTag.provider(resolvedProvider.toLowerCase(Locale.ROOT)),
                TelemetryTag.model(normalizedModel.toLowerCase(Locale.ROOT)));
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
