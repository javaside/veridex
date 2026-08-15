package io.veridex.configuration.api;

import java.util.UUID;

public record ProfileDetailView(UUID id, String name, String description, ProfileConfig draft) {
}
