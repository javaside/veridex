package io.veridex.configuration.api;

import java.util.UUID;

public record VersionView(UUID id, int versionNo, String createdAt) {
}
