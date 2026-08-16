package io.veridex.shared.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "veridex.api")
public class RateLimitProperties {

    private int rateLimitPerMinute = 600;

    public int getRateLimitPerMinute() {
        return rateLimitPerMinute;
    }

    public void setRateLimitPerMinute(int rateLimitPerMinute) {
        this.rateLimitPerMinute = rateLimitPerMinute;
    }

    public void setPerMinute(int perMinute) {
        setRateLimitPerMinute(perMinute);
    }
}
