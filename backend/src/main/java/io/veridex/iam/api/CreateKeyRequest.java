package io.veridex.iam.api;

import java.util.List;

public record CreateKeyRequest(String name, String userId, List<String> scopes) {}
