package io.veridex.configuration.api;

public record RetrievalConfig(int topKPerChannel, int rrfK, int contextTopK,
                              int perDocumentMax, int contextMaxChars) {
}
