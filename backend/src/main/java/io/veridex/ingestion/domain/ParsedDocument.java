package io.veridex.ingestion.domain;

import java.util.Map;

public record ParsedDocument(String text, String sourceFilename, String contentType, Map<String, String> metadata) {

    public ParsedDocument(String text, String sourceFilename, String contentType) {
        this(text, sourceFilename, contentType, Map.of());
    }
}
