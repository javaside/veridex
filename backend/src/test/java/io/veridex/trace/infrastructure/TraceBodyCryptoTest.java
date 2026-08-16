package io.veridex.trace.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TraceBodyCryptoTest {

    private static final UUID RUN_ID = UUID.randomUUID();

    @Test
    void encryptsAndDecryptsWithCurrentAndHistoricalKeys() {
        TraceBodyProperties properties = validProperties();
        properties.setHistoricalKeys("current=" + properties.getCurrentKey() + ",old=" + Base64.getEncoder().encodeToString(new byte[32]));
        TraceBodyKeyRing ring = new TraceBodyKeyRing(properties);
        TraceBodyCrypto crypto = new TraceBodyCrypto(ring);
        byte[] plaintext = "trace body".getBytes(StandardCharsets.UTF_8);

        TraceBodyCrypto.EncryptedPayload encrypted = crypto.encrypt(RUN_ID, (short) 1, plaintext);
        assertThat(encrypted.keyId()).isEqualTo("current");
        assertThat(encrypted.nonce()).hasSize(12);
        assertThat(encrypted.ciphertext()).isNotEqualTo(plaintext);
        assertThat(crypto.decrypt(RUN_ID, (short) 1, encrypted.keyId(), encrypted.nonce(), encrypted.ciphertext()))
                .isEqualTo(plaintext);

        TraceBodyCrypto.EncryptedPayload historical = encryptWithKey(crypto, ring, "old", plaintext);
        assertThat(crypto.decrypt(RUN_ID, (short) 1, "old", historical.nonce(), historical.ciphertext()))
                .isEqualTo(plaintext);
    }

    @Test
    void usesUniqueNoncesAndRejectsWrongAadOrTampering() {
        TraceBodyCrypto crypto = new TraceBodyCrypto(new TraceBodyKeyRing(validProperties()));
        byte[] plaintext = "same body".getBytes(StandardCharsets.UTF_8);
        var first = crypto.encrypt(RUN_ID, (short) 1, plaintext);
        var second = crypto.encrypt(RUN_ID, (short) 1, plaintext);
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThatThrownBy(() -> crypto.decrypt(UUID.randomUUID(), (short) 1, first.keyId(), first.nonce(), first.ciphertext()))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] tampered = first.ciphertext().clone();
        tampered[0] ^= 1;
        assertThatThrownBy(() -> crypto.decrypt(RUN_ID, (short) 1, first.keyId(), first.nonce(), tampered))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fingerprintsAreHmacSha256HexAndAbsentWithoutKey() {
        TraceBodyProperties properties = validProperties();
        TraceBodyCrypto crypto = new TraceBodyCrypto(new TraceBodyKeyRing(properties));
        assertThat(crypto.fingerprint("question")).hasValueSatisfying(value -> {
            assertThat(value).hasSize(64);
            assertThat(value).matches("[0-9a-f]{64}");
        });

        TraceBodyProperties none = new TraceBodyProperties();
        assertThat(new TraceBodyCrypto(new TraceBodyKeyRing(none)).fingerprint("question")).isEmpty();
    }

    private static TraceBodyProperties validProperties() {
        TraceBodyProperties properties = new TraceBodyProperties();
        properties.setCapturePolicy("ALL");
        properties.setCurrentKeyId("current");
        properties.setCurrentKey(Base64.getEncoder().encodeToString(new byte[32]));
        properties.setHistoricalKeys("current=" + properties.getCurrentKey());
        return properties;
    }

    private static TraceBodyCrypto.EncryptedPayload encryptWithKey(
            TraceBodyCrypto crypto, TraceBodyKeyRing ring, String keyId, byte[] plaintext) {
        return crypto.encryptWithKeyForTest(RUN_ID, (short) 1, keyId, plaintext);
    }
}
