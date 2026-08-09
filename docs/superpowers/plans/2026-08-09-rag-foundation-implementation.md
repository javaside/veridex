# RAG Platform Executable Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a reproducible Spring Boot 4 / Spring AI 2.0 project foundation that starts locally, validates modular boundaries, migrates PostgreSQL, verifies infrastructure adapters, and provides a minimal React console shell.

**Architecture:** Use one Maven-built Spring Boot modular monolith under `backend/`, with domain-oriented packages and separately launchable worker applications added in later phases. Use ports/adapters around infrastructure and Testcontainers for real dependency tests; keep the browser application in `web/` and local infrastructure in `deploy/compose/`.

**Tech Stack:** Java 21, Maven Wrapper, Spring Boot 4.x, Spring AI 2.0.0 GA, Spring Modulith, Flyway, PostgreSQL, OpenSearch, RabbitMQ, Redis, MinIO, Testcontainers, React, TypeScript, Vite, Vitest, Docker Compose.

## Global Constraints

- Target one privately deployed enterprise installation, not multi-tenant SaaS.
- Design for 500–5000 users and validate with 1–5 million chunks before pilot.
- Preserve Document, DocumentVersion, and IndexRelease as separate platform-owned concepts.
- Enforce authorization in retrieval, citations, previews, downloads, caches, and trace access.
- Use Spring AI 2.0 GA as an integration framework, not as the owner of platform domain state.
- Keep Python optional and isolated to OCR, layout analysis, and multimodal parsing.
- PostgreSQL is the source of truth; Redis and OpenSearch must not own unique business facts.
- Do not enable prompt, completion, tool-content, or vector-response body logging by default.
- Use TDD, real infrastructure integration tests, and one focused commit per task.
- Do not implement domain features, generic agents, GraphRAG, workflow builders, billing, or multi-tenancy in this foundation phase.

## Planned File Structure

```text
pom.xml                                  Maven aggregator and shared version properties
.mvn/wrapper/*                           Reproducible Maven launcher
mvnw / mvnw.cmd                          Maven wrapper scripts
backend/pom.xml                          Backend dependencies and build configuration
backend/src/main/java/io/veridex/...     Spring Boot app and domain modules
backend/src/main/resources/...           Runtime configuration and Flyway migrations
backend/src/test/java/io/veridex/...     Unit, architecture, and integration tests
web/package.json                         Frontend scripts and locked dependencies
web/src/app/...                          Console shell and route definitions
web/src/features/...                     Future feature-oriented UI modules
web/src/test/...                         Frontend test setup
deploy/compose/compose.yml               Local dependency topology
deploy/compose/.env.example              Non-secret local defaults
scripts/verify.sh                        One-command backend/frontend verification
```

The initial Java package boundaries are:

```text
io.veridex.shared                        Cross-cutting primitives only
io.veridex.iam                           Identity and authorization domain
io.veridex.knowledge                     Knowledge base and document lifecycle
io.veridex.ingestion                     Asynchronous document processing
io.veridex.indexing                      Index build and release lifecycle
io.veridex.retrieval                     Authorized evidence retrieval
io.veridex.generation                    Evidence-bound model generation
io.veridex.conversation                  Conversation and message lifecycle
io.veridex.trace                         Query execution trace
io.veridex.evaluation                    Evaluation datasets and runs
io.veridex.audit                         Administrative audit events
```

Each module receives `api`, `application`, `domain`, and `infrastructure` subpackages only when needed. Do not create empty layers merely to mirror the list.

---

### Task 1: Bootable Backend Skeleton

