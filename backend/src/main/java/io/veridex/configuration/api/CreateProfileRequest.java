package io.veridex.configuration.api;

public record CreateProfileRequest(String name, String description, ProfileConfig draft) {
}
