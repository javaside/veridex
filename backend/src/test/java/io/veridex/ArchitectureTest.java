package io.veridex;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ArchitectureTest {

    private final ApplicationModules modules = ApplicationModules.of(VeridexApplication.class);

    @Test
    void modulesRespectDeclaredDependencies() {
        modules.verify();
    }
}
