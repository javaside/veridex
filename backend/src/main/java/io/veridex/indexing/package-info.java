@org.springframework.modulith.ApplicationModule(
        displayName = "Indexing",
        allowedDependencies = {"shared::config", "knowledge::api", "iam::api"}
)
package io.veridex.indexing;