**Files:**
- Create: `pom.xml`
- Create: `.mvn/wrapper/maven-wrapper.properties`
- Create: `mvnw`
- Create: `mvnw.cmd`
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/io/veridex/VeridexApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/io/veridex/VeridexApplicationTest.java`

**Interfaces:**
- Consumes: none.
- Produces: `io.veridex.VeridexApplication`; Maven module `:backend`; `/actuator/health`; shared Spring Boot and Spring AI dependency management.

- [ ] **Step 1: Generate Maven Wrapper 3.9.x in a temporary empty directory and copy the generated wrapper files into the repository**

Run from the project root:

```bash
mvn -N wrapper:wrapper -Dmaven=3.9.11
```

Expected: `mvnw`, `mvnw.cmd`, and `.mvn/wrapper/maven-wrapper.properties` exist and `./mvnw --version` reports Maven 3.9.11. If system Maven is unavailable, use an installed IDE Maven once to run the same wrapper goal; do not handwrite the wrapper JAR.

- [ ] **Step 2: Create the failing application context test**

```java
package io.veridex;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class VeridexApplicationTest {

    @Test
    void applicationContextStarts() {
    }
}
```

- [ ] **Step 3: Run the test and verify the backend module is absent**

Run:

```bash
./mvnw -pl backend test -Dtest=VeridexApplicationTest
```

Expected: FAIL because `backend/pom.xml` or `VeridexApplication` does not exist.

- [ ] **Step 4: Create the root Maven aggregator**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>io.veridex</groupId>
    <artifactId>veridex-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>backend</module>
    </modules>

    <properties>
        <java.version>21</java.version>
        <spring-boot.version>4.0.0</spring-boot.version>
        <spring-ai.version>2.0.0</spring-ai.version>
        <spring-modulith.version>2.0.0</spring-modulith.version>
        <maven.compiler.release>${java.version}</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.springframework.ai</groupId>
                <artifactId>spring-ai-bom</artifactId>
                <version>${spring-ai.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.springframework.modulith</groupId>
                <artifactId>spring-modulith-bom</artifactId>
                <version>${spring-modulith.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.14.1</version>
                    <configuration>
                        <release>${java.version}</release>
                        <parameters>true</parameters>
                    </configuration>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.5.4</version>
                    <configuration>
                        <useModulePath>false</useModulePath>
                    </configuration>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

Before implementation, verify these exact dependency versions exist in Maven Central and remain compatible. If Spring Boot or Spring Modulith requires a later patch version, update only the version property, record the chosen patch in the commit body, and keep Spring AI at the confirmed 2.0 GA line.

- [ ] **Step 5: Create `backend/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.veridex</groupId>
        <artifactId>veridex-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>veridex-backend</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-client-chat</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 6: Create the application and safe default configuration**

```java
package io.veridex;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class VeridexApplication {

    public static void main(String[] args) {
        SpringApplication.run(VeridexApplication.class, args);
    }
}
```

```yaml
spring:
  application:
    name: veridex
  ai:
    chat:
      client:
        observations:
          log-prompt: false
          log-completion: false
    vectorstore:
      observations:
        log-query-response: false

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      probes:
        enabled: true
```

- [ ] **Step 7: Run backend verification**

Run:

```bash
./mvnw -pl backend test -Dtest=VeridexApplicationTest
```

Expected: BUILD SUCCESS and one passing test.

- [ ] **Step 8: Commit the bootable skeleton**

```bash
git add pom.xml .mvn mvnw mvnw.cmd backend
git commit -m "build: bootstrap Spring AI platform backend"
```

---

### Task 2: Enforced Modular Boundaries

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/io/veridex/iam/package-info.java`
- Create: `backend/src/main/java/io/veridex/knowledge/package-info.java`
- Create: `backend/src/main/java/io/veridex/ingestion/package-info.java`
- Create: `backend/src/main/java/io/veridex/indexing/package-info.java`
- Create: `backend/src/main/java/io/veridex/retrieval/package-info.java`
- Create: `backend/src/main/java/io/veridex/generation/package-info.java`
- Create: `backend/src/main/java/io/veridex/conversation/package-info.java`
- Create: `backend/src/main/java/io/veridex/trace/package-info.java`
- Create: `backend/src/main/java/io/veridex/evaluation/package-info.java`
- Create: `backend/src/main/java/io/veridex/audit/package-info.java`
- Create: `backend/src/main/java/io/veridex/shared/package-info.java`
- Create: `backend/src/test/java/io/veridex/ArchitectureTest.java`

**Interfaces:**
- Consumes: `VeridexApplication`.
- Produces: named Spring Modulith modules and `ArchitectureTest#modulesRespectDeclaredDependencies()` as a permanent architecture gate.

