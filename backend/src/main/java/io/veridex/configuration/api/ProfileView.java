package io.veridex.configuration.api;

import java.util.UUID;

public record ProfileView(UUID id, String name, String description, int versionCount, Integer latestVersionNo) {
}
