@org.springframework.modulith.ApplicationModule(
        displayName = "Retrieval",
        allowedDependencies = {"shared", "knowledge::api", "indexing::api"}
)
package io.veridex.retrieval;
