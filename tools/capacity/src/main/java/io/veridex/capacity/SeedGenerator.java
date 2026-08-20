package io.veridex.capacity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.opensearch.client.RestClient;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.apache.hc.core5.http.HttpHost;

/**
 * SeedGenerator——容量合成数据生成器。
 *
 * 直连基础设施（PG / MinIO / OpenSearch，默认 localhost compose 栈），写入与 backend
 * 完全一致的元数据与索引结构：
 * <ul>
 *   <li>PG：knowledge_base / knowledge_base_grant / document / document_version / index_release /
 *       index_release_document（列名对齐 V3/V4/V5 迁移）</li>
 *   <li>MinIO：&lt;kbId&gt;/&lt;docId&gt;/v1/doc-{i}.md（原文）、.parsed.json（纯文本 JSON 串）、
 *       .chunks.json（[{index,text,title,structurePath}]）</li>
 *   <li>OpenSearch：veridex-&lt;kbId&gt;-&lt;versionNo&gt;（mapping 同 OpenSearchIndexGateway：
 *       shards=1 / replicas=0 / knn=true，text / embedding(128) / document_version_id /
 *       knowledge_base_id / release_id / chunk_index / structure_path / title），
 *       bulk 后 alias 切到 veridex-&lt;kbId&gt;-active，并写 index_release（PUBLISHED / is_active=true）</li>
 * </ul>
 *
 * 语料：8 类中文制度模板（请假/报销/差旅/保密/考勤/采购/安全/培训）参数化展开，
 * 每 chunk 200-500 字；embedding 用 {@link DeterministicEmbedding} 批量并行。
 *
 * 用法：java -jar target/veridex-capacity-tools-0.1.0.jar --kbs 20 --docs-per-kb 50 --chunks-per-doc 1000
 * （默认即 1M chunk；自校验用 --kbs 2 --docs-per-kb 5 --chunks-per-doc 100 = 1000 chunk）。
 */
public final class SeedGenerator {

    public static final UUID ADMIN_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    public static final String INDEX_PREFIX = "veridex";
    public static final int EMBEDDING_DIMENSIONS = DeterministicEmbedding.DIMENSIONS;
    public static final int BULK_BATCH = 1000;

    // 环境变量默认值 = deploy/compose/compose.yml 与 backend application.yml 的本地默认
    private static final String ENV_DB_URL = env("VERIDEX_DB_URL", "jdbc:postgresql://localhost:5432/veridex");
    private static final String ENV_DB_USER = env("VERIDEX_DB_USERNAME", "veridex");
    private static final String ENV_DB_PASS = env("VERIDEX_DB_PASSWORD", "veridex-local");
    private static final String ENV_MINIO_ENDPOINT = env("VERIDEX_MINIO_ENDPOINT", "http://localhost:9000");
    private static final String ENV_MINIO_ACCESS = env("VERIDEX_MINIO_ACCESS_KEY", "veridex");
    private static final String ENV_MINIO_SECRET = env("VERIDEX_MINIO_SECRET_KEY", "veridex-local-secret");
    private static final String ENV_MINIO_BUCKET = env("VERIDEX_MINIO_BUCKET", "veridex-documents");
    private static final String ENV_OS_URIS = env("VERIDEX_OPENSEARCH_URIS", "http://localhost:9200");

    /** 一次待写索引的 chunk（与 OpenSearchIndexGateway.indexChunks 的输入一致）。 */
    public record ChunkDoc(UUID documentVersionId, int index, String text, String title, String structurePath) {
    }

    private SeedGenerator() {
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);
        System.out.printf("[seed] kbs=%d docsPerKb=%d chunksPerDoc=%d (target chunks=%d)%n",
                a.kbs, a.docsPerKb, a.chunksPerDoc, (long) a.kbs * a.docsPerKb * a.chunksPerDoc);
        System.out.printf("[seed] db=%s minio=%s opensearch=%s%n", ENV_DB_URL, ENV_MINIO_ENDPOINT, ENV_OS_URIS);

        long totalStart = System.nanoTime();
        long t0 = System.nanoTime();
        MinioClient minio = minioClient();
        ensureBucket(minio);
        OpenSearchClient os = openSearchClient();
        System.out.printf("[seed] clients ready in %.2fs%n", seconds(t0));

