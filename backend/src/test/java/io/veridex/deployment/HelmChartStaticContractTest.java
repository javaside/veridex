package io.veridex.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase 5-d Helm chart 文件级静态契约（不依赖 helm 二进制）。 */
class HelmChartStaticContractTest {

    private static final Path CHART = Path.of("..", "deploy", "helm", "veridex");

    private static String read(String relative) throws IOException {
        return Files.readString(CHART.resolve(relative));
    }

    @Test
    void chartDeclaresRequiredStructure() throws IOException {
        for (String file : List.of("Chart.yaml", "values.yaml", "values.schema.json", "NOTES.txt",
                "templates/_helpers.tpl", "templates/backend-deployment.yaml", "templates/web-deployment.yaml",
                "templates/backend-service.yaml", "templates/backend-management-service.yaml",
                "templates/web-service.yaml", "templates/backend-configmap.yaml", "templates/serviceaccount.yaml")) {
            assertThat(CHART.resolve(file)).as("%s", file).exists();
        }
        assertThat(read("Chart.yaml")).contains("apiVersion: v2").contains("name: veridex");
    }

    @Test
    void workloadTemplatesEnforceSecurityContract() throws IOException {
        String backend = read("templates/backend-deployment.yaml");
        String web = read("templates/web-deployment.yaml");
        for (String template : List.of(backend, web)) {
            // 安全上下文必须引用 values（受 values.schema.json const/pattern 约束），
            // 而非模板内写死或可被 values 关闭
            assertThat(template).containsPattern("runAsNonRoot: \\{\\{");
            assertThat(template).containsPattern("readOnlyRootFilesystem: \\{\\{");
            assertThat(template).contains("allowPrivilegeEscalation: {{ .Values.containerSecurityContext.allowPrivilegeEscalation }}");
            assertThat(template).contains("automountServiceAccountToken: false");
            assertThat(template).contains("seccompProfile:");
            assertThat(template).containsPattern("type: \\{\\{ \\.Values\\.[a-zA-Z]+SecurityContext\\.seccompProfile\\.type \\}\\}");
            assertThat(template).containsPattern("capabilities:\\s*\\n\\s*drop:");
            assertThat(template).doesNotContain("hostPath");
            assertThat(template).doesNotContain("privileged: true");
            // 镜像必须走 helper，禁止裸字符串引用
            assertThat(template).contains("image: {{ include \"veridex.image\"");
        }
        // backend：probes 走管理端口 health groups；parser emptyDir 限额；secret 逐 key 注入；配置 checksum 滚动
        assertThat(backend).contains("/actuator/health/liveness");
        assertThat(backend).contains("/actuator/health/readiness");
        assertThat(backend).contains("port: management");
        assertThat(backend).contains("sizeLimit:");
        assertThat(backend).contains("secretKeyRef");
        // ConfigMap checksum 注入 Pod annotation：Deployment 引 helper，helper 输出 checksum/config 键
        assertThat(backend).contains("veridex.checksum/backendConfig");
        assertThat(read("templates/_helpers.tpl")).contains("checksum/config:");
        // backend 不得引用 web 侧上游变量（Phase 5-d 边界契约）
        assertThat(backend).doesNotContain("VERIDEX_BACKEND_UPSTREAM");
        // web：本地静态健康路径、代理上游为 backend Service
        assertThat(web).contains("/healthz");
        assertThat(web).contains("{{ include \"veridex.fullname\" . }}-backend:8080");
        // 管理端口独立 Service 且 web Service 不引用它
        String management = read("templates/backend-management-service.yaml");
        assertThat(management).contains("name: management").contains("port: 8081");
        String webSvc = read("templates/web-service.yaml");
        assertThat(webSvc).doesNotContain("8081");
    }

    @Test
    void valuesAndSchemaRejectInsecureDefaults() throws IOException {
        String values = read("values.yaml");
        assertThat(values).doesNotContain("tag: latest");
        assertThat(values).contains("existingSecret: veridex-secret");
        assertThat(values).contains("readOnlyRootFilesystem: true");
        // 安全默认值字面量固定在 values（模板只能引用，schema 用 const 锁死不可关闭）
        assertThat(values).contains("runAsNonRoot: true");
        assertThat(values).contains("drop: [\"ALL\"]");
        assertThat(values).contains("seccompProfile: { type: RuntimeDefault }");
        String schema = read("values.schema.json");
        assertThat(schema).contains("\"pullPolicy\"");
        assertThat(schema).contains("IfNotPresent");
        assertThat(schema).contains("\"digest\"");
        assertThat(schema).contains("sha256:");
    }
}
