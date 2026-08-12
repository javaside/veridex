-- 知识库全量快照发布：快照清单表 + index_release 语义列
CREATE TABLE index_release_document (
    release_id UUID NOT NULL REFERENCES index_release(id) ON DELETE CASCADE,
    document_version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    PRIMARY KEY (release_id, document_version_id)
);

ALTER TABLE index_release
    ADD COLUMN document_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN chunk_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT false;

-- 回填：既有 release 各绑定单个文档版本（如实记录，不虚构）
INSERT INTO index_release_document (release_id, document_version_id)
    SELECT id, document_version_id FROM index_release WHERE document_version_id IS NOT NULL;

UPDATE index_release r
    SET document_count = 1,
        chunk_count = COALESCE((SELECT v.chunk_count FROM document_version v WHERE v.id = r.document_version_id), 0);

-- 每个知识库 version_no 最大的 PUBLISHED release 即当前 alias 指向（旧实现 publish 总是切到最新）
UPDATE index_release r
    SET is_active = true
    WHERE r.status = 'PUBLISHED'
      AND r.version_no = (SELECT MAX(r2.version_no) FROM index_release r2
                          WHERE r2.knowledge_base_id = r.knowledge_base_id
                            AND r2.status = 'PUBLISHED');

ALTER TABLE index_release DROP COLUMN document_version_id;
