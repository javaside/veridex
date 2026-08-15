package io.veridex.retrieval.api;

/**
 * 混合检索可调参数（供评测运行按 ProfileConfig 驱动检索）。
 */
public record RetrievalParameters(int topKPerChannel, int rrfK, int contextTopK,
                                  int perDocumentMax, int contextMaxChars) {
}
