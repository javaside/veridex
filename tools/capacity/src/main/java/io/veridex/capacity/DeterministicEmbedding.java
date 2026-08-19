package io.veridex.capacity;

import java.nio.charset.StandardCharsets;

/**
 * DeterministicEmbedding——backend DeterministicEmbeddingModel 的无依赖纯 Java 复刻。
 *
 * 必须与 backend 的
 * {@code backend/src/main/java/io/veridex/shared/infrastructure/embedding/DeterministicEmbeddingModel.java}
 * 逐位一致：128 维、UTF-8 字节哈希分桶、L2 归一化。任何漂移都会导致
 * OpenSearch kNN 检索 miss（容量数据的 embedding 由本工具写入，查询 embedding 由
 * backend 在服务端计算），自校验（1K 场景）的 HTTP 检索命中就是兜底验证。
 *
 * 注意保持算法逐行一致：{@code ((bytes[i] & 0xff) - 128) / 128f} 是 int 运算后
 * float 除法（不是 double），normalize 用 double 累加后再转 float。
 */
public final class DeterministicEmbedding {

    public static final int DIMENSIONS = 128;

    private DeterministicEmbedding() {
    }

    public static float[] embed(String text) {
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
