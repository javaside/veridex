package io.veridex.support;

import io.veridex.VeridexApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(classes = VeridexApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(PostgresContainerConfiguration.class)
public abstract class PostgresIntegrationTest {
}
