@org.springframework.modulith.ApplicationModule(
        displayName = "Trace",
        allowedDependencies = {"shared", "shared::infrastructure", "iam::api", "audit::api", "retrieval", "generation"}
)
package io.veridex.trace;
