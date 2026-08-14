package io.veridex.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.veridex.indexing.api.DraftRelease;
import io.veridex.indexing.domain.IndexRelease;
import io.veridex.indexing.domain.IndexReleaseDocumentRepository;
import io.veridex.indexing.domain.IndexReleaseRepository;
import io.veridex.indexing.domain.IndexReleaseStatus;
import io.veridex.indexing.infrastructure.IndexReleaseService;
import io.veridex.shared.infrastructure.config.EmbeddingProperties;
import io.veridex.shared.infrastructure.config.OpenSearchProperties;
import java.util.List;
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
    @Mock IndexReleaseDocumentRepository snapshotDocuments;
    @Mock io.veridex.indexing.application.SearchIndexGateway gateway;
    @Mock OpenSearchProperties properties;
    @Mock EmbeddingProperties embeddingProperties;
    @InjectMocks IndexReleaseService service;

    private static final UUID KB = UUID.randomUUID();

    @Test
    void prepareCreatesIndexAndPublishAliasesItAndMarksPublished() {
        when(properties.indexPrefix()).thenReturn("veridex");
        when(embeddingProperties.dimensions()).thenReturn(128);
        when(releases.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(releases.findMaxVersionNo(KB)).thenReturn(0);
        DraftRelease draft = service.createDraft(KB, "prod-active");
        assertThat(draft.indexName()).isEqualTo("veridex-" + KB + "-1");

        IndexRelease saved = releases.save(new IndexRelease(KB, 1, draft.indexName(), "prod-active"));
        when(releases.findById(draft.releaseId())).thenReturn(Optional.of(saved));
        when(releases.findByKnowledgeBaseIdAndIsActiveTrue(KB)).thenReturn(List.of());
        service.prepare(draft.releaseId());
        service.publish(draft.releaseId());

        verify(gateway).createIndex(draft.indexName(), 128);
        verify(gateway).aliasTo("prod-active", draft.indexName());
        assertThat(saved.getStatus()).isEqualTo(IndexReleaseStatus.PUBLISHED);
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void publishMarksOnlyNewestReleaseActive() {
        when(releases.save(any())).thenAnswer(inv -> inv.getArgument(0));
        IndexRelease older = new IndexRelease(KB, 1, "veridex-1", "prod-active");
        older.publish();
        older.markActive();
        IndexRelease newer = new IndexRelease(KB, 2, "veridex-2", "prod-active");
        when(releases.findById(newer.getId())).thenReturn(Optional.of(newer));
        when(releases.findByKnowledgeBaseIdAndIsActiveTrue(KB)).thenReturn(List.of(older));

        service.publish(newer.getId());

        assertThat(older.isActive()).isFalse();
        assertThat(newer.isActive()).isTrue();
        assertThat(newer.getStatus()).isEqualTo(IndexReleaseStatus.PUBLISHED);
    }

    @Test
    void draftsForDifferentKnowledgeBasesUseDifferentIndexNames() {
        UUID otherKnowledgeBase = UUID.randomUUID();
        when(properties.indexPrefix()).thenReturn("veridex");
        when(releases.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(releases.findMaxVersionNo(KB)).thenReturn(0);
        when(releases.findMaxVersionNo(otherKnowledgeBase)).thenReturn(0);

        DraftRelease first = service.createDraft(KB, "first-active");
        DraftRelease second = service.createDraft(otherKnowledgeBase, "second-active");

        assertThat(first.indexName()).isNotEqualTo(second.indexName());
    }

    @Test
    void draftVersionContinuesAfterDeletedRelease() {
        when(properties.indexPrefix()).thenReturn("veridex");
        when(releases.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(releases.findMaxVersionNo(KB)).thenReturn(3);

        DraftRelease draft = service.createDraft(KB, "prod-active");

        assertThat(draft.indexName()).isEqualTo("veridex-" + KB + "-4");
    }

    @Test
    void makeCurrentSwitchesActiveFlagAndAliasToTarget() {
        IndexRelease current = new IndexRelease(KB, 3, "veridex-3", "prod-active");
        current.publish();
        current.markActive();
        IndexRelease target = new IndexRelease(KB, 1, "veridex-1", "prod-active");
        target.publish();
        when(releases.findById(target.getId())).thenReturn(Optional.of(target));
        when(releases.findByKnowledgeBaseIdAndIsActiveTrue(KB)).thenReturn(List.of(current));
        when(releases.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.makeCurrent(KB, target.getId());

        verify(gateway).aliasTo("prod-active", "veridex-1");
        assertThat(current.isActive()).isFalse();
        assertThat(target.isActive()).isTrue();
        assertThat(target.getStatus()).isEqualTo(IndexReleaseStatus.PUBLISHED);
    }

    @Test
    void makeCurrentReactivatesOfflineRelease() {
        IndexRelease target = new IndexRelease(KB, 2, "veridex-2", "prod-active");
        target.publish();
        target.offline();
        when(releases.findById(target.getId())).thenReturn(Optional.of(target));
        when(releases.findByKnowledgeBaseIdAndIsActiveTrue(KB)).thenReturn(List.of());
        when(releases.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.makeCurrent(KB, target.getId());

        assertThat(target.getStatus()).isEqualTo(IndexReleaseStatus.PUBLISHED);
        assertThat(target.isActive()).isTrue();
        verify(gateway).aliasTo("prod-active", "veridex-2");
    }

    @Test
    void makeCurrentRejectsAlreadyActiveRelease() {
        IndexRelease active = new IndexRelease(KB, 2, "veridex-2", "prod-active");
        active.publish();
        active.markActive();
        when(releases.findById(active.getId())).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.makeCurrent(KB, active.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void offlineRemovesAliasAndMarksOffline() {
        IndexRelease published = new IndexRelease(KB, 2, "veridex-2", "prod-active");
        published.publish();
        published.markActive();
        when(releases.findById(published.getId())).thenReturn(Optional.of(published));

        service.offline(KB, published.getId());

        verify(gateway).removeAlias("prod-active");
        assertThat(published.getStatus()).isEqualTo(IndexReleaseStatus.OFFLINE);
        assertThat(published.isActive()).isFalse();
    }

    @Test
    void deleteOfActiveReleaseIsRejected() {
        IndexRelease active = new IndexRelease(KB, 4, "veridex-4", "prod-active");
        active.publish();
        active.markActive();
        when(releases.findById(active.getId())).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.delete(KB, active.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deleteOfInactiveReleaseDeletesIndexAndRecord() {
        IndexRelease inactive = new IndexRelease(KB, 5, "veridex-5", "prod-active");
        inactive.publish();
        when(releases.findById(inactive.getId())).thenReturn(Optional.of(inactive));

        service.delete(KB, inactive.getId());

        verify(gateway).deleteIndex("veridex-5");
        verify(releases).delete(inactive);
    }
}
