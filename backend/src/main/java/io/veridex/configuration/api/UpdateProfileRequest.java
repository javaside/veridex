package io.veridex.configuration.api;

import io.veridex.configuration.domain.ProfileConfig;

public record UpdateProfileRequest(String name, String description, ProfileConfig draft) {
}
