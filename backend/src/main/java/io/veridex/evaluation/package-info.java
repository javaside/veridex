@org.springframework.modulith.ApplicationModule(
        displayName = "Evaluation",
        allowedDependencies = {"shared", "iam::api", "retrieval::api", "generation::api", "configuration::api"}
)
package io.veridex.evaluation;
