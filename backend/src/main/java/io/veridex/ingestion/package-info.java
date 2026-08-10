@org.springframework.modulith.ApplicationModule(
        displayName = "Ingestion",
        allowedDependencies = {"shared::messaging", "knowledge::api", "indexing::api", "audit::api"}
)
package io.veridex.ingestion;
