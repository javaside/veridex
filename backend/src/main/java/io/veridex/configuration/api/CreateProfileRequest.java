package io.veridex.configuration.api;

import io.veridex.configuration.domain.ProfileConfig;

public record CreateProfileRequest(String name, String description, ProfileConfig draft) {
}