- [ ] **Step 1: Add the failing architecture test**

```java
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
```

- [ ] **Step 2: Run the test before adding Modulith**

Run:

```bash
./mvnw -pl backend test -Dtest=ArchitectureTest
```

Expected: FAIL because Spring Modulith test classes are not on the classpath.

- [ ] **Step 3: Add Spring Modulith dependencies to `backend/pom.xml`**

```xml
<dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-starter-core</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 4: Declare each top-level module**

Use this complete pattern, changing only the display name and package declaration for every listed module:

```java
@org.springframework.modulith.ApplicationModule(
        displayName = "Knowledge",
        allowedDependencies = {"shared"}
)
package io.veridex.knowledge;
```

Declare dependency rules as follows:

```text
shared: no dependencies
iam: shared
knowledge: shared, iam
 ingestion: shared, knowledge
indexing: shared, knowledge
audit: shared, iam
retrieval: shared, iam, knowledge, indexing
generation: shared, retrieval
conversation: shared, iam, generation
trace: shared, iam, retrieval, generation
evaluation: shared, knowledge, retrieval, generation, trace
audit: shared, iam
```

Correct the accidental leading space in the textual `ingestion` line when creating the Java annotation. Every module annotation must contain exact package names without whitespace.

- [ ] **Step 5: Run the architecture gate**

Run:

```bash
./mvnw -pl backend test -Dtest=ArchitectureTest
```

Expected: BUILD SUCCESS. The discovered module list contains all ten domain modules plus `shared`.

- [ ] **Step 6: Run all backend tests and commit**

```bash
./mvnw -pl backend test
git add backend/pom.xml backend/src/main/java backend/src/test/java/io/veridex/ArchitectureTest.java
git commit -m "arch: enforce platform module boundaries"
```

Expected: all backend tests pass before commit.

---

### Task 3: PostgreSQL Source-of-Truth Baseline

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/db/migration/V1__platform_baseline.sql`
- Create: `backend/src/test/java/io/veridex/support/PostgresIntegrationTest.java`
- Create: `backend/src/test/java/io/veridex/support/PostgresContainerConfiguration.java`
- Create: `backend/src/test/java/io/veridex/support/DatabaseMigrationTest.java`

**Interfaces:**
- Consumes: bootable backend.
- Produces: Flyway-managed PostgreSQL schema; reusable `PostgresIntegrationTest`; baseline `installation`, `outbox_event`, and `audit_event` tables.

- [ ] **Step 1: Write a failing migration integration test**

```java
package io.veridex.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DatabaseMigrationTest extends PostgresIntegrationTest {

    @Autowired
    DataSource dataSource;

    @Test
    void flywayCreatesPlatformBaselineTables() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            var tables = connection.getMetaData().getTables(null, "public", "%", new String[]{"TABLE"});
            var names = new java.util.HashSet<String>();
            while (tables.next()) {
                names.add(tables.getString("TABLE_NAME"));
            }
            assertThat(names).contains("installation", "outbox_event", "audit_event");
        }
    }
}
```

- [ ] **Step 2: Add reusable PostgreSQL Testcontainer classes**

```java
package io.veridex.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("veridex")
                .withUsername("veridex")
                .withPassword("veridex");
    }
}
```

```java
package io.veridex.support;

import io.veridex.VeridexApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(classes = VeridexApplication.class)
@Import(PostgresContainerConfiguration.class)
public abstract class PostgresIntegrationTest {
}
```

- [ ] **Step 3: Add database test dependencies and verify failure**

Add to `backend/pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
```

Run:

```bash
./mvnw -pl backend test -Dtest=DatabaseMigrationTest
```

Expected: FAIL because the three tables do not exist.

- [ ] **Step 4: Create the baseline migration**

