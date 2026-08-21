package io.veridex.ingestion.application;

import io.veridex.ingestion.domain.Chunk;
import io.veridex.ingestion.domain.ParsedDocument;
import java.util.List;

public interface StructureChunker {
    List<Chunk> chunk(ParsedDocument document, int maxChars, int overlap);
}