        long totalChunks = 0;
        long pgDocs = 0;
        long pgVersions = 0;
        long pgReleases = 0;
        try (Connection db = dbConnection()) {
            for (int kb = 0; kb < a.kbs; kb++) {
                long kbStart = System.nanoTime();
                UUID kbId = UUID.randomUUID();
                String name = "容量库-" + kb;
                String slug = "capacity-" + kb + "-" + kbId.toString().substring(0, 8);
                String indexName = null;
                List<String> minioKeys = new ArrayList<>();
                try {

                try (PreparedStatement ps = db.prepareStatement("""
                        INSERT INTO knowledge_base (id, name, slug, description, owner_id, status, created_at)
                        VALUES (?, ?, ?, ?, ?, 'ACTIVE', now())""")) {
                    ps.setObject(1, kbId);
                    ps.setString(2, name);
                    ps.setString(3, slug);
                    ps.setString(4, "容量测试合成知识库（SeedGenerator 生成）");
                    ps.setObject(5, ADMIN_USER);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = db.prepareStatement("""
                        INSERT INTO knowledge_base_grant (knowledge_base_id, user_id, level, granted_at)
                        VALUES (?, ?, 'MANAGE', now())""")) {
                    ps.setObject(1, kbId);
                    ps.setObject(2, ADMIN_USER);
                    ps.executeUpdate();
                }

                int versionNo = nextVersionNo(db, kbId);
                indexName = INDEX_PREFIX + "-" + kbId + "-" + versionNo;
                String aliasName = INDEX_PREFIX + "-" + kbId + "-active";
                createIndex(os, indexName);
                UUID releaseId = UUID.randomUUID();

                long docChunks = 0;
                for (int d = 0; d < a.docsPerKb; d++) {
                    UUID docId = UUID.randomUUID();
                    UUID versionId = UUID.randomUUID();
                    String filename = "doc-" + d + ".md";
                    String objectKey = kbId + "/" + docId + "/v1/" + filename;

                    List<String> chunkTexts = new ArrayList<>();
                    List<String> titles = new ArrayList<>();
                    List<String> paths = new ArrayList<>();
                    for (int c = 0; c < a.chunksPerDoc; c++) {
                        PolicyCorpus.Chunk chunk = PolicyCorpus.chunk((kb + d) % 8, d, c);
                        chunkTexts.add(chunk.text());
                        titles.add(chunk.title());
                        paths.add(chunk.structurePath());
                    }
                    String fullText = String.join("\n", chunkTexts);
                    String markdown = toMarkdown(name, filename, chunkTexts);
                    byte[] mdBytes = markdown.getBytes(StandardCharsets.UTF_8);
                    String sha256 = sha256Hex(mdBytes);
                    String parsedJson = jsonMapper().writeValueAsString(fullText);
                    List<Map<String, Object>> chunkManifest = new ArrayList<>();
                    for (int c = 0; c < a.chunksPerDoc; c++) {
                        chunkManifest.add(Map.of(
                                "index", c, "text", chunkTexts.get(c),
                                "title", titles.get(c), "structurePath", paths.get(c)));
                    }
                    byte[] chunksJson = jsonMapper().writeValueAsBytes(chunkManifest);

                    insertDocument(db, kbId, docId, filename, mdBytes.length);
                    insertDocumentVersion(db, docId, versionId, objectKey, sha256, a.chunksPerDoc);

                    putObject(minio, objectKey, mdBytes, "text/markdown");
                    putObject(minio, objectKey + ".parsed.json", parsedJson.getBytes(StandardCharsets.UTF_8), "application/json");
                    putObject(minio, objectKey + ".chunks.json", chunksJson, "application/json");
                    minioKeys.add(objectKey);
                    minioKeys.add(objectKey + ".parsed.json");
                    minioKeys.add(objectKey + ".chunks.json");

                    indexChunks(os, indexName, kbId, releaseId, versionId, chunkTexts, titles, paths);
                    docChunks += a.chunksPerDoc;
                    pgDocs++;
                    pgVersions++;
                    if ((d + 1) % 10 == 0 || d == a.docsPerKb - 1) {
                        System.out.printf("[seed] kb=%s doc %d/%d indexed (chunks so far=%d)%n",
                                kbId.toString().substring(0, 8), d + 1, a.docsPerKb, docChunks);
                    }
                }

                insertIndexRelease(db, kbId, releaseId, versionNo, indexName, aliasName, a.docsPerKb, docChunks);
                try (PreparedStatement ps = db.prepareStatement("""
                        INSERT INTO index_release_document (release_id, document_version_id)
                        SELECT ?, id FROM document_version
                        WHERE document_id IN (SELECT id FROM document WHERE knowledge_base_id = ?)""")) {
                    ps.setObject(1, releaseId);
                    ps.setObject(2, kbId);
                    ps.executeUpdate();
                }
                aliasTo(os, aliasName, indexName);
                long count = countIndex(os, indexName);
                pgReleases++;
                totalChunks += docChunks;
                boolean ok = count == docChunks;
                System.out.printf("[seed] kb=%s name=%s index=%s docs=%d chunks=%d os_count=%d %s (%.2fs)%n",
                        kbId, name, indexName, a.docsPerKb, docChunks, count,
                        ok ? "OK" : "MISMATCH", seconds(kbStart));
                if (!ok) {
                    throw new IllegalStateException("OpenSearch count mismatch for " + indexName
                            + ": expected " + docChunks + " got " + count);
                }
                } catch (Exception e) {
                    // 失败清理：删除刚建的 OpenSearch 索引（连同其上 alias）、回滚该 KB 的 PG 行、删除已写 MinIO 对象
                    cleanupFailedKb(db, minio, os, kbId, indexName, minioKeys);
                    System.err.printf("[seed] FAILED kb=%s name=%s index=%s: %s%n", kbId, name, indexName, e);
                    System.err.printf("[seed] CLEANED-UP kb=%s: OpenSearch 索引/PG 行/MinIO 对象已清理，可安全重跑（详见 README「失败清理与重跑」）%n", kbId);
                    throw e;
                }
            }
        }

