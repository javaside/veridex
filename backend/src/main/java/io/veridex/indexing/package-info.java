@org.springframework.modulith.ApplicationModule(
        displayName = "Indexing",
        allowedDependencies = {"shared::config", "shared::observability", "knowledge::api", "iam::api"}
)
package io.veridex.indexing;
