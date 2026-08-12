package io.veridex.knowledge.application;

import io.veridex.knowledge.api.DocumentVersionQuery;
import io.veridex.knowledge.domain.DocumentVersion;
import io.veridex.knowledge.domain.DocumentVersionRepository;
import io.veridex.knowledge.domain.DocumentVersionStatus;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DocumentVersionQueryImpl implements DocumentVersionQuery {

    private final DocumentVersionRepository documentVersions;

    public DocumentVersionQueryImpl(DocumentVersionRepository documentVersions) {
        this.documentVersions = documentVersions;
    }

    @Override
    public List<UUID> findOnlineVersionIds(Collection<UUID> versionIds) {
        java.util.ArrayList<DocumentVersion> all = new java.util.ArrayList<>();
        documentVersions.findAllById(versionIds).forEach(all::add);
        return all.stream()
                .filter(v -> v.getStatus() != DocumentVersionStatus.OFFLINE)
                .map(DocumentVersion::getId)
                .toList();
    }

    @Override
    public Map<UUID, UUID> findDocumentIdByVersionIds(Collection<UUID> versionIds) {
        java.util.LinkedHashMap<UUID, UUID> out = new java.util.LinkedHashMap<>();
        documentVersions.findAllById(versionIds).forEach(v -> out.put(v.getId(), v.getDocumentId()));
        return out;
    }
}
