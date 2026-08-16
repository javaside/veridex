package io.veridex.ingestion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import io.veridex.audit.api.AuditRecorder;
import io.veridex.ingestion.application.DocumentParser;
import io.veridex.ingestion.application.StructureChunker;
import io.veridex.ingestion.domain.Chunk;
import io.veridex.ingestion.domain.ParsedDocument;
import io.veridex.ingestion.infrastructure.DocumentIngestionWorker;
import io.veridex.knowledge.api.DocumentVersionProcessing;
import io.veridex.knowledge.api.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import tools.jackson.databind.json.JsonMapper;

class DocumentIngestionWorkerTest {

    private DocumentIngestionWorker worker(DocumentVersionProcessing documents, ObjectStorage storage,
                                           DocumentParser parser, StructureChunker chunker,
                                           AuditRecorder audit) {
        return new DocumentIngestionWorker(documents, storage, parser, chunker, audit,
                JsonMapper.builder().build());
    }

    @Test
    void chunkIndexFailureDiscardsDraftWithoutPublishing() throws Exception {
        DocumentVersionProcessing documents = mock(DocumentVersionProcessing.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DocumentParser parser = mock(DocumentParser.class);
        StructureChunker chunker = mock(StructureChunker.class);
        AuditRecorder audit = mock(AuditRecorder.class);
        Channel channel = mock(Channel.class);
        DocumentIngestionWorker worker = worker(documents, storage, parser, chunker, audit);

        UUID versionId = UUID.randomUUID();
        String objectKey = "kb/document/v2/guide.md";
        when(documents.findVersionStatus(versionId)).thenReturn("UPLOADED");
        when(storage.get(objectKey)).thenReturn(new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));
        when(parser.parse(any(), eq("guide.md"), eq("text/markdown")))
                .thenReturn(new ParsedDocument("content", "guide.md", "text/markdown"));
        when(chunker.chunk(any())).thenReturn(List.of(new Chunk(0, "content", "guide.md", "1")));
        // 写 chunks.json 时 MinIO 异常 → worker 失败
        doThrow(new IllegalStateException("put failed")).when(storage)
                .put(anyString(), any(), anyString(), org.mockito.ArgumentMatchers.anyLong());

        String payload = "{\"documentVersionId\":\"" + versionId
                + "\",\"knowledgeBaseId\":\"" + UUID.randomUUID()
                + "\",\"objectKey\":\"" + objectKey
                + "\",\"filename\":\"guide.md\",\"contentType\":\"text/markdown\"}";
        worker.onIngest(payload.getBytes(StandardCharsets.UTF_8), channel, 2L);

        verify(documents).markFailed(versionId, "ingestion_unknown");
        verify(channel).basicReject(2L, false);
        verify(documents, never()).markReady(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void writesChunkManifestAndMarksReadyWithoutPublishing() throws Exception {
        DocumentVersionProcessing documents = mock(DocumentVersionProcessing.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DocumentParser parser = mock(DocumentParser.class);
        StructureChunker chunker = mock(StructureChunker.class);
        AuditRecorder audit = mock(AuditRecorder.class);
        Channel channel = mock(Channel.class);
        DocumentIngestionWorker worker = worker(documents, storage, parser, chunker, audit);

        UUID versionId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        String objectKey = "kb/document/v1/guide.md";
        when(documents.findVersionStatus(versionId)).thenReturn("UPLOADED");
        when(storage.get(objectKey)).thenReturn(new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));
        when(parser.parse(any(), eq("guide.md"), eq("text/markdown")))
                .thenReturn(new ParsedDocument("content", "guide.md", "text/markdown"));
        when(chunker.chunk(any())).thenReturn(List.of(new Chunk(0, "content", "guide.md", "1")));

        String payload = "{\"documentVersionId\":\"" + versionId
                + "\",\"knowledgeBaseId\":\"" + knowledgeBaseId
                + "\",\"objectKey\":\"" + objectKey
                + "\",\"filename\":\"guide.md\",\"contentType\":\"text/markdown\"}";
        worker.onIngest(payload.getBytes(StandardCharsets.UTF_8), channel, 1L);

        verify(storage).put(eq(objectKey + ".parsed.json"), any(), eq("application/json"), ArgumentMatchers.anyLong());
        verify(storage).put(eq(objectKey + ".chunks.json"), any(), eq("application/json"), ArgumentMatchers.anyLong());
        verify(documents).setParsedObjectKey(versionId, objectKey + ".parsed.json");
        verify(documents).markReady(versionId, 1);
        verify(audit).record(eq(null), eq("ingestion.completed"), eq("document_version"), eq(versionId),
                eq(null), eq(java.util.Map.of("chunkCount", 1, "knowledgeBaseId", knowledgeBaseId.toString())));
        verify(channel).basicAck(1L, false);
    }

    @Test
    void readyVersionIsAcknowledgedIdempotently() throws Exception {
        DocumentVersionProcessing documents = mock(DocumentVersionProcessing.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DocumentParser parser = mock(DocumentParser.class);
        StructureChunker chunker = mock(StructureChunker.class);
        AuditRecorder audit = mock(AuditRecorder.class);
        Channel channel = mock(Channel.class);
        DocumentIngestionWorker worker = worker(documents, storage, parser, chunker, audit);

        UUID versionId = UUID.randomUUID();
        when(documents.findVersionStatus(versionId)).thenReturn("READY");

        String payload = "{\"documentVersionId\":\"" + versionId
                + "\",\"knowledgeBaseId\":\"" + UUID.randomUUID()
                + "\",\"objectKey\":\"kb/doc/v1/x.md\",\"filename\":\"x.md\",\"contentType\":\"text/markdown\"}";
        worker.onIngest(payload.getBytes(StandardCharsets.UTF_8), channel, 3L);

        verify(channel).basicAck(3L, false);
        verify(documents, never()).markReady(any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
