package io.veridex.knowledge.api;

import io.veridex.knowledge.domain.Document;
import java.util.UUID;

public record DocumentView(UUID id, String filename, String contentType, long sizeBytes) {

    public static DocumentView from(Document d) {
        return new DocumentView(d.getId(), d.getFilename(), d.getContentType(), d.getSizeBytes());
    }
}
