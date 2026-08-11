package io.veridex.ingestion;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.Channel;
import io.veridex.audit.api.AuditRecorder;
import io.veridex.indexing.api.ChunkIndexer;
import io.veridex.indexing.api.DraftRelease;
import io.veridex.indexing.api.IndexReleaseManager;
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
import org.mockito.InOrder;
import tools.jackson.databind.json.JsonMapper;

class DocumentIngestionWorkerTest {

    @Test
    void chunkIndexFailureDiscardsDraftWithoutPublishing() throws Exception {
        DocumentVersionProcessing documents = mock(DocumentVersionProcessing.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DocumentParser parser = mock(DocumentParser.class);
        StructureChunker chunker = mock(StructureChunker.class);
        IndexReleaseManager releases = mock(IndexReleaseManager.class);
        ChunkIndexer indexer = mock(ChunkIndexer.class);
        AuditRecorder audit = mock(AuditRecorder.class);
        Channel channel = mock(Channel.class);
        JsonMapper jsonMapper = JsonMapper.builder().build();
        DocumentIngestionWorker worker = new DocumentIngestionWorker(
                documents, storage, parser, chunker, releases, indexer, audit, jsonMapper);

        UUID versionId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        String objectKey = "kb/document/v2/guide.md";
        when(documents.findVersionStatus(versionId)).thenReturn("UPLOADED");
        when(storage.get(objectKey)).thenReturn(new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));
        when(parser.parse(any(), eq("guide.md"), eq("text/markdown")))
                .thenReturn(new ParsedDocument("content", "guide.md", "text/markdown"));
        when(chunker.chunk(any())).thenReturn(List.of(new Chunk(0, "content", "guide.md", "1")));
        when(releases.createDraft(eq(knowledgeBaseId), eq(versionId), anyString()))
                .thenReturn(new DraftRelease(releaseId, "veridex-2", "active"));
        doThrow(new IllegalStateException("bulk failed")).when(indexer)
                .index(eq(knowledgeBaseId), eq(versionId), any(), eq("veridex-2"), eq(releaseId));

        String payload = "{\"documentVersionId\":\"" + versionId
                + "\",\"knowledgeBaseId\":\"" + knowledgeBaseId
                + "\",\"objectKey\":\"" + objectKey
                + "\",\"filename\":\"guide.md\",\"contentType\":\"text/markdown\"}";
        worker.onIngest(payload.getBytes(StandardCharsets.UTF_8), channel, 2L);

        verify(releases).discardDraft(releaseId);
        verify(documents).markFailed(versionId, "bulk failed");
        verify(channel).basicReject(2L, false);
        org.mockito.Mockito.verify(releases, org.mockito.Mockito.never()).publish(releaseId);
    }

    @Test
    void indexesChunksBeforePublishingReleaseAlias() throws Exception {
        DocumentVersionProcessing documents = mock(DocumentVersionProcessing.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        DocumentParser parser = mock(DocumentParser.class);
        StructureChunker chunker = mock(StructureChunker.class);
        IndexReleaseManager releases = mock(IndexReleaseManager.class);
        ChunkIndexer indexer = mock(ChunkIndexer.class);
        AuditRecorder audit = mock(AuditRecorder.class);
        Channel channel = mock(Channel.class);
        JsonMapper jsonMapper = JsonMapper.builder().build();
        DocumentIngestionWorker worker = new DocumentIngestionWorker(
                documents, storage, parser, chunker, releases, indexer, audit, jsonMapper);

        UUID versionId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID releaseId = UUID.randomUUID();
        String objectKey = "kb/document/v1/guide.md";
        when(documents.findVersionStatus(versionId)).thenReturn("UPLOADED");
        when(storage.get(objectKey)).thenReturn(new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)));
        when(parser.parse(any(), eq("guide.md"), eq("text/markdown")))
                .thenReturn(new ParsedDocument("content", "guide.md", "text/markdown"));
        when(chunker.chunk(any())).thenReturn(List.of(new Chunk(0, "content", "guide.md", "1")));
        when(releases.createDraft(eq(knowledgeBaseId), eq(versionId), anyString()))
                .thenReturn(new DraftRelease(releaseId, "veridex-1", "active"));

        String payload = "{\"documentVersionId\":\"" + versionId
                + "\",\"knowledgeBaseId\":\"" + knowledgeBaseId
                + "\",\"objectKey\":\"" + objectKey
                + "\",\"filename\":\"guide.md\",\"contentType\":\"text/markdown\"}";
        worker.onIngest(payload.getBytes(StandardCharsets.UTF_8), channel, 1L);

        InOrder order = inOrder(releases, indexer, documents, channel);
        order.verify(releases).prepare(releaseId);
        order.verify(indexer).index(eq(knowledgeBaseId), eq(versionId), any(), eq("veridex-1"), eq(releaseId));
        order.verify(releases).publish(releaseId);
        order.verify(documents).markReady(versionId, 1);
        order.verify(channel).basicAck(1L, false);
    }
}
