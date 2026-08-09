package io.veridex.support;

import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import io.veridex.VeridexApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(classes = VeridexApplication.class, webEnvironment = RANDOM_PORT)
@Import(PostgresContainerConfiguration.class)
public abstract class PostgresIntegrationTest {
}
