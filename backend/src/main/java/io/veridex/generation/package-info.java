@org.springframework.modulith.ApplicationModule(
        displayName = "Generation",
        allowedDependencies = {"shared", "shared::observability", "shared::security", "retrieval::api",
        "conversation::api", "knowledge::api"}
)
package io.veridex.generation;
