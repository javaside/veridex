package io.veridex.configuration.api;

public record UpdateProfileRequest(String name, String description, ProfileConfig draft) {
}
