@org.springframework.modulith.ApplicationModule(
        displayName = "Trace",
        allowedDependencies = {"shared", "shared::infrastructure", "shared::observability", "iam::api", "audit::api",
                "retrieval", "generation"}
)
package io.veridex.trace;
