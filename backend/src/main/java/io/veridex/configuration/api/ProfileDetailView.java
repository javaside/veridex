package io.veridex.configuration.api;

import io.veridex.configuration.domain.ProfileConfig;
import java.util.UUID;

public record ProfileDetailView(UUID id, String name, String description, ProfileConfig draft) {
}
