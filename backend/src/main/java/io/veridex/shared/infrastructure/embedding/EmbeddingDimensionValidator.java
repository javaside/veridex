package io.veridex.shared.infrastructure.embedding;

import io.veridex.shared.infrastructure.config.EmbeddingProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class EmbeddingDimensionValidator implements ApplicationRunner {

    private final EmbeddingModel embeddings;
    private final EmbeddingProperties properties;

    public EmbeddingDimensionValidator(EmbeddingModel embeddings, EmbeddingProperties properties) {
        this.embeddings = embeddings;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        float[] probe = embeddings.embed("veridex:dimension-probe");
        if (probe == null) {
            throw new IllegalStateException("Embedding model returned null for the dimension probe");
        }
        if (probe.length != properties.dimensions()) {
            throw new IllegalStateException(
                    "Embedding model returned " + probe.length + " dimensions but veridex.embedding.dimensions="
                            + properties.dimensions() + ". Fix the dimension config (and rebuild indices) before starting.");
        }
    }
}
