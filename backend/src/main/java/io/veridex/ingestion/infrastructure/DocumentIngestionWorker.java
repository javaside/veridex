package io.veridex.ingestion.infrastructure;

import com.rabbitmq.client.Channel;
import io.veridex.audit.api.AuditRecorder;
import io.veridex.configuration.api.ConfigurationProfileQuery;
import io.veridex.configuration.api.ProfileDefaults;
import io.veridex.ingestion.application.DocumentParser;
import io.veridex.ingestion.application.StructureChunker;
import io.veridex.ingestion.domain.Chunk;
import io.veridex.ingestion.domain.ParsedDocument;
import io.veridex.knowledge.api.DocumentVersionProcessing;
import io.veridex.knowledge.api.ObjectStorage;
import io.veridex.shared.observability.ObservationName;
import io.veridex.shared.observability.RabbitContextPropagation;
import io.veridex.shared.observability.TelemetryErrorCode;
import io.veridex.shared.observability.TelemetryOutcome;
import io.veridex.shared.observability.TelemetryTag;
import io.veridex.shared.observability.VeridexObservability;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final VeridexObservability observability;
    private final RabbitContextPropagation propagation;
    private final ParserExecutionGuard guard;
    private final ConfigurationProfileQuery profileQuery;

    public DocumentIngestionWorker(DocumentVersionProcessing documents, ObjectStorage storage,
                                   DocumentParser parser, StructureChunker chunker,
                                   AuditRecorder audit, JsonMapper jsonMapper) {
        this(documents, storage, parser, chunker, audit, jsonMapper, null, null, null, null);
    }

    @Autowired
    public DocumentIngestionWorker(DocumentVersionProcessing documents, ObjectStorage storage,
                                   DocumentParser parser, StructureChunker chunker,
                                   AuditRecorder audit, JsonMapper jsonMapper,
                                   VeridexObservability observability, RabbitContextPropagation propagation,
                                   ParserExecutionGuard guard, ConfigurationProfileQuery profileQuery) {
        this.documents = documents;
        this.storage = storage;
        this.parser = parser;
        this.chunker = chunker;
        this.audit = audit;
        this.jsonMapper = jsonMapper;
        this.observability = observability;
        this.propagation = propagation;
        this.guard = guard;
        this.profileQuery = profileQuery;
    }

    @RabbitListener(queues = io.veridex.shared.infrastructure.messaging.RabbitTopology.INGESTION_QUEUE)
    public void onIngest(byte[] payload, Channel channel,
                         @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                         MessageProperties properties) {
        process(payload, channel, deliveryTag, properties);
    }

    public void onIngest(byte[] payload, Channel channel, long deliveryTag) {
        process(payload, channel, deliveryTag, null);
    }

    private void process(byte[] payload, Channel channel, long deliveryTag, MessageProperties properties) {
        UUID versionId = null;
        var observation = observability == null ? null : observability.start(ObservationName.INGESTION_RUN,
                TelemetryTag.ingestionStage(TelemetryOutcome.IngestionStage.UNKNOWN));
        String failureCode = TelemetryErrorCode.INGESTION_UNKNOWN.wireValue();
        try {
            restoreRequestId(properties);
            Map<String, Object> message = jsonMapper.readValue(payload, Map.class);
            versionId = UUID.fromString(String.valueOf(message.get("documentVersionId")));
            UUID kbId = UUID.fromString(String.valueOf(message.get("knowledgeBaseId")));
            String objectKey = String.valueOf(message.get("objectKey"));
            String filename = String.valueOf(message.get("filename"));
            String contentType = String.valueOf(message.get("contentType"));

            String status = documents.findVersionStatus(versionId);
            if ("READY".equals(status)) {
                channel.basicAck(deliveryTag, false);
                recordAck(TelemetryOutcome.Ingestion.ALREADY_READY);
                finishSuccess(observation, TelemetryOutcome.Ingestion.ALREADY_READY);
                return;
            }
            if (!"UPLOADED".equals(status)) {
                throw new IllegalStateException("unexpected status");
            }
            documents.markProcessing(versionId);

            ParsedDocument parsed = guard != null
                    ? guard.execute(versionId, taskDir -> parseDocument(objectKey, filename, contentType))
                    : parseDocument(objectKey, filename, contentType);
            var chunking = profileQuery != null
                    ? profileQuery.activeProfileConfig().chunking()
                    : ProfileDefaults.defaults().chunking();
            List<Chunk> chunks = chunker.chunk(parsed, chunking.maxChars(), chunking.overlap());
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
            recordAck(TelemetryOutcome.Ingestion.SUCCESS);
            finishSuccess(observation, TelemetryOutcome.Ingestion.SUCCESS);
        } catch (Exception exception) {
            if (versionId != null) {
                try {
                    documents.markFailed(versionId, failureCode);
                } catch (Exception ignored) {
                    // 状态标记失败不阻塞拒信
                }
            }
            try {
                channel.basicReject(deliveryTag, false);
            } catch (Exception ignored) {
                // reject telemetry remains bounded and fail-open
            }
            recordReject(TelemetryOutcome.Ingestion.FAILED);
            if (observation != null) {
                observation.failure(TelemetryErrorCode.INGESTION_UNKNOWN);
                observation.close();
            }
        } finally {
            MDC.remove("requestId");
        }
    }

    private ParsedDocument parseDocument(String objectKey, String filename, String contentType) throws java.io.IOException {
        try (var in = storage.get(objectKey)) {
            return parser.parse(in, filename, contentType);
        }
    }

    private void restoreRequestId(MessageProperties properties) {
        if (properties == null || propagation == null) return;
        var context = propagation.extract(properties);
        if (context.requestId() != null) MDC.put("requestId", context.requestId());
    }

    private void recordAck(TelemetryOutcome.Ingestion result) {
        if (observability != null) {
            observability.increment(io.veridex.shared.observability.MetricName.INGESTION_ACK,
                    TelemetryTag.ingestionOutcome(result));
        }
    }

    private void recordReject(TelemetryOutcome.Ingestion result) {
        if (observability != null) {
            observability.increment(io.veridex.shared.observability.MetricName.INGESTION_REJECT,
                    TelemetryTag.ingestionOutcome(result));
        }
    }

    private static void finishSuccess(VeridexObservability.ObservationScope observation,
                                      TelemetryOutcome.Ingestion result) {
        if (observation != null) {
            observation.success(TelemetryTag.ingestionOutcome(result));
            observation.close();
        }
    }
}
