package io.veridex.ingestion.infrastructure;

import com.rabbitmq.client.Channel;
import io.veridex.audit.api.AuditRecorder;
import io.veridex.ingestion.application.DocumentParser;
import io.veridex.ingestion.application.StructureChunker;
import io.veridex.ingestion.domain.Chunk;
import io.veridex.ingestion.domain.ParsedDocument;
import io.veridex.knowledge.api.DocumentVersionProcessing;
import io.veridex.knowledge.api.ObjectStorage;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 文档入库 worker：parse → chunk → 写解析产物与 chunk 清单 → markReady。
 * 手动 ack；失败 basicReject(requeue=false) 进 DLQ；已 READY 幂等确认。
 * 索引发布由知识管理员手动触发（KnowledgeBasePublishService）。
 */
@Component
public class DocumentIngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionWorker.class);

    private final DocumentVersionProcessing documents;
    private final ObjectStorage storage;
    private final DocumentParser parser;
    private final StructureChunker chunker;
    private final AuditRecorder audit;
    private final JsonMapper jsonMapper;

    public DocumentIngestionWorker(DocumentVersionProcessing documents, ObjectStorage storage,
                                   DocumentParser parser, StructureChunker chunker,
                                   AuditRecorder audit, JsonMapper jsonMapper) {
        this.documents = documents;
        this.storage = storage;
        this.parser = parser;
        this.chunker = chunker;
        this.audit = audit;
        this.jsonMapper = jsonMapper;
    }

    @RabbitListener(queues = io.veridex.shared.infrastructure.messaging.RabbitTopology.INGESTION_QUEUE)
    public void onIngest(byte[] payload, Channel channel,
                         @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        UUID versionId = null;
        try {
            Map<String, Object> message = jsonMapper.readValue(payload, Map.class);
            versionId = UUID.fromString(String.valueOf(message.get("documentVersionId")));
            UUID kbId = UUID.fromString(String.valueOf(message.get("knowledgeBaseId")));
            String objectKey = String.valueOf(message.get("objectKey"));
            String filename = String.valueOf(message.get("filename"));
            String contentType = String.valueOf(message.get("contentType"));

            String status = documents.findVersionStatus(versionId);
            if ("READY".equals(status)) {
                // 幂等：已处理完成，直接确认
                channel.basicAck(deliveryTag, false);
                return;
            }
            if (!"UPLOADED".equals(status)) {
                throw new IllegalStateException("unexpected status " + status);
            }
            documents.markProcessing(versionId);

            ParsedDocument parsed;
            try (var in = storage.get(objectKey)) {
                parsed = parser.parse(in, filename, contentType);
            }
            List<Chunk> chunks = chunker.chunk(parsed);
            List<Map<String, Object>> records = chunks.stream()
                    .map(c -> Map.<String, Object>of(
                            "index", c.index(), "text", c.text(), "title", c.title(), "structurePath", c.structurePath()))
                    .toList();

            byte[] parsedBytes = jsonMapper.writeValueAsBytes(parsed.text());
            storage.put(objectKey + ".parsed.json",
                    new java.io.ByteArrayInputStream(parsedBytes), "application/json", parsedBytes.length);
            byte[] chunksBytes = jsonMapper.writeValueAsBytes(records);
            storage.put(objectKey + ".chunks.json",
                    new java.io.ByteArrayInputStream(chunksBytes), "application/json", chunksBytes.length);
            documents.setParsedObjectKey(versionId, objectKey + ".parsed.json");

            documents.markReady(versionId, chunks.size());
            audit.record(null, "ingestion.completed", "document_version", versionId, null,
                    Map.of("chunkCount", chunks.size(), "knowledgeBaseId", kbId.toString()));

            channel.basicAck(deliveryTag, false);
            log.info("ingestion completed for version {}", versionId);
        } catch (Exception e) {
            log.error("ingestion failed for message", e);
            if (versionId != null) {
                try {
                    documents.markFailed(versionId, e.getMessage());
                } catch (Exception ignored) {
                    // 状态标记失败不阻塞拒信
                }
            }
            try {
                channel.basicReject(deliveryTag, false);
            } catch (Exception reject) {
                log.error("failed to reject message", reject);
            }
        }
    }
}
