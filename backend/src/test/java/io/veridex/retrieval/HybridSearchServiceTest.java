package io.veridex.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.veridex.indexing.api.IndexReleaseQuery;
import io.veridex.indexing.api.IndexReleaseQuery.ActiveRelease;
import io.veridex.knowledge.api.DocumentVersionQuery;
import io.veridex.retrieval.api.HybridSearchService;
import io.veridex.retrieval.api.RankedHitView;
import io.veridex.retrieval.api.RerankProvider;
import io.veridex.retrieval.api.SearchHit;
import io.veridex.retrieval.application.ContextAssemblyService;
import io.veridex.retrieval.application.HybridSearchServiceImpl;
import io.veridex.retrieval.infrastructure.OpenSearchRetrievalReader;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HybridSearchServiceTest {

    @Mock OpenSearchRetrievalReader reader;
    @Mock IndexReleaseQuery indexReleases;
    @Mock DocumentVersionQuery documentVersions;
    @Mock ContextAssemblyService assembler;
    @Mock RerankProvider reranker;
    @InjectMocks HybridSearchServiceImpl service;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID KB = UUID.randomUUID();
    private static final UUID RELEASE = UUID.randomUUID();
    private static final UUID VER = UUID.randomUUID();

    /** 默认直通 rerank，个别测试可覆盖。 */
    private void passthroughRerank() {
        when(reranker.rerank(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void searchOnlyScopesToIntersectionOfAuthorizedAndRequested() {
        UUID other = UUID.randomUUID();
        passthroughRerank();
        when(indexReleases.findActiveRelease(KB)).thenReturn(Optional.of(new ActiveRelease(RELEASE, "alias-" + KB)));
        when(indexReleases.listSnapshotDocumentVersionIds(RELEASE)).thenReturn(List.of(VER));
        when(documentVersions.findOnlineVersionIds(List.of(VER))).thenReturn(List.of(VER));
        when(reader.bm25(any(), eq(KB), any(), eq(30))).thenReturn(List.of());
        when(reader.vector(any(), eq(KB), any(), eq(30))).thenReturn(List.of());
        when(assembler.assemble(any(), any(), eq(6), eq(3), eq(4000))).thenReturn(List.of());

        // 请求含未授权库 other，授权集合只有 KB
        var outcome = service.search(USER, List.of(KB), List.of(KB, other), "请假");
        assertThat(outcome.evidence()).isEmpty();
        verify(reader, never()).bm25(any(), eq(other), any(), anyInt());
        verify(reader, never()).vector(any(), eq(other), any(), anyInt());
    }

    @Test
    void searchExcludesOfflineDocumentVersions() {
        passthroughRerank();
        when(indexReleases.findActiveRelease(KB)).thenReturn(Optional.of(new ActiveRelease(RELEASE, "alias-" + KB)));
        when(indexReleases.listSnapshotDocumentVersionIds(RELEASE)).thenReturn(List.of(VER));
        // 快照清单里的版本已 OFFLINE → 在线清单为空 → 不触发任何检索
        when(documentVersions.findOnlineVersionIds(List.of(VER))).thenReturn(List.of());

        var outcome = service.search(USER, List.of(KB), List.of(KB), "请假");
        assertThat(outcome.evidence()).isEmpty();
        verify(reader, never()).bm25(anyString(), eq(KB), any(), anyInt());
        verify(reader, never()).vector(anyString(), eq(KB), any(), anyInt());
    }

    @Test
    void outcomeCarriesRankedHitsForTracing() {
        UUID ver = UUID.randomUUID();
        passthroughRerank();
        var hit = new SearchHit(KB, ver, 0, "请假制度", "1", "员工请假需提前申请", SearchHit.Channel.BM25, 2.0);
        when(indexReleases.findActiveRelease(KB)).thenReturn(Optional.of(new ActiveRelease(RELEASE, "alias-" + KB)));
        when(indexReleases.listSnapshotDocumentVersionIds(RELEASE)).thenReturn(List.of(ver));
        when(documentVersions.findOnlineVersionIds(List.of(ver))).thenReturn(List.of(ver));
        when(reader.bm25(any(), eq(KB), any(), eq(30))).thenReturn(List.of(hit));
        when(reader.vector(any(), eq(KB), any(), eq(30))).thenReturn(List.of());
        when(assembler.assemble(any(), any(), eq(6), eq(3), eq(4000))).thenReturn(List.of());

        var outcome = service.search(USER, List.of(KB), List.of(KB), "请假");
        assertThat(outcome.hits()).isNotEmpty();
        assertThat(outcome.hits().get(0).rank()).isEqualTo(1);
        assertThat(outcome.hits().get(0).documentVersionId()).isEqualTo(ver);
    }

    @Test
    void noActiveReleaseReturnsEmpty() {
        passthroughRerank();
        when(indexReleases.findActiveRelease(KB)).thenReturn(Optional.empty());
        var outcome = service.search(USER, List.of(KB), List.of(KB), "请假");
        assertThat(outcome.evidence()).isEmpty();
        assertThat(outcome.hits()).isEmpty();
    }

    @Test
    void vectorFailureDegradesToBm25Only() {
        UUID ver = UUID.randomUUID();
        var hit = new SearchHit(KB, ver, 0, "请假制度", "1", "员工请假需提前申请", SearchHit.Channel.BM25, 2.0);
        passthroughRerank();
        when(indexReleases.findActiveRelease(KB)).thenReturn(Optional.of(new ActiveRelease(RELEASE, "alias-" + KB)));
        when(indexReleases.listSnapshotDocumentVersionIds(RELEASE)).thenReturn(List.of(ver));
        when(documentVersions.findOnlineVersionIds(List.of(ver))).thenReturn(List.of(ver));
        when(reader.bm25(any(), eq(KB), any(), eq(30))).thenReturn(List.of(hit));
        when(reader.vector(any(), eq(KB), any(), eq(30))).thenThrow(new RuntimeException("field not built for ANN search"));
        when(assembler.assemble(any(), any(), eq(6), eq(3), eq(4000))).thenReturn(List.of());

        // 向量路失败不再让整个检索失败：降级为仅 BM25，并记录降级
        var outcome = service.search(USER, List.of(KB), List.of(KB), "请假");
        assertThat(outcome.hits()).isNotEmpty();
        assertThat(outcome.degradations()).anyMatch(d -> d.contains("vector"));
    }

    @Test
    void bm25FailureDegradesToVectorOnly() {
        UUID ver = UUID.randomUUID();
        var hit = new SearchHit(KB, ver, 0, "请假制度", "1", "员工请假需提前申请", SearchHit.Channel.VECTOR, 1.5);
        passthroughRerank();
        when(indexReleases.findActiveRelease(KB)).thenReturn(Optional.of(new ActiveRelease(RELEASE, "alias-" + KB)));
        when(indexReleases.listSnapshotDocumentVersionIds(RELEASE)).thenReturn(List.of(ver));
        when(documentVersions.findOnlineVersionIds(List.of(ver))).thenReturn(List.of(ver));
        when(reader.bm25(any(), eq(KB), any(), eq(30))).thenThrow(new RuntimeException("bm25 unavailable"));
        when(reader.vector(any(), eq(KB), any(), eq(30))).thenReturn(List.of(hit));
        when(assembler.assemble(any(), any(), eq(6), eq(3), eq(4000))).thenReturn(List.of());

        var outcome = service.search(USER, List.of(KB), List.of(KB), "请假");
        assertThat(outcome.hits()).isNotEmpty();
        assertThat(outcome.degradations()).anyMatch(d -> d.contains("bm25"));
    }

    @Test
    void bothChannelsFailureRaisesSystemError() {
        when(indexReleases.findActiveRelease(KB)).thenReturn(Optional.of(new ActiveRelease(RELEASE, "alias-" + KB)));
        when(indexReleases.listSnapshotDocumentVersionIds(RELEASE)).thenReturn(List.of(VER));
        when(documentVersions.findOnlineVersionIds(List.of(VER))).thenReturn(List.of(VER));
        when(reader.bm25(any(), eq(KB), any(), eq(30))).thenThrow(new RuntimeException("bm25 down"));
        when(reader.vector(any(), eq(KB), any(), eq(30))).thenThrow(new RuntimeException("vector down"));

        // 双路均失败 → 系统错误（不伪装无答案，由上层 run.failed 兜底）
        assertThatThrownBy(() -> service.search(USER, List.of(KB), List.of(KB), "请假"))
                .isInstanceOf(RuntimeException.class);
    }
}
