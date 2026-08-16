package io.veridex.trace.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("veridex.trace.body")
public class TraceBodyProperties {

    public enum CapturePolicy { NONE, ERRORS, ALL }

    private CapturePolicy capturePolicy = CapturePolicy.NONE;
    private Duration retention = Duration.ofHours(24);
    private DataSize maxPlaintextSize = DataSize.ofKilobytes(256);
    private Duration cleanupInterval = Duration.ofHours(1);
    private int cleanupBatchSize = 500;
    private String fingerprintKey = "";
    private String currentKeyId = "";
    private String currentKey = "";
    private String historicalKeys = "";

    public CapturePolicy getCapturePolicy() { return capturePolicy; }
    public void setCapturePolicy(String value) { this.capturePolicy = CapturePolicy.valueOf(value.trim().toUpperCase()); }
    public void setCapturePolicy(CapturePolicy value) { this.capturePolicy = value; }
    public Duration getRetention() { return retention; }
    public void setRetention(Duration retention) { this.retention = retention; }
    public DataSize getMaxPlaintextSize() { return maxPlaintextSize; }
    public void setMaxPlaintextSize(DataSize maxPlaintextSize) { this.maxPlaintextSize = maxPlaintextSize; }
    public Duration getCleanupInterval() { return cleanupInterval; }
    public void setCleanupInterval(Duration cleanupInterval) { this.cleanupInterval = cleanupInterval; }
    public int getCleanupBatchSize() { return cleanupBatchSize; }
    public void setCleanupBatchSize(int cleanupBatchSize) { this.cleanupBatchSize = cleanupBatchSize; }
    public String getFingerprintKey() { return fingerprintKey; }
    public void setFingerprintKey(String fingerprintKey) { this.fingerprintKey = valueOrEmpty(fingerprintKey); }
    public String getCurrentKeyId() { return currentKeyId; }
    public void setCurrentKeyId(String currentKeyId) { this.currentKeyId = valueOrEmpty(currentKeyId); }
    public String getCurrentKey() { return currentKey; }
    public void setCurrentKey(String currentKey) { this.currentKey = valueOrEmpty(currentKey); }
    public String getHistoricalKeys() { return historicalKeys; }
    public void setHistoricalKeys(String historicalKeys) { this.historicalKeys = valueOrEmpty(historicalKeys); }

    private static String valueOrEmpty(String value) { return value == null ? "" : value; }
}
