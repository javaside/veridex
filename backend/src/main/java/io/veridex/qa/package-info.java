@org.springframework.modulith.ApplicationModule(
        displayName = "QA",
        allowedDependencies = {"shared", "shared::observability", "iam::api", "knowledge::api", "retrieval::api",
                "generation::api", "conversation::api", "trace::api"}
)
package io.veridex.qa;