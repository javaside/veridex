package io.veridex.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.veridex.indexing.api.DraftRelease;
import io.veridex.indexing.domain.IndexRelease;
import io.veridex.indexing.domain.IndexReleaseRepository;
import io.veridex.indexing.domain.IndexReleaseStatus;
import io.veridex.indexing.infrastructure.IndexReleaseService;
import io.veridex.shared.infrastructure.config.OpenSearchProperties;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IndexReleaseServiceTest {

    @Mock IndexReleaseRepository releases;
    @Mock io.veridex.indexing.application.SearchIndexGateway gateway;
    @Mock OpenSearchProperties properties;
    @InjectMocks IndexReleaseService service;

    private static final UUID KB = UUID.randomUUID();
    private static final UUID DV = UUID.randomUUID();

    @Test
    void prepareCreatesIndexAndPublishAliasesItAndMarksPublished() {
        when(properties.indexPrefix()).thenReturn("veridex");
        when(properties.dimensions()).thenReturn(128);
        when(releases.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(releases.findMaxVersionNo(KB)).thenReturn(0);
        DraftRelease draft = service.createDraft(KB, DV, "prod-active");
        assertThat(draft.indexName()).isEqualTo("veridex-" + KB + "-1");

        IndexRelease saved = releases.save(new IndexRelease(KB, DV, 1, draft.indexName(), "prod-active"));
        when(releases.findById(draft.releaseId())).thenReturn(Optional.of(saved));
        service.prepare(draft.releaseId());
        service.publish(draft.releaseId());

        verify(gateway).createIndex(draft.indexName(), 128);
        verify(gateway).aliasTo("prod-active", draft.indexName());
        assertThat(saved.getStatus()).isEqualTo(IndexReleaseStatus.PUBLISHED);
    }

    @Test
    void draftsForDifferentKnowledgeBasesUseDifferentIndexNames() {
        UUID otherKnowledgeBase = UUID.randomUUID();
        when(properties.indexPrefix()).thenReturn("veridex");
        when(releases.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(releases.findMaxVersionNo(KB)).thenReturn(0);
        when(releases.findMaxVersionNo(otherKnowledgeBase)).thenReturn(0);

        DraftRelease first = service.createDraft(KB, DV, "first-active");
        DraftRelease second = service.createDraft(otherKnowledgeBase, UUID.randomUUID(), "second-active");

        assertThat(first.indexName()).isNotEqualTo(second.indexName());
    }

    @Test
    void draftVersionContinuesAfterDeletedRelease() {
        when(properties.indexPrefix()).thenReturn("veridex");
        when(releases.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(releases.findMaxVersionNo(KB)).thenReturn(3);

        DraftRelease draft = service.createDraft(KB, DV, "prod-active");

        assertThat(draft.indexName()).isEqualTo("veridex-" + KB + "-4");
    }

    @Test
    void offlineRemovesAliasAndMarksOffline() {
        IndexRelease published = new IndexRelease(KB, DV, 2, "veridex-2", "prod-active");
        published.publish();
        when(releases.findById(published.getId())).thenReturn(Optional.of(published));

        service.offline(KB, published.getId());

        verify(gateway).removeAlias("prod-active");
        assertThat(published.getStatus()).isEqualTo(IndexReleaseStatus.OFFLINE);
    }
}
