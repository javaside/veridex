@org.springframework.modulith.ApplicationModule(
        displayName = "Generation",
        allowedDependencies = {"shared", "retrieval::api", "conversation::api", "knowledge::api"}
)
package io.veridex.generation;