```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE installation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    installation_key VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(200) NOT NULL,
    payload JSONB NOT NULL,
    schema_version INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT
);
CREATE INDEX idx_outbox_unpublished ON outbox_event (occurred_at) WHERE published_at IS NULL;

CREATE TABLE audit_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID,
    action VARCHAR(200) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    resource_id UUID,
    request_id VARCHAR(100),
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_occurred_at ON audit_event (occurred_at DESC);
```

- [ ] **Step 5: Configure runtime datasource via environment variables**

Merge into `application.yml`:

```yaml
spring:
  datasource:
    url: ${VERIDEX_DB_URL:jdbc:postgresql://localhost:5432/veridex}
    username: ${VERIDEX_DB_USERNAME:veridex}
    password: ${VERIDEX_DB_PASSWORD:veridex}
  flyway:
    enabled: true
    validate-on-migrate: true
```

- [ ] **Step 6: Verify migration and commit**

```bash
./mvnw -pl backend test -Dtest=DatabaseMigrationTest
./mvnw -pl backend test
git add backend
git commit -m "feat: add PostgreSQL migration baseline"
```

Expected: both commands succeed; Flyway reports migration version `1`.

---

### Task 4: Real Infrastructure Contract Harness

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/test/java/io/veridex/support/InfrastructureContainers.java`
- Create: `backend/src/test/java/io/veridex/support/InfrastructureSmokeTest.java`

**Interfaces:**
- Consumes: Spring application and PostgreSQL harness.
- Produces: one repeatable test proving PostgreSQL, RabbitMQ, Redis, MinIO, and OpenSearch are reachable with the versions intended for local development.

- [ ] **Step 1: Write the failing smoke test contract**

```java
package io.veridex.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class InfrastructureSmokeTest extends InfrastructureContainers {

    @Test
    void allRequiredDependenciesBecomeReachable() throws Exception {
        assertThat(POSTGRES.isRunning()).isTrue();
        assertThat(RABBITMQ.isRunning()).isTrue();
        assertThat(REDIS.isRunning()).isTrue();
        assertThat(MINIO.isRunning()).isTrue();
        assertThat(OPENSEARCH.isRunning()).isTrue();

        var response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(OPENSEARCH.getHttpHost() + "/_cluster/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("status");
    }
}
```

- [ ] **Step 2: Run the test and verify the container harness is missing**

```bash
./mvnw -pl backend test -Dtest=InfrastructureSmokeTest
```

Expected: FAIL to compile because `InfrastructureContainers` does not exist.

- [ ] **Step 3: Add Testcontainers modules**

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>rabbitmq</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>opensearch</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 4: Implement the shared container harness**

```java
package io.veridex.support;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.opensearch.OpenSearchContainer;
import org.testcontainers.utility.DockerImageName;

abstract class InfrastructureContainers {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:4-management-alpine");
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);
    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2025-07-23T15-54-02Z"))
            .withCommand("server", "/data", "--console-address", ":9001")
            .withEnv("MINIO_ROOT_USER", "veridex")
            .withEnv("MINIO_ROOT_PASSWORD", "veridex-local-secret")
            .withExposedPorts(9000, 9001);
    static final OpenSearchContainer<?> OPENSEARCH = new OpenSearchContainer<>(
            "opensearchproject/opensearch:3.2.0")
            .withEnv("DISABLE_SECURITY_PLUGIN", "true");

    @BeforeAll
    static void startInfrastructure() {
        POSTGRES.start();
        RABBITMQ.start();
        REDIS.start();
        MINIO.start();
        OPENSEARCH.start();
    }

    @AfterAll
    static void stopInfrastructure() {
        OPENSEARCH.stop();
        MINIO.stop();
        REDIS.stop();
        RABBITMQ.stop();
        POSTGRES.stop();
    }
}
```

Before implementation, verify each pinned image exists in its official registry. If an image has been withdrawn, select the latest available patch within the same declared major line and record the exact digest or tag in the commit body.

- [ ] **Step 5: Run the infrastructure smoke test**

```bash
./mvnw -pl backend test -Dtest=InfrastructureSmokeTest
```

Expected: BUILD SUCCESS; all five containers start and OpenSearch health returns HTTP 200.

- [ ] **Step 6: Commit the contract harness**

```bash
git add backend/pom.xml backend/src/test/java/io/veridex/support
git commit -m "test: add infrastructure contract harness"
```

---

### Task 5: Local Docker Compose Topology

**Files:**
- Create: `deploy/compose/compose.yml`
- Create: `deploy/compose/.env.example`
- Create: `backend/src/test/java/io/veridex/ConfigurationSafetyTest.java`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- Consumes: infrastructure versions validated by Task 4.
- Produces: deterministic local dependencies on standard ports and a safety test preventing AI content logging defaults from changing.

- [ ] **Step 1: Write the configuration safety test**

```java
package io.veridex;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
class ConfigurationSafetyTest {

