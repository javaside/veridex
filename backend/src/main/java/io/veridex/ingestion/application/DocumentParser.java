package io.veridex.ingestion.application;

import io.veridex.ingestion.domain.ParsedDocument;
import java.io.InputStream;

public interface DocumentParser {
    ParsedDocument parse(InputStream content, String filename, String contentType);
}
