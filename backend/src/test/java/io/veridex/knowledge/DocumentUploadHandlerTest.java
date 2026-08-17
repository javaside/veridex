package io.veridex.knowledge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.veridex.audit.api.AuditRecorder;
import io.veridex.knowledge.api.DocumentUploadHandler;
import io.veridex.knowledge.api.ObjectStorage;
import io.veridex.knowledge.application.DocumentService;
import io.veridex.knowledge.domain.DocumentVersion;
import io.veridex.knowledge.infrastructure.security.UploadContentInspector;
import io.veridex.shared.infrastructure.RequestIds;
import io.veridex.shared.outbox.OutboxWriter;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class DocumentUploadHandlerTest {

    @Test
    void recordsRequestIdInDocumentUploadAudit() {
        DocumentService documents = mock(DocumentService.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        OutboxWriter outbox = mock(OutboxWriter.class);
        AuditRecorder audit = mock(AuditRecorder.class);
        UploadContentInspector inspector = mock(UploadContentInspector.class);
        when(inspector.inspect(any(), any(), any(), anyLong()))
                .thenReturn(UploadContentInspector.InspectionResult.ok());
        DocumentUploadHandler handler = new DocumentUploadHandler(documents, storage, outbox, audit, inspector);
        DocumentVersion version = mock(DocumentVersion.class);
        UUID actorId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        byte[] content = "content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIds.ATTRIBUTE, "request-123");
        when(version.getId()).thenReturn(versionId);
        when(version.getObjectKey()).thenReturn("kb/document/v1/guide.md");
        when(documents.upload(eq(actorId), eq(knowledgeBaseId), eq("guide.md"), eq("text/markdown"),
                anyLong(), any())).thenReturn(version);

        handler.upload(actorId, knowledgeBaseId, "guide.md", "text/markdown", content, request);

        verify(audit).record(actorId, "document.upload", "document_version", versionId, "request-123",
                Map.of("knowledgeBaseId", knowledgeBaseId.toString(), "filename", "guide.md"));
    }
}