    @Autowired
    Environment environment;

    @Test
    void sensitiveAiObservationContentIsDisabledByDefault() {
        assertThat(environment.getProperty("spring.ai.chat.client.observations.log-prompt", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.ai.chat.client.observations.log-completion", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.ai.vectorstore.observations.log-query-response", Boolean.class)).isFalse();
    }
}
```

- [ ] **Step 2: Run the safety test**

```bash
./mvnw -pl backend test -Dtest=ConfigurationSafetyTest
```

Expected: PASS with the safe defaults from Task 1. If Boot 4 uses different auto-configuration class names, inspect the startup failure report, replace the excluded classes with the exact Boot 4 names, and retain the three assertions unchanged.

- [ ] **Step 3: Create `.env.example`**

```dotenv
POSTGRES_DB=veridex
POSTGRES_USER=veridex
POSTGRES_PASSWORD=veridex-local
MINIO_ROOT_USER=veridex
MINIO_ROOT_PASSWORD=veridex-local-secret
RABBITMQ_DEFAULT_USER=veridex
RABBITMQ_DEFAULT_PASS=veridex-local
```

- [ ] **Step 4: Create Compose topology with health checks**

```yaml
name: veridex
services:
  postgres:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-veridex}
      POSTGRES_USER: ${POSTGRES_USER:-veridex}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-veridex-local}
    ports: ["5432:5432"]
    volumes: ["postgres-data:/var/lib/postgresql/data"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}"]
      interval: 5s
      timeout: 3s
      retries: 20

  rabbitmq:
    image: rabbitmq:4-management-alpine
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_DEFAULT_USER:-veridex}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_DEFAULT_PASS:-veridex-local}
    ports: ["5672:5672", "15672:15672"]
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 5s
      timeout: 5s
      retries: 20

  redis:
    image: redis:8-alpine
    ports: ["6379:6379"]
    command: ["redis-server", "--appendonly", "no"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 20

  minio:
    image: minio/minio:RELEASE.2025-07-23T15-54-02Z
    command: ["server", "/data", "--console-address", ":9001"]
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER:-veridex}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:-veridex-local-secret}
    ports: ["9000:9000", "9001:9001"]
    volumes: ["minio-data:/data"]
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 5s
      timeout: 3s
      retries: 20

  opensearch:
    image: opensearchproject/opensearch:3.2.0
    environment:
      discovery.type: single-node
      DISABLE_SECURITY_PLUGIN: "true"
      OPENSEARCH_JAVA_OPTS: -Xms1g -Xmx1g
    ports: ["9200:9200"]
    volumes: ["opensearch-data:/usr/share/opensearch/data"]
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:9200/_cluster/health >/dev/null"]
      interval: 10s
      timeout: 5s
      retries: 30

volumes:
  postgres-data:
  minio-data:
  opensearch-data:
