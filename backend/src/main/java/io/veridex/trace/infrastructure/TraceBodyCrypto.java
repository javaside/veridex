package io.veridex.trace.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class TraceBodyCrypto {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final TraceBodyKeyRing keyRing;
    private final byte[] fingerprintKey;

    public TraceBodyCrypto(TraceBodyKeyRing keyRing) {
        this(keyRing, null);
    }

    public TraceBodyCrypto(TraceBodyKeyRing keyRing, byte[] fingerprintKey) {
        this.keyRing = keyRing;
        this.fingerprintKey = fingerprintKey == null ? keyRing.fingerprintKey() : fingerprintKey.clone();
    }

    public EncryptedPayload encrypt(UUID runId, short schemaVersion, byte[] plaintext) {
        return encryptWithKey(runId, schemaVersion, keyRing.currentKeyId(), plaintext);
    }

    public byte[] decrypt(UUID runId, short schemaVersion, String keyId, byte[] nonce, byte[] ciphertext) {
        if (nonce == null || nonce.length != NONCE_BYTES) throw new IllegalArgumentException("invalid nonce");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyRing.key(keyId), "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(runId, schemaVersion, keyId));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new IllegalArgumentException("trace body decryption failed", exception);
        }
    }

    public Optional<String> fingerprint(String value) {
        byte[] key = fingerprintKey != null ? fingerprintKey : (keyRing.isEmpty() ? null : keyRing.currentKey());
        if (key == null) return Optional.empty();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Optional.of(HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("fingerprint unavailable", exception);
        }
    }

    EncryptedPayload encryptWithKeyForTest(UUID runId, short schemaVersion, String keyId, byte[] plaintext) {
        return encryptWithKey(runId, schemaVersion, keyId, plaintext);
    }

    private EncryptedPayload encryptWithKey(UUID runId, short schemaVersion, String keyId, byte[] plaintext) {
        if (keyId == null || keyId.isBlank()) throw new IllegalStateException("trace body encryption key is unavailable");
        byte[] nonce = new byte[NONCE_BYTES];
        new java.security.SecureRandom().nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyRing.key(keyId), "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(runId, schemaVersion, keyId));
            return new EncryptedPayload(keyId, nonce, cipher.doFinal(plaintext));
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new IllegalArgumentException("trace body encryption failed", exception);
        }
    }

    private static byte[] aad(UUID runId, short schemaVersion, String keyId) {
        return ("veridex-trace-body:" + runId + ":" + schemaVersion + ":" + keyId).getBytes(StandardCharsets.UTF_8);
    }

    public record EncryptedPayload(String keyId, byte[] nonce, byte[] ciphertext) {
        public EncryptedPayload {
            nonce = nonce.clone();
            ciphertext = ciphertext.clone();
        }
    }
}
