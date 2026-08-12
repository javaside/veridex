package io.veridex.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.veridex.knowledge.api.KnowledgeBaseAuthorization;
import io.veridex.knowledge.api.KnowledgeScopeQuery;
import io.veridex.knowledge.application.KnowledgeBaseService;
import io.veridex.knowledge.application.KnowledgeScopeQueryImpl;
import io.veridex.knowledge.domain.KnowledgeBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KnowledgeScopeQueryImplTest {

    @Mock KnowledgeBaseService knowledgeBases;
    @Mock KnowledgeBaseAuthorization authorization;
    @InjectMocks KnowledgeScopeQueryImpl scope;

    private static final UUID USER = UUID.randomUUID();

    @Test
    void resolveReturnsIntersectionOfViewableAndRequested() {
        KnowledgeBase kb = new KnowledgeBase("制度", "zd-1", "描述", USER);
        when(knowledgeBases.listViewable(USER)).thenReturn(List.of(kb));
        when(authorization.canView(kb.getId(), USER)).thenReturn(true);
        var result = scope.resolve(USER, List.of(kb.getId(), UUID.randomUUID()));
        assertThat(result).containsExactly(kb.getId());
    }

    @Test
    void resolveDropsRequestedKnowledgeBaseWithoutViewPermission() {
        KnowledgeBase kb = new KnowledgeBase("制度", "zd-1", "描述", USER);
        when(knowledgeBases.listViewable(USER)).thenReturn(List.of());
        var result = scope.resolve(USER, List.of(kb.getId()));
        assertThat(result).isEmpty();
    }

    @Test
    void resolveDropsUnrequestedButViewableKnowledgeBase() {
        KnowledgeBase kb = new KnowledgeBase("制度", "zd-1", "描述", USER);
        KnowledgeBase other = new KnowledgeBase("薪酬", "xc-1", "描述", USER);
        when(knowledgeBases.listViewable(USER)).thenReturn(List.of(kb, other));
        when(authorization.canView(kb.getId(), USER)).thenReturn(true);
        var result = scope.resolve(USER, List.of(kb.getId()));
        assertThat(result).containsExactly(kb.getId());
    }
}
