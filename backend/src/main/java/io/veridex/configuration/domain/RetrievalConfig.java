package io.veridex.configuration.domain;

public record RetrievalConfig(int topKPerChannel, int rrfK, int contextTopK,
                              int perDocumentMax, int contextMaxChars) {
}
