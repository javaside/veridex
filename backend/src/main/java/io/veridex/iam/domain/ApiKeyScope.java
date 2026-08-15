package io.veridex.iam.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * API key 作用域：按 API 命名空间映射。未列出的路径（key 管理、api-docs、actuator）
 * 对所有 scope 均不允许 —— key 只能访问业务命名空间。
 */
public enum ApiKeyScope {
    QA(List.of("/api/qa")) {
        @Override public boolean allows(String method, String path) { return matches(path); }
    },
    KNOWLEDGE_READ(List.of("/api/knowledge-bases", "/api/documents")) {
        @Override public boolean allows(String method, String path) {
            return "GET".equalsIgnoreCase(method) && matches(path);
        }
    },
    KNOWLEDGE_WRITE(List.of("/api/knowledge-bases", "/api/documents")) {
        @Override public boolean allows(String method, String path) {
            return !"GET".equalsIgnoreCase(method) && !"OPTIONS".equalsIgnoreCase(method) && matches(path);
        }
    },
    CONFIGURATION(List.of("/api/configuration")) {
        @Override public boolean allows(String method, String path) { return matches(path); }
    },
    EVALUATION(List.of("/api/evaluation")) {
        @Override public boolean allows(String method, String path) { return matches(path); }
    },
    FEEDBACK(List.of("/api/feedback")) {
        @Override public boolean allows(String method, String path) { return matches(path); }
    };

    private final List<String> prefixes;

    ApiKeyScope(List<String> prefixes) {
        this.prefixes = prefixes;
    }

    protected boolean matches(String path) {
        return prefixes.stream().anyMatch(p -> path.equals(p) || path.startsWith(p + "/") || path.startsWith(p + "?"));
    }

    public abstract boolean allows(String method, String path);

    /** scopes 逗号串（DB 存储）→ 枚举集合；未知值抛 IllegalArgumentException。 */
    public static Set<ApiKeyScope> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            throw new IllegalArgumentException("scopes must not be empty");
        }
        Set<ApiKeyScope> set = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            set.add(ApiKeyScope.valueOf(part.trim()));
        }
        return set;
    }
}
