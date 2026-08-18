package io.veridex.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 5-d exit gate：部署交付物完整且已接入统一门禁。 */
class DeploymentExitGateTest {

    private static final Path ROOT = Path.of("..");

    @Test
    void offlineDeliveryBundleIsComplete() throws IOException {
        Path offline = ROOT.resolve("deploy/offline/veridex-offline");
        for (String file : List.of("images.txt", "images.sha256", "values/registry-values.yaml",
                "scripts/export-images.sh", "scripts/import-images.sh", "scripts/push-images.sh",
                "../scripts/export-images.sh", "../scripts/import-images.sh", "../scripts/push-images.sh",
                "INSTALL.txt")) {
            assertThat(offline.resolve(file)).as("%s", file).exists();
        }
        String images = Files.readString(offline.resolve("images.txt"));
        assertThat(images).contains("veridex-backend:").contains("veridex-web:");
        assertThat(Files.readString(offline.resolve("INSTALL.txt")))
                .contains("existing Secret")
                .contains("trace-current-key");
    }

    @Test
    void clusterAcceptanceAndVerifyGateAreWired() throws IOException {
        Path script = ROOT.resolve("deploy/kind/run-acceptance.sh");
        assertThat(script).exists();
        assertThat(Files.isExecutable(script)).isTrue();
        assertThat(Files.readString(script)).contains("ENFORCE_NETWORKPOLICY");
        assertThat(Files.readString(ROOT.resolve("scripts/verify.sh"))).contains("verify-deployment.sh");
    }

    @Test
    void operatorDocsCoverDeployment() throws IOException {
        String readme = Files.readString(ROOT.resolve("README.md"));
        assertThat(readme).contains("deploy/helm/veridex");
        assertThat(readme).contains("verify-deployment.sh");
        assertThat(readme).contains("deploy/compose/smoke.sh");
        String architecture = Files.readString(ROOT.resolve("docs/architecture.md"));
        assertThat(architecture).contains("8081");
        assertThat(architecture).contains("existing Secret");
    }
}
