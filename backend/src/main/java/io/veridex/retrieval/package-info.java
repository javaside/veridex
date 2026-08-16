@org.springframework.modulith.ApplicationModule(
        displayName = "Retrieval",
        allowedDependencies = {"shared", "shared::observability", "knowledge::api", "indexing::api"}
)
package io.veridex.retrieval;
