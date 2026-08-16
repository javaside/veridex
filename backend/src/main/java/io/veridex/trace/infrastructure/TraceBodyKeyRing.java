package io.veridex.trace.infrastructure;

import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TraceBodyKeyRing {

    private static final String KEY_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,99}";
    private final String currentKeyId;
    private final Map<String, byte[]> keys;
    private final byte[] fingerprintKey;

    public TraceBodyKeyRing(TraceBodyProperties properties) {
        if (properties.getCapturePolicy() == TraceBodyProperties.CapturePolicy.NONE) {
            currentKeyId = "";
            keys = Map.of();
            fingerprintKey = optionalKey(properties.getFingerprintKey());
            return;
        }
        currentKeyId = requireKeyId(properties.getCurrentKeyId(), "current key ID");
        byte[] currentKey = decode(properties.getCurrentKey(), "current key");
        Map<String, byte[]> parsed = new LinkedHashMap<>();
        String historical = properties.getHistoricalKeys();
        if (historical != null && !historical.isBlank()) {
            for (String entry : historical.split(",", -1)) {
                int equals = entry.indexOf('=');
                if (equals <= 0) {
                    throw invalid("historical key must be keyId=Base64Key");
                }
                String id = requireKeyId(entry.substring(0, equals).trim(), "historical key ID");
                put(parsed, id, decode(entry.substring(equals + 1).trim(), "historical key"));
            }
        }
        if (!parsed.containsKey(currentKeyId)) {
            throw invalid("current key is absent from key ring");
        }
        if (!java.util.Arrays.equals(parsed.get(currentKeyId), currentKey)) {
            throw invalid("current key does not match ring");
        }
        keys = Collections.unmodifiableMap(parsed);
        fingerprintKey = optionalKey(properties.getFingerprintKey());
    }

    public String currentKeyId() { return currentKeyId; }
    public byte[] currentKey() { return key(currentKeyId); }
    public byte[] key(String keyId) {
        byte[] value = keys.get(keyId);
        if (value == null) throw new IllegalArgumentException("unknown trace body key ID");
        return value.clone();
    }
    public boolean isEmpty() { return keys.isEmpty(); }
    public byte[] fingerprintKey() { return fingerprintKey == null ? null : fingerprintKey.clone(); }

    private static void put(Map<String, byte[]> keys, String id, byte[] value) {
        if (keys.putIfAbsent(id, value) != null) throw invalid("duplicate key ID");
    }
    private static String requireKeyId(String id, String label) {
        if (id == null || !id.matches(KEY_ID_PATTERN)) throw invalid("invalid " + label);
        return id;
    }
    private static byte[] optionalKey(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        return decode(encoded.trim(), "fingerprint key");
    }

    private static byte[] decode(String encoded, String label) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encoded);
            if (decoded.length != 32) throw invalid(label + " must be 32 bytes");
            return decoded;
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("must be")) throw exception;
            throw invalid("invalid Base64 " + label);
        }
    }
    private static IllegalStateException invalid(String message) { return new IllegalStateException(message); }
}
