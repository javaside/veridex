@org.springframework.modulith.ApplicationModule(
        displayName = "Knowledge",
        allowedDependencies = {"shared::config", "shared::outbox", "iam::api", "audit::api"}
)
package io.veridex.knowledge;
