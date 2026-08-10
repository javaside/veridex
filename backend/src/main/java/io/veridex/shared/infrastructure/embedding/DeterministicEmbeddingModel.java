package io.veridex.shared.infrastructure.embedding;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.stereotype.Component;

/**
 * 确定性 embedding（128 维、归一化）：开发与集成测试的本地默认，
 * 无需外部模型 API。生产环境按 veridex.embedding.provider 切换真实模型。
 */
@Component
public class DeterministicEmbeddingModel implements EmbeddingModel {

    private static final int DIMENSIONS = 128;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        var embeddings = request.getInstructions().stream()
                .map(text -> new Embedding(embed(text), 0))
                .toList();
        return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata());
    }

    @Override
    public float[] embed(String text) {
        return deterministic(text);
    }

    @Override
    public float[] embed(Document document) {
        return deterministic(document.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    private static float[] deterministic(String text) {
        float[] vector = new float[DIMENSIONS];
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            vector[i % DIMENSIONS] += ((bytes[i] & 0xff) - 128) / 128f;
        }
        return normalize(vector);
    }

    private static float[] normalize(float[] v) {
        double norm = 0;
        for (float x : v) {
            norm += (double) x * x;
        }
        if (norm == 0) {
            return v;
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] / norm);
        }
        return v;
    }
}
