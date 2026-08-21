@org.springframework.modulith.ApplicationModule(
        displayName = "QA",
        allowedDependencies = {"shared", "shared::observability", "iam::api", "knowledge::api", "retrieval::api",
                "generation::api", "conversation::api", "trace::api", "configuration::api"}
)
package io.veridex.qa;