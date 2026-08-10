package io.veridex.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.knowledge.api.ObjectStorage;
import io.veridex.support.MinioContainerConfiguration;
import io.veridex.support.PostgresIntegrationTest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@Import(MinioContainerConfiguration.class)
class MinioObjectStorageTest extends PostgresIntegrationTest {

    @Autowired ObjectStorage storage;

    @Test
    void putGetAndDeleteRoundTrip() throws Exception {
        String key = "kb/doc/v1/guide.md";
        storage.put(key, new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8)),
                "text/markdown", 11L);

        assertThat(storage.exists(key)).isTrue();
        try (var in = storage.get(key)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello world");
        }
        storage.delete(key);
        assertThat(storage.exists(key)).isFalse();
    }
}
