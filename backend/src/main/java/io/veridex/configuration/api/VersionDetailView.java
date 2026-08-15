package io.veridex.configuration.api;

import java.util.UUID;

public record VersionDetailView(UUID id, int versionNo, String createdAt, ProfileConfig config) {
}
