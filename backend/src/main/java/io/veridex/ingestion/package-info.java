@org.springframework.modulith.ApplicationModule(
        displayName = "Ingestion",
        allowedDependencies = {"shared::messaging", "shared::observability", "knowledge::api", "indexing::api", "audit::api", "configuration::api"}
)
package io.veridex.ingestion;
