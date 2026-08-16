@org.springframework.modulith.ApplicationModule(
        displayName = "Generation",
        allowedDependencies = {"shared", "shared::observability", "retrieval::api", "conversation::api", "knowledge::api"}
)
package io.veridex.generation;
