package io.veridex.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.veridex.knowledge.application.DocumentService;
import io.veridex.knowledge.application.KnowledgeBaseService;
import io.veridex.knowledge.domain.DocumentVersionStatus;
import io.veridex.knowledge.domain.GrantLevel;
import io.veridex.support.PostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class DocumentLifecycleIntegrationTest extends PostgresIntegrationTest {

    @Autowired KnowledgeBaseService knowledgeBases;
    @Autowired DocumentService documents;

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void createKnowledgeBaseGrantsOwnerManageAccess() {
        var kb = knowledgeBases.createKnowledgeBase(ACTOR, "人事制度", "员工人事政策");
        assertThat(kb.getSlug()).isNotBlank();
        assertThat(knowledgeBases.canManage(kb.getId(), ACTOR)).isTrue();
    }

    @Test
    void uploadCreatesUploadedVersionAndSecondUploadBumpsVersion() {
        var kb = knowledgeBases.createKnowledgeBase(ACTOR, "产品手册", null);
        var v1 = documents.upload(ACTOR, kb.getId(), "guide.md", "text/markdown", 1024L, "a".repeat(64));
        assertThat(v1.getStatus()).isEqualTo(DocumentVersionStatus.UPLOADED);
        assertThat(v1.getVersionNo()).isEqualTo(1);

        var v2 = documents.upload(ACTOR, kb.getId(), "guide.md", "text/markdown", 2048L, "b".repeat(64));
        assertThat(v2.getVersionNo()).isEqualTo(2);
    }

    @Test
    void unknownActorCannotManageWithoutGrant() {
        var kb = knowledgeBases.createKnowledgeBase(ACTOR, "财务", null);
        var other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        // 测试线程无 Security context → SecurityContextRole.currentRole() 返回 EMPLOYEE
        assertThat(knowledgeBases.canManage(kb.getId(), other)).isFalse();
        assertThatThrownBy(() -> documents.upload(other, kb.getId(), "x.md", "text/markdown", 1L, "c".repeat(64)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void unsupportedExtensionIsRejected() {
        var kb = knowledgeBases.createKnowledgeBase(ACTOR, "白名单", null);
        assertThatThrownBy(() -> documents.upload(ACTOR, kb.getId(), "virus.exe", "application/octet-stream", 1L, "d".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
