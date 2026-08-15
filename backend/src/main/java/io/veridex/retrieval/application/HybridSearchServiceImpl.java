package io.veridex.retrieval.application;

import io.veridex.indexing.api.IndexReleaseQuery;
import io.veridex.knowledge.api.DocumentVersionQuery;
import io.veridex.retrieval.api.EvidencePiece;
import io.veridex.retrieval.api.HybridSearchResult;
import io.veridex.retrieval.api.HybridSearchService;
import io.veridex.retrieval.api.RankedHitView;
import io.veridex.retrieval.api.RerankProvider;
import io.veridex.retrieval.api.RetrievalParameters;
import io.veridex.retrieval.api.SearchHit;
import io.veridex.retrieval.infrastructure.OpenSearchRetrievalReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 混合检索编排：授权交集 → 逐库 active release 快照 → 在线版本过滤 → 双路召回 → RRF 融合
 * → Rerank → 上下文组装。返回证据与全部命中明细（供 trace 记录 RetrievalHit）。
 */
@Service
public class HybridSearchServiceImpl implements HybridSearchService {

    private static final RetrievalParameters DEFAULT_PARAMETERS =
            new RetrievalParameters(30, 60, 6, 3, 4000);

    private final OpenSearchRetrievalReader reader;
    private final IndexReleaseQuery indexReleases;
    private final DocumentVersionQuery documentVersions;
    private final ContextAssemblyService assembler;
    private final RerankProvider reranker;

    public HybridSearchServiceImpl(OpenSearchRetrievalReader reader, IndexReleaseQuery indexReleases,
                                   DocumentVersionQuery documentVersions, ContextAssemblyService assembler,
                                   RerankProvider reranker) {
        this.reader = reader;
        this.indexReleases = indexReleases;
        this.documentVersions = documentVersions;
        this.assembler = assembler;
        this.reranker = reranker;
    }

    @Override
    public HybridSearchResult search(UUID userId, List<UUID> authorizedKnowledgeBaseIds,
                                     List<UUID> requestedKnowledgeBaseIds, String question) {
        return search(userId, authorizedKnowledgeBaseIds, requestedKnowledgeBaseIds, question, DEFAULT_PARAMETERS);
    }

    @Override
    public HybridSearchResult search(UUID userId, List<UUID> authorizedKnowledgeBaseIds,
                                     List<UUID> requestedKnowledgeBaseIds, String question,
                                     RetrievalParameters parameters) {
        List<UUID> scope = authorizedKnowledgeBaseIds.stream()
                .filter(requestedKnowledgeBaseIds::contains)
                .toList();
        List<SearchHit> all = new ArrayList<>();
        List<String> degradations = new ArrayList<>();
        for (UUID kbId : scope) {
            all.addAll(searchKnowledgeBase(kbId, question, degradations, parameters));
        }
        List<RankFusion.RankedHit> fused = RankFusion.fuse(
                all.stream().filter(h -> h.channel() == SearchHit.Channel.BM25).toList(),
                all.stream().filter(h -> h.channel() == SearchHit.Channel.VECTOR).toList(),
                parameters.rrfK());
        List<SearchHit> reranked = reranker.rerank(
                fused.stream().map(f -> new SearchHit(f.knowledgeBaseId(), f.documentVersionId(), f.chunkIndex(),
                        f.title(), f.structurePath(), f.text(), SearchHit.Channel.BM25, f.fusionScore())).toList(),
                question);
        List<EvidencePiece> evidence = assembler.assemble(reranked.stream().map(r -> new RankFusion.RankedHit(
                r.knowledgeBaseId(), r.documentVersionId(), r.chunkIndex(), r.title(), r.structurePath(),
                r.text(), r.score(), r.score(), r.score())).toList(),
                question, parameters.contextTopK(), parameters.perDocumentMax(), parameters.contextMaxChars());

        Set<String> inContext = evidence.stream()
                .map(e -> e.documentVersionId() + ":" + e.chunkIndex())
                .collect(Collectors.toSet());
        List<RankedHitView> views = new ArrayList<>();
        for (int i = 0; i < fused.size(); i++) {
            RankFusion.RankedHit f = fused.get(i);
            boolean entered = inContext.contains(f.documentVersionId() + ":" + f.chunkIndex());
            views.add(new RankedHitView(f.knowledgeBaseId(), f.documentVersionId(), f.chunkIndex(),
                    "BM25", f.bm25Score(), f.vectorScore(), f.fusionScore(), i + 1, entered,
                    entered ? null : "below-context-budget"));
        }
        return new HybridSearchResult(evidence, views, degradations);
    }

    private List<SearchHit> searchKnowledgeBase(UUID kbId, String question, List<String> degradations,
                                                RetrievalParameters parameters) {
        return indexReleases.findActiveRelease(kbId)
                .map(release -> {
                    List<UUID> snapshotIds = indexReleases.listSnapshotDocumentVersionIds(release.releaseId());
                    if (snapshotIds.isEmpty()) {
                        return List.<SearchHit>of();
                    }
                    Set<UUID> online = new HashSet<>(documentVersions.findOnlineVersionIds(snapshotIds));
                    if (online.isEmpty()) {
                        return List.<SearchHit>of();
                    }
                    // 单路失败降级为另一路（设计文档 §15.1）：一路失败用另一路并记录降级；
                    // 两路均失败报系统错误（由上层 run.failed 兜底，不伪装无答案）。
                    List<SearchHit> bm25 = null;
                    List<SearchHit> vector = null;
                    try {
                        bm25 = reader.bm25(release.aliasName(), kbId, question, parameters.topKPerChannel())
                                .stream().filter(h -> online.contains(h.documentVersionId())).toList();
                    } catch (RuntimeException e) {
                        degradations.add("bm25-retrieval-failed: " + e.getMessage());
                    }
                    try {
                        vector = reader.vector(release.aliasName(), kbId, question, parameters.topKPerChannel())
                                .stream().filter(h -> online.contains(h.documentVersionId())).toList();
                    } catch (RuntimeException e) {
                        degradations.add("vector-retrieval-failed: " + e.getMessage());
                    }
                    if (bm25 == null && vector == null) {
                        throw new IllegalStateException("dual retrieval failed for knowledge base " + kbId);
                    }
                    List<SearchHit> merged = new ArrayList<>();
                    if (bm25 != null) {
                        merged.addAll(bm25);
                    }
                    if (vector != null) {
                        merged.addAll(vector);
                    }
                    return merged;
                })
                .orElse(List.of());
    }
}