        System.out.printf("[seed] DONE total_chunks=%d pg_docs=%d pg_versions=%d pg_releases=%d total_seconds=%.2f%n",
                totalChunks, pgDocs, pgVersions, pgReleases, seconds(totalStart));
        System.out.printf("[seed] verify: OpenSearch _count == %d across %d KBs%n", totalChunks, a.kbs);
    }

    // ---------------------------------------------------------- PG helpers

    public static Connection dbConnection() throws Exception {
        Connection c = DriverManager.getConnection(ENV_DB_URL, ENV_DB_USER, ENV_DB_PASS);
        c.setAutoCommit(true);
        return c;
    }

    private static int nextVersionNo(Connection db, UUID kbId) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT COALESCE(MAX(version_no), 0) FROM index_release WHERE knowledge_base_id = ?")) {
            ps.setObject(1, kbId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) + 1;
            }
        }
    }

    private static void insertDocument(Connection db, UUID kbId, UUID docId, String filename, int size) throws Exception {
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO document (id, knowledge_base_id, filename, content_type, size_bytes, created_by, created_at)
                VALUES (?, ?, ?, 'text/markdown', ?, ?, now())""")) {
            ps.setObject(1, docId);
            ps.setObject(2, kbId);
            ps.setString(3, filename);
            ps.setInt(4, size);
            ps.setObject(5, ADMIN_USER);
            ps.executeUpdate();
        }
    }

    private static void insertDocumentVersion(Connection db, UUID docId, UUID versionId,
                                              String objectKey, String sha256, int chunkCount) throws Exception {
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO document_version (id, document_id, version_no, object_key, file_sha256,
                                              status, parsed_object_key, chunk_count, created_at, processed_at)
                VALUES (?, ?, 1, ?, ?, 'READY', ?, ?, now(), now())""")) {
            ps.setObject(1, versionId);
            ps.setObject(2, docId);
            ps.setString(3, objectKey);
            ps.setString(4, sha256);
            ps.setString(5, objectKey + ".parsed.json");
            ps.setInt(6, chunkCount);
            ps.executeUpdate();
        }
    }

    private static void insertIndexRelease(Connection db, UUID kbId, UUID releaseId, int versionNo,
                                           String indexName, String aliasName, int documentCount,
                                           long chunkCount) throws Exception {
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO index_release (id, knowledge_base_id, version_no, index_name, alias_name,
                                           status, document_count, chunk_count, is_active, created_at, published_at)
                VALUES (?, ?, ?, ?, ?, 'PUBLISHED', ?, ?, true, now(), now())""")) {
            ps.setObject(1, releaseId);
            ps.setObject(2, kbId);
            ps.setInt(3, versionNo);
            ps.setString(4, indexName);
            ps.setString(5, aliasName);
            ps.setInt(6, documentCount);
            ps.setLong(7, chunkCount);
            ps.executeUpdate();
        }
    }

    // ----------------------------------------------------- failure cleanup

    /**
     * 失败路径清理（best-effort，任何一步失败都不掩盖原始异常）：
     * 删除刚建的 OpenSearch 索引（删除索引即连同其上 alias 一并移除）、
     * 回滚该 KB 的 PG 行、删除已写 MinIO 对象。
     */
    private static void cleanupFailedKb(Connection db, MinioClient minio, OpenSearchClient os,
                                        UUID kbId, String indexName, List<String> minioKeys) {
        if (indexName != null) {
            try {
                deleteIndex(os, indexName);
                System.err.println("[seed] cleanup: deleted OpenSearch index " + indexName);
            } catch (Exception ex) {
                System.err.println("[seed] cleanup: WARN failed to delete OpenSearch index " + indexName + ": " + ex);
            }
        }
        rollbackKbRows(db, kbId); // 内部逐条 best-effort，不抛出
        System.err.println("[seed] cleanup: rolled back PG rows for kb " + kbId);
        for (String key : minioKeys) {
            try {
                minio.removeObject(RemoveObjectArgs.builder().bucket(ENV_MINIO_BUCKET).object(key).build());
            } catch (Exception ex) {
                System.err.println("[seed] cleanup: WARN failed to delete MinIO object " + key + ": " + ex);
            }
        }
        if (!minioKeys.isEmpty()) {
            System.err.println("[seed] cleanup: deleted " + minioKeys.size() + " MinIO object(s)");
        }
    }

    /** 逆序删除该 KB 写入的全部 PG 行（index_release_document → index_release → document_version → document → grant → KB）。
     *  每条 DELETE 独立 try/catch：单条失败（如表被并发改动）不阻塞其余回滚，保证 best-effort 语义。 */
    private static void rollbackKbRows(Connection db, UUID kbId) {
        rollbackDelete(db, """
                DELETE FROM index_release_document
                WHERE release_id IN (SELECT id FROM index_release WHERE knowledge_base_id = ?)""", kbId);
        rollbackDelete(db, "DELETE FROM index_release WHERE knowledge_base_id = ?", kbId);
        rollbackDelete(db, """
                DELETE FROM document_version
                WHERE document_id IN (SELECT id FROM document WHERE knowledge_base_id = ?)""", kbId);
        rollbackDelete(db, "DELETE FROM document WHERE knowledge_base_id = ?", kbId);
        rollbackDelete(db, "DELETE FROM knowledge_base_grant WHERE knowledge_base_id = ?", kbId);
        rollbackDelete(db, "DELETE FROM knowledge_base WHERE id = ?", kbId);
    }

    /** 执行单条回滚 DELETE；失败仅打印 WARN，不抛出（保证其余回滚语句继续执行）。 */
    private static void rollbackDelete(Connection db, String sql, UUID kbId) {
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setObject(1, kbId);
            int n = ps.executeUpdate();
            if (n > 0) {
                System.err.println("[seed] cleanup: deleted " + n + " row(s) via: " + sql.split("\\n")[0].trim() + " …");
            }
        } catch (Exception ex) {
            System.err.println("[seed] cleanup: WARN rollback statement failed (" + sql.split("\\n")[0].trim() + "): " + ex);
        }
    }

    // ----------------------------------------------------- MinIO helpers

    public static MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(ENV_MINIO_ENDPOINT)
                .credentials(ENV_MINIO_ACCESS, ENV_MINIO_SECRET)
                .build();
    }

    private static void ensureBucket(MinioClient minio) throws Exception {
        boolean exists = minio.bucketExists(BucketExistsArgs.builder().bucket(ENV_MINIO_BUCKET).build());
        if (!exists) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(ENV_MINIO_BUCKET).build());
        }
    }

    public static void putObject(MinioClient minio, String objectKey, byte[] data, String contentType) throws Exception {
        minio.putObject(PutObjectArgs.builder()
                .bucket(ENV_MINIO_BUCKET).object(objectKey)
                .stream(new ByteArrayInputStream(data), data.length, -1)
                .contentType(contentType).build());
    }

    public static InputStream getObject(MinioClient minio, String objectKey) throws Exception {
        return minio.getObject(GetObjectArgs.builder().bucket(ENV_MINIO_BUCKET).object(objectKey).build());
    }

    // --------------------------------------------------- OpenSearch helpers

    public static OpenSearchClient openSearchClient() throws Exception {
        var hosts = ENV_OS_URIS.split(",");
        var httpHosts = new HttpHost[hosts.length];
        for (int i = 0; i < hosts.length; i++) {
            httpHosts[i] = HttpHost.create(hosts[i].trim());
        }
        var restClient = RestClient.builder(httpHosts).build();
        return new OpenSearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
    }

    /** mapping 与 OpenSearchIndexGateway.createIndex 逐字段一致。 */
    public static void createIndex(OpenSearchClient os, String indexName) throws Exception {
        if (indexExists(os, indexName)) {
            return;
        }
        IndexSettings settings = new IndexSettings.Builder()
                .numberOfShards(1).numberOfReplicas(0)
                .knn(true)
                .build();
        TypeMapping mapping = new TypeMapping.Builder()
                .properties(Map.of(
                        "text", Property.of(p -> p.text(t -> t)),
                        "embedding", Property.of(p -> p.knnVector(k -> k.dimension(EMBEDDING_DIMENSIONS))),
                        "document_version_id", Property.of(p -> p.keyword(k -> k)),
                        "knowledge_base_id", Property.of(p -> p.keyword(k -> k)),
                        "release_id", Property.of(p -> p.keyword(k -> k)),
                        "chunk_index", Property.of(p -> p.integer(i -> i)),
                        "structure_path", Property.of(p -> p.keyword(k -> k)),
                        "title", Property.of(p -> p.keyword(k -> k))))
                .build();
        os.indices().create(c -> c.index(indexName).settings(settings).mappings(mapping));
    }

    public static void deleteIndex(OpenSearchClient os, String indexName) throws Exception {
        if (indexExists(os, indexName)) {
            os.indices().delete(d -> d.index(indexName));
        }
    }

    public static boolean indexExists(OpenSearchClient os, String indexName) throws Exception {
        return os.indices().exists(e -> e.index(indexName)).value();
    }

    public static void aliasTo(OpenSearchClient os, String aliasName, String indexName) throws Exception {
        var request = new org.opensearch.client.opensearch.indices.UpdateAliasesRequest.Builder();
        if (indexExists(os, aliasName)) {
            request.actions(a -> a.remove(r -> r.index("*").alias(aliasName)));
        }
        request.actions(a -> a.add(add -> add.index(indexName).alias(aliasName)));
        os.indices().updateAliases(request.build());
    }

    public static long countIndex(OpenSearchClient os, String indexName) throws Exception {
        return os.count(c -> c.index(indexName)).count();
    }

    /**
     * bulk 写 chunk（批次 BULK_BATCH=1000），_id 固定 documentVersionId:chunkIndex（幂等覆盖写），
     * 字段结构同 OpenSearchIndexGateway.indexChunks；embedding 并行计算。
     */
    public static void indexChunks(OpenSearchClient os, String indexName, UUID knowledgeBaseId, UUID releaseId,
                                   UUID documentVersionId, List<String> texts, List<String> titles,
                                   List<String> paths) throws Exception {
        int n = texts.size();
        List<float[]> vectors = texts.parallelStream()
                .map(DeterministicEmbedding::embed)
                .toList();
        List<ChunkDoc> docs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            docs.add(new ChunkDoc(documentVersionId, i, texts.get(i), titles.get(i), paths.get(i)));
        }
        bulkIndex(os, indexName, knowledgeBaseId, releaseId, docs, vectors);
    }

    public static void bulkIndex(OpenSearchClient os, String indexName, UUID knowledgeBaseId, UUID releaseId,
                                 List<ChunkDoc> docs, List<float[]> vectors) throws Exception {
        for (int start = 0; start < docs.size(); start += BULK_BATCH) {
            var request = new org.opensearch.client.opensearch.core.BulkRequest.Builder();
            int end = Math.min(start + BULK_BATCH, docs.size());
            for (int i = start; i < end; i++) {
                ChunkDoc chunk = docs.get(i);
                float[] vector = vectors.get(i);
                String docId = chunk.documentVersionId() + ":" + chunk.index();
                request.operations(op -> op.index(idx -> idx
                        .index(indexName).id(docId)
                        .document(Map.of(
                                "text", chunk.text(),
                                "embedding", vector,
                                "document_version_id", chunk.documentVersionId().toString(),
                                "knowledge_base_id", knowledgeBaseId.toString(),
                                "release_id", releaseId.toString(),
                                "chunk_index", chunk.index(),
                                "structure_path", chunk.structurePath(),
                                "title", chunk.title()))));
            }
            var response = os.bulk(request.build());
            if (response.errors()) {
                throw new IllegalStateException("bulk index reported errors for " + indexName);
            }
        }
        os.indices().refresh(r -> r.index(indexName));
    }

    // -------------------------------------------------------- misc helpers

    public static ObjectMapper jsonMapper() {
        return new ObjectMapper();
    }

    private static String toMarkdown(String kbName, String filename, List<String> chunkTexts) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(kbName).append(" 制度文档 ").append(filename).append("\n\n");
        for (int i = 0; i < chunkTexts.size(); i++) {
            sb.append("## 第 ").append(i + 1).append(" 节\n\n").append(chunkTexts.get(i)).append("\n\n");
        }
        return sb.toString();
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(data));
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }

    static double seconds(long nanosStart) {
        return (System.nanoTime() - nanosStart) / 1_000_000_000d;
    }

    // ------------------------------------------------------------ arg model

    record Args(int kbs, int docsPerKb, int chunksPerDoc) {

        static Args parse(String[] args) {
            int kbs = 20, docsPerKb = 50, chunksPerDoc = 1000; // 默认即 1M chunk
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--kbs" -> kbs = Integer.parseInt(args[++i]);
                    case "--docs-per-kb" -> docsPerKb = Integer.parseInt(args[++i]);
                    case "--chunks-per-doc" -> chunksPerDoc = Integer.parseInt(args[++i]);
                    case "--help", "-h" -> {
                        System.out.println("""
                                Usage: java -jar veridex-capacity-tools-0.1.0.jar [options]
                                  --kbs N            知识库数量 (default 20)
                                  --docs-per-kb M    每库文档数 (default 50)
                                  --chunks-per-doc K 每文档 chunk 数 (default 1000)
                                Defaults product = 20*50*1000 = 1,000,000 chunks.
                                自校验：--kbs 2 --docs-per-kb 5 --chunks-per-doc 100 (=1000 chunks)""");
                        System.exit(0);
                    }
                    default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
                }
            }
            if (kbs <= 0 || docsPerKb <= 0 || chunksPerDoc <= 0) {
                throw new IllegalArgumentException("kbs/docs-per-kb/chunks-per-doc must be positive");
            }
            return new Args(kbs, docsPerKb, chunksPerDoc);
        }
    }

    // ------------------------------------------------------------ corpus

    /** 8 类中文制度模板的参数化语料生成器。 */
    public static final class PolicyCorpus {

        private static final String[] COMPANIES = {
                "华东制造集团有限公司", "北方数据科技有限公司", "西部能源股份有限公司",
                "南方智能装备有限公司", "中部物流产业集团有限公司"
        };
        private static final String[] DEPARTMENTS = {
                "财务部", "市场部", "研发部", "销售部", "人力资源部", "运营部", "技术部", "行政部"
        };
        private static final String[] TYPE_NAMES = {
                "请假", "报销", "差旅", "保密", "考勤", "采购", "安全", "培训"
        };
        private static final String[][] SECTIONS = {
                {"总则", "请假类型与适用范围", "请假审批流程", "病假管理规定", "事假管理规定",
                        "年休假管理规定", "产假与陪产假规定", "请假材料要求", "考勤与绩效衔接", "违规处理"},
                {"总则", "报销流程", "票据要求", "审批权限", "报销时限", "付款时效",
                        "差旅费用报销", "业务招待费管理", "财务审核", "违规处理"},
                {"总则", "出差申请", "交通标准", "住宿标准", "市内交通", "伙食补助",
                        "报销时限", "出国出差", "行程变更", "安全要求"},
                {"总则", "密级划分", "涉密文件管理", "电子信息管理", "对外提供资料", "离职脱密",
                        "涉密会议", "泄密报告", "责任追究", "检查培训"},
                {"总则", "工作时间", "迟到早退", "外出登记", "加班管理", "考勤申诉",
                        "月度汇总", "旷工处理", "考勤纪律", "节假日值班"},
                {"总则", "采购申请", "询价比价", "合同管理", "供应商管理", "验收管理",
                        "付款管理", "紧急采购", "廉洁要求", "档案管理"},
                {"总则", "安全责任", "消防管理", "应急疏散", "用电安全", "隐患报告",
                        "教育培训", "事故处置", "安全巡查", "奖惩制度"},
                {"总则", "入职培训", "年度计划", "培训形式", "费用预算", "服务协议",
                        "培训总结", "学时要求", "效果评估", "纪律要求"},
        };
        private static final String[][] SENTENCES = {
                { // 0 请假
                        "本制度适用于公司全体在职员工，请假申请应当提前填写《请假申请单》并通过公司人事系统提交。",
                        "员工请假分为病假、事假、年休假、婚假、产假与陪产假等类型，不同类型适用不同的审批层级与时限要求。",
                        "请假流程包括填写申请、直属主管审批、部门负责人复核与人力资源部备案四个环节，缺一不可。",
                        "请假三天以内由直属主管审批，三天以上七天以内由部门负责人审批，七天以上须经人力资源总监批准。",
                        "病假应当提供二级甲等以上医院出具的诊断证明，急诊可事后补交，补交时限不超过返岗后三个工作日。",
                        "年休假按员工累计工龄分段计算，工作满一年不满十年的每年五天，满十年不满二十年的每年十天，二十年以上的每年十五天。",
                        "请假期间应当保持通讯畅通，突发情况无法按时返岗的，应当及时电话说明并补充书面材料。",
                        "当月请假累计超过十五个工作日的，绩效奖金按实际出勤天数折算，考勤记录同步更新。",
                        "虚假请假、骗取假期经查实的，视情节给予警告、记过直至解除劳动合同的处理。",
                        "请假申请获批后应当在系统内查看审批记录，未获批准擅自离岗的按旷工处理。" },
                { // 1 报销
                        "本制度适用于公司全体员工日常费用报销，报销事项应当真实、合规、及时，严禁虚报冒领。",
                        "报销流程包括填写报销单、粘贴原始票据、部门负责人审批、财务审核与出纳付款五个环节。",
                        "费用报销应当提供合法有效的增值税发票，电子发票需同时打印并留存电子原件备查。",
                        "单笔金额五百元以下的日常采购由部门负责人审批，五百元至五千元须财务经理复核，五千元以上须总经理审批。",
                        "办公用品、通讯费、交通费等日常费用每月集中报销两次，分别在每月十日和二十五日前提交。",
                        "报销款项原则上在审核通过后五个工作日内到账，特殊情况需加急的须书面说明原因。",
                        "差旅费用报销应当附行程单与住宿清单，超出公司标准的费用不予报销，特殊情况须提前审批。",
                        "业务招待费报销须注明招待对象、事由与人数，单次超过两千元须提前报备。",
                        "财务部门对报销凭证存疑的，有权要求经办人补充说明或提供佐证材料，拒不配合的不予报销。",
                        "虚开发票、重复报销等违规行为一经查实，追回款项并按公司制度严肃处理。" },
                { // 2 差旅
                        "本制度适用于公司员工因公出差的全过程管理，出差应当事先申请、事中留痕、事后报销。",
                        "出差申请应当明确目的地、事由、预计天数与预估费用，经部门负责人批准后方可出行。",
                        "国内出差乘坐高铁二等座、飞机经济舱，住宿标准一线城市每晚不超过四百五十元。",
                        "出差人员应当通过公司指定平台预订机票酒店，行程变更应当及时报备并保留变更凭证。",
                        "出差期间产生的市内交通费凭发票实报实销，每人每天上限一百元。",
                        "出差伙食补助按自然日计算，省内出差每天八十元，省外出差每天一百二十元。",
                        "出差结束后五个工作日内提交差旅报销单，附行程单、发票与审批记录。",
                        "因公出国出差须提前十五天提交申请，附邀请函与外事审批材料，费用标准按另行规定执行。",
                        "出差途中因不可抗力延误行程的，应当第一时间报告部门负责人并留存相关证明材料。",
                        "出差人员应当注意人身与财产安全，遵守目的地法律法规，违规行为责任自负。" },
                { // 3 保密
                        "本制度适用于公司全体员工及外部合作方，全体员工入职时应当签署保密协议。",
                        "公司秘密分为核心秘密、重要秘密与一般秘密三级，分级标注并限定知悉范围。",
                        "涉密文件应当存放在指定保密柜中，电子文档应当加密存储并设置访问权限。",
                        "严禁将涉密资料通过个人邮箱、即时通讯工具或网盘等非授权渠道传输。",
                        "对外提供资料应当履行审批手续，涉及核心秘密的须经总经理批准。",
                        "员工离职时应当归还全部涉密载体并签署保密承诺，脱密期按岗位约定执行。",
                        "涉密会议应当控制参会人员范围，会议材料会后统一回收销毁。",
                        "发现泄密隐患应当立即报告保密管理部门，主动报告可酌情减轻责任。",
                        "未经授权披露公司秘密的，视情节给予处分直至追究法律责任。",
                        "保密管理部门定期开展保密检查与保密培训，检查结果纳入部门绩效考核。" },
                { // 4 考勤
                        "本制度适用于公司全体员工考勤管理，实行上下班打卡制度，考勤记录作为薪酬发放依据。",
                        "标准工作时间为周一至周五上午九点至下午六点，午休一小时，实行弹性上下班的部门另行规定。",
                        "迟到早退十五分钟以内记警告一次，超过十五分钟按旷工半天处理。",
                        "员工因公外出应当提前报备，填写外出登记表并由部门负责人确认。",
                        "加班应当事先申请，经批准后加班时间可调休或按国家规定支付加班费。",
                        "考勤异常应当在三个工作日内通过系统提交申诉并附证明材料，逾期不予处理。",
                        "月度考勤汇总由部门考勤员在次月三日前提交人力资源部复核。",
                        "连续旷工三天或累计旷工五天以上的，公司有权解除劳动合同。",
                        "考勤数据由人事系统自动采集，任何人不得代打卡，代打卡双方均按严重违纪处理。",
                        "法定节假日按国家规定执行，值班安排由各部门提前一周报人力资源部备案。" },
                { // 5 采购
                        "本制度适用于公司各类物资与服务的采购管理，采购应当坚持公开、公平、比质比价原则。",
                        "采购申请应当注明品名、规格、数量、用途与期望到货时间，经部门负责人审批后提交采购部。",
                        "单笔采购金额一万元以下的由采购专员询价三家后比价采购，一万元以上须公开比选。",
                        "采购合同应当明确标的、数量、质量、价款、交付与违约责任，重大合同须法务审核。",
                        "供应商应当通过资质审查并纳入合格供应商名录，名录每年复核一次。",
                        "到货验收由使用部门与采购部共同完成，验收不合格的按合同约定退换货。",
                        "采购付款凭合同、发票与验收单三单匹配后办理，禁止无合同先付款。",
                        "紧急采购可先执行后补手续，但应当在三个工作日内补齐全部审批材料。",
                        "采购人员不得收受供应商礼品礼金，违者按公司廉洁制度严肃处理。",
                        "采购档案包括申请、比价、合同、验收与付款凭证，保存期限不少于五年。" },
                { // 6 安全
                        "本制度适用于公司办公区域与生产经营场所的安全管理，坚持安全第一、预防为主、综合治理的方针。",
                        "各部门负责人为本部门安全第一责任人，应当落实安全责任制并定期开展安全检查。",
                        "办公区域严禁存放易燃易爆物品，消防通道与安全出口应当保持畅通。",
                        "员工应当掌握灭火器与消防栓的使用方法，熟悉所在楼层的应急疏散路线。",
                        "电气设备使用应当符合安全规范，严禁私拉乱接电线，下班应当关闭非必要电源。",
                        "发现安全隐患应当立即报告安全管理部门，重大隐患先撤离人员再组织处置。",
                        "新员工上岗前应当接受安全教育培训，考核合格后方可上岗作业。",
                        "发生安全事故应当立即启动应急预案，保护现场并及时上报，严禁瞒报迟报。",
                        "安全管理部门每月组织一次安全巡查，巡查结果通报并纳入部门考核。",
                        "对安全生产作出突出贡献的部门和个人给予表彰奖励，违规操作造成事故的追究责任。" },
                { // 7 培训
                        "本制度适用于公司培训体系建设与员工培训管理，培训应当服务业务发展需要。",
                        "员工入职后应当参加入职培训，内容包括企业文化、规章制度与岗位技能。",
                        "各部门应当于每年一月提交年度培训计划，人力资源部汇总后统筹安排。",
                        "内部培训由业务骨干担任讲师，外部培训须经部门负责人与人力资源部双重审批。",
                        "培训费用按每人每年预算执行，超出预算的培训项目须总经理批准。",
                        "参加外部培训的员工应当与公司签订培训服务协议，约定服务期与违约责任。",
                        "培训结束后应当提交培训总结并在部门内部分享转训，确保培训成果落地。",
                        "关键岗位员工每年培训学时不少于四十小时，培训记录纳入个人档案。",
                        "培训效果通过考试、实操与绩效改进等方式评估，评估结果用于改进培训计划。",
                        "无故缺席培训或培训考核不合格的，取消当年评优资格并按制度处理。" },
        };

        public record Chunk(String text, String title, String structurePath) {
        }

        private PolicyCorpus() {
        }

        /** 生成 docNo 文档的第 chunkNo 个 chunk（200-500 字，参数化展开）。 */
        public static Chunk chunk(int type, int docNo, int chunkNo) {
            String company = COMPANIES[docNo % COMPANIES.length];
            String dept = DEPARTMENTS[(docNo + chunkNo) % DEPARTMENTS.length];
            String typeName = TYPE_NAMES[type];
            String section = SECTIONS[type][chunkNo % SECTIONS[type].length];
            int year = 2024 + docNo % 5;
            int month = 1 + chunkNo % 12;
            int day = 1 + docNo % 28;

            String[] pool = SENTENCES[type];
            int start = (docNo * 3 + chunkNo) % pool.length;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                sb.append(pool[(start + i) % pool.length]);
            }
            sb.append("本规定由").append(dept).append("负责解释，自").append(year)
                    .append("年").append(month).append("月").append(day).append("日起施行，适用于")
                    .append(company).append("全体员工，此前相关规定同时废止。");

            String title = "《" + typeName + "管理制度》-" + section;
            String structurePath = typeName + "管理制度/第" + (chunkNo % 10 + 1) + "章/" + section;
            return new Chunk(sb.toString(), title, structurePath);
        }
    }
}