```

- [ ] **Step 5: Start and inspect dependencies**

```bash
cp deploy/compose/.env.example deploy/compose/.env
docker compose -f deploy/compose/compose.yml --env-file deploy/compose/.env up -d
docker compose -f deploy/compose/compose.yml ps
```

Expected: all five services reach `healthy`. Remove `deploy/compose/.env` after validation; it is ignored by Git.

- [ ] **Step 6: Start the backend against Compose and check health**

```bash
VERIDEX_DB_PASSWORD=veridex-local ./mvnw -pl backend spring-boot:run
```

In another terminal:

```bash
curl -fsS http://localhost:8080/actuator/health
```

Expected: response contains `"status":"UP"`. Stop the backend and Compose after verification.

- [ ] **Step 7: Commit local deployment files**

```bash
git add deploy backend/src/main/resources/application.yml backend/src/test/java/io/veridex/ConfigurationSafetyTest.java
git commit -m "build: add local infrastructure topology"
```

---

### Task 6: React Console Shell

**Files:**
- Create: `web/package.json`
- Create: `web/package-lock.json`
- Create: `web/index.html`
- Create: `web/tsconfig.json`
- Create: `web/vite.config.ts`
- Create: `web/src/main.tsx`
- Create: `web/src/app/App.tsx`
- Create: `web/src/app/routes.tsx`
- Create: `web/src/app/App.test.tsx`
- Create: `web/src/test/setup.ts`
- Create: `web/src/styles.css`

**Interfaces:**
- Consumes: backend health endpoint contract.
- Produces: routes `/workbench`, `/knowledge`, `/evaluation`, `/admin`; frontend `test`, `build`, and `dev` scripts.

- [ ] **Step 1: Scaffold Vite React TypeScript with npm**

Run:

```bash
npm create vite@latest web -- --template react-ts
cd web && npm install && npm install react-router-dom && npm install -D vitest jsdom @testing-library/react @testing-library/jest-dom
```

Expected: `web/package-lock.json` is created. Retain the generated dependency versions in the lockfile rather than manually widening version ranges.

- [ ] **Step 2: Add test scripts to `web/package.json`**

The scripts object must contain:

```json
{
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "test": "vitest run",
    "test:watch": "vitest",
    "lint": "eslint ."
  }
}
```

- [ ] **Step 3: Write the failing shell test**

```tsx
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import '@testing-library/jest-dom';
import { App } from './App';

test('renders the four platform workspaces', () => {
  render(<MemoryRouter initialEntries={['/workbench']}><App /></MemoryRouter>);

  expect(screen.getByRole('link', { name: '员工问答' })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '知识管理' })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '质量评测' })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '平台管理' })).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: '员工问答' })).toBeInTheDocument();
});
```

- [ ] **Step 4: Configure Vitest and verify failure**

Add to `vite.config.ts`:

```ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts'
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080'
    }
  }
});
```

Create `web/src/test/setup.ts`:

```ts
import '@testing-library/jest-dom/vitest';
```

Run:

```bash
cd web && npm test
```

Expected: FAIL because `App` does not expose the four routes and links.

- [ ] **Step 5: Implement focused route definitions**

```tsx
import type { ReactNode } from 'react';

export type WorkspaceRoute = {
  path: string;
  label: string;
  content: ReactNode;
};

export const workspaceRoutes: WorkspaceRoute[] = [
  { path: '/workbench', label: '员工问答', content: <h1>员工问答</h1> },
  { path: '/knowledge', label: '知识管理', content: <h1>知识管理</h1> },
  { path: '/evaluation', label: '质量评测', content: <h1>质量评测</h1> },
  { path: '/admin', label: '平台管理', content: <h1>平台管理</h1> }
];
```

```tsx
import { Navigate, NavLink, Route, Routes } from 'react-router-dom';
import { workspaceRoutes } from './routes';

