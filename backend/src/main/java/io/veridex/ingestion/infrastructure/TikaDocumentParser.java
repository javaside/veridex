package io.veridex.ingestion.infrastructure;

import io.veridex.ingestion.application.DocumentParser;
import io.veridex.ingestion.domain.ParsedDocument;
import java.io.InputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

@Component
public class TikaDocumentParser implements DocumentParser {

    private final AutoDetectParser parser = new AutoDetectParser();

    @Override
    public ParsedDocument parse(InputStream content, String filename, String contentType) {
        try {
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            if (contentType != null) {
                metadata.set(Metadata.CONTENT_TYPE, contentType);
            }
            parser.parse(content, handler, metadata, new ParseContext());
            String pageCount = metadata.get("xmpTPg:NPages");
            return new ParsedDocument(handler.toString(), filename, contentType,
                    java.util.Map.of("pageCount", pageCount == null ? "" : pageCount));
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to parse document " + filename, e);
        }
    }
}
