package io.veridex.capacity;

import com.fasterxml.jackson.core.type.TypeReference;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.minio.MinioClient;
import org.opensearch.client.opensearch.OpenSearchClient;

/**
 * ReindexBenchmark——重灌计时基准。
 *
 * 流程：取该 KB 当前 active release 的索引 → 删除索引 → 按相同 mapping 重建 →
 * 从 MinIO 读全部 READY document_version 的 chunks.json（复用 SeedGenerator 的写入逻辑）
 * → 并行 embedding + bulk → refresh → 重新挂 alias → 输出 reindex_seconds 与对账。
 *
 * 用法（shade 未绑定 mainClass 时用 -cp 运行）：
 *   java -cp target/veridex-capacity-tools-0.1.0.jar io.veridex.capacity.ReindexBenchmark --kb &lt;kbId&gt;
 */
public final class ReindexBenchmark {

    private ReindexBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        UUID kbId = parseKb(args);

        String indexName;
        String aliasName;
        UUID releaseId;
        long expectedChunks;
        try (Connection db = SeedGenerator.dbConnection()) {
            try (PreparedStatement ps = db.prepareStatement("""
                    SELECT id, index_name, alias_name, chunk_count FROM index_release
                    WHERE knowledge_base_id = ? AND is_active = true
                    ORDER BY version_no DESC LIMIT 1""")) {
                ps.setObject(1, kbId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("no active index_release for kb " + kbId);
                    }
                    releaseId = rs.getObject("id", UUID.class);
                    indexName = rs.getString("index_name");
                    aliasName = rs.getString("alias_name");
                    expectedChunks = rs.getLong("chunk_count");
                }
            }
        }
        System.out.printf("[reindex] kb=%s index=%s alias=%s expected_chunks=%d%n",
                kbId, indexName, aliasName, expectedChunks);

        List<SeedGenerator.ChunkDoc> all = new ArrayList<>();
        try (Connection db = SeedGenerator.dbConnection()) {
            try (PreparedStatement ps = db.prepareStatement("""
                    SELECT dv.id, dv.object_key FROM document_version dv
                    JOIN document d ON d.id = dv.document_id
                    WHERE d.knowledge_base_id = ? AND dv.status = 'READY'
                    ORDER BY dv.id""")) {
                ps.setObject(1, kbId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        all.addAll(readChunks(UUID.fromString(rs.getString("id")), rs.getString("object_key")));
                    }
                }
            }
        }
        if (all.size() != expectedChunks) {
            throw new IllegalStateException("chunks from MinIO (" + all.size()
                    + ") != index_release.chunk_count (" + expectedChunks + ")");
        }

        MinioClient minio = SeedGenerator.minioClient();
        OpenSearchClient os = SeedGenerator.openSearchClient();

        long start = System.nanoTime();
        SeedGenerator.deleteIndex(os, indexName);
        SeedGenerator.createIndex(os, indexName);
        List<float[]> vectors = all.parallelStream()
                .map(c -> DeterministicEmbedding.embed(c.text()))
                .toList();
        SeedGenerator.bulkIndex(os, indexName, kbId, releaseId, all, vectors);
        // 删除索引会使索引脱离 alias，重建后需重新挂回（等价生产 reindex 语义）
        SeedGenerator.aliasTo(os, aliasName, indexName);
        double reindexSeconds = SeedGenerator.seconds(start);

        long count = SeedGenerator.countIndex(os, indexName);
        boolean ok = count == all.size();
        System.out.printf("[reindex] DONE chunks=%d os_count=%d reindex_seconds=%.3f %s%n",
                all.size(), count, reindexSeconds, ok ? "OK" : "MISMATCH");
        if (!ok) {
            throw new IllegalStateException("count mismatch: expected " + all.size() + " got " + count);
        }
    }

    private static List<SeedGenerator.ChunkDoc> readChunks(UUID documentVersionId, String objectKey)
            throws Exception {
        List<Map<String, Object>> raw;
        try (var in = SeedGenerator.getObject(SeedGenerator.minioClient(), objectKey + ".chunks.json")) {
            raw = SeedGenerator.jsonMapper().readValue(in.readAllBytes(), new TypeReference<>() {
            });
        }
        List<SeedGenerator.ChunkDoc> out = new ArrayList<>(raw.size());
        for (Map<String, Object> m : raw) {
            out.add(new SeedGenerator.ChunkDoc(documentVersionId,
                    ((Number) m.get("index")).intValue(),
                    String.valueOf(m.get("text")),
                    String.valueOf(m.get("title")),
                    String.valueOf(m.get("structurePath"))));
        }
        return out;
    }

    private static UUID parseKb(String[] args) {
        UUID kbId = null;
        for (int i = 0; i < args.length; i++) {
            if ("--kb".equals(args[i]) && i + 1 < args.length) {
                kbId = UUID.fromString(args[i + 1]);
                i++;
            } else {
                throw new IllegalArgumentException("usage: ReindexBenchmark --kb <kbId>");
            }
        }
        if (kbId == null) {
            throw new IllegalArgumentException("usage: ReindexBenchmark --kb <kbId>");
        }
        return kbId;
    }
}