export function App() {
  return (
    <div className="app-shell">
      <aside>
        <strong>Veridex</strong>
        <nav aria-label="平台工作区">
          {workspaceRoutes.map(route => (
            <NavLink key={route.path} to={route.path}>{route.label}</NavLink>
          ))}
        </nav>
      </aside>
      <main>
        <Routes>
          {workspaceRoutes.map(route => (
            <Route key={route.path} path={route.path} element={route.content} />
          ))}
          <Route path="*" element={<Navigate to="/workbench" replace />} />
        </Routes>
      </main>
    </div>
  );
}
```

```tsx
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { App } from './app/App';
import './styles.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode><BrowserRouter><App /></BrowserRouter></StrictMode>
);
```

Use this minimal CSS rather than a UI framework in the foundation task:

```css
:root { font-family: Inter, "PingFang SC", sans-serif; color: #142033; background: #f4f7fb; }
body { margin: 0; }
.app-shell { display: grid; grid-template-columns: 220px 1fr; min-height: 100vh; }
aside { padding: 24px; color: white; background: #10233a; }
nav { display: grid; gap: 8px; margin-top: 24px; }
nav a { padding: 10px 12px; color: #b9c8d8; text-decoration: none; border-radius: 8px; }
nav a.active { color: #06251f; background: #43d9c3; }
main { padding: 32px; }
```

- [ ] **Step 6: Verify tests and production build**

```bash
cd web
npm test
npm run build
```

Expected: tests pass and Vite creates `web/dist/` without TypeScript errors.

- [ ] **Step 7: Commit the console shell**

```bash
git add web
git commit -m "feat: add enterprise console shell"
```

---

### Task 7: One-Command Verification and Foundation Gate

**Files:**
- Create: `scripts/verify.sh`
- Modify: `.gitignore`
- Test: all backend and frontend tests.

**Interfaces:**
- Consumes: Maven backend and npm frontend scripts.
- Produces: `scripts/verify.sh` as the local and CI-equivalent foundation gate.

- [ ] **Step 1: Write the verification script**

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

"${ROOT_DIR}/mvnw" -f "${ROOT_DIR}/pom.xml" clean verify
npm --prefix "${ROOT_DIR}/web" test
npm --prefix "${ROOT_DIR}/web" run build

git -C "${ROOT_DIR}" diff --check
```

Run:

```bash
chmod +x scripts/verify.sh
```

- [ ] **Step 2: Ensure generated and local files are ignored**

The root `.gitignore` must include:

```gitignore
.codetui/
.idea/
*.iml
.DS_Store
**/target/
**/build/
node_modules/
dist/
.env
.env.*
!.env.example
application-local.yml
application-local.yaml
.data/
logs/
```

- [ ] **Step 3: Run the complete verification gate**

```bash
./scripts/verify.sh
```

Expected:

- Maven `clean verify` succeeds;
- application, architecture, migration, infrastructure, and safety tests pass;
- frontend Vitest suite passes;
- frontend production build succeeds;
- `git diff --check` emits no output.

- [ ] **Step 4: Inspect repository state**

```bash
git status --short
git log --oneline --decorate -7
```

Expected: only `scripts/verify.sh` and the planned `.gitignore` adjustment are uncommitted; prior tasks appear as focused commits.

- [ ] **Step 5: Commit the foundation gate**

```bash
git add .gitignore scripts/verify.sh
git commit -m "ci: add executable foundation verification"
```

- [ ] **Step 6: Run final clean-tree verification**

```bash
./scripts/verify.sh
git status --short
```

Expected: verification succeeds and Git reports no tracked changes. The untracked `.codetui/` directory must not appear because it is ignored.

## Foundation Completion Criteria

This plan is complete only when all of the following are evidenced by command output:

- `./mvnw clean verify` succeeds on Java 21;
- Spring Boot starts and `/actuator/health` reports UP;
- Spring Modulith verifies declared module boundaries;
- Flyway applies the PostgreSQL baseline against a real PostgreSQL container;
- PostgreSQL, RabbitMQ, Redis, MinIO, and OpenSearch contract tests pass;
- Compose dependencies become healthy;
- sensitive Spring AI observation body logging remains disabled by test;
- React route tests and production build pass;
- `./scripts/verify.sh` succeeds;
- the repository has a clean tracked working tree.

After this gate passes, write the detailed Phase 2 knowledge-ingestion plan against the files and dependency versions that now exist; do not plan Phase 2 against hypothetical package or framework APIs.
