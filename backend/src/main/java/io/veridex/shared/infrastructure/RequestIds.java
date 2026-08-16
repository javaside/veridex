package io.veridex.shared.infrastructure;

import jakarta.servlet.ServletRequest;

public final class RequestIds {

    public static final String ATTRIBUTE = "veridex.requestId";
    public static final String HEADER = "X-Request-Id";

    private RequestIds() {
    }

    public static String current(ServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value == null ? null : value.toString();
    }
}
