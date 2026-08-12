package io.veridex.qa.api;

import java.util.List;
import java.util.UUID;

/**
 * 问答请求。knowledgeBaseIds 为客户端请求的知识库；conversationId 为空则新建会话。
 */
public record AskRequest(String question, List<UUID> knowledgeBaseIds, UUID conversationId) {
}
