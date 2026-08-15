package io.veridex.iam.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** token 生成与哈希：明文只在签发时出现一次，库中只存 SHA-256 hex。 */
@Component
public class ApiKeyTokenGenerator {

    public record PlainToken(String token, String hash, String prefix) {}

    private static final SecureRandom RANDOM = new SecureRandom();

    public PlainToken issue() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = "vd_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new PlainToken(token, hash(token), token.substring(0, 8));
    }

    public String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(token.getBytes(StandardCharsets.US_ASCII));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : out) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
