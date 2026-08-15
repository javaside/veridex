package io.veridex.configuration.api;

import io.veridex.configuration.domain.ProfileConfig;
import java.util.UUID;

public record VersionDetailView(UUID id, int versionNo, String createdAt, ProfileConfig config) {
}
