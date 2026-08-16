package io.veridex.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.veridex.shared.infrastructure.RequestIdFilter;
import io.veridex.shared.infrastructure.RequestIds;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    @Test
    void generatesRequestIdAndExposesHeaderMdcAndAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/qa/conversations");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seen = new String[1];

        new RequestIdFilter().doFilter(request, response, (req, res) -> {
            seen[0] = RequestIds.current(req);
            assertThat(MDC.get("requestId")).isEqualTo(seen[0]);
        });

        assertThat(seen[0]).isNotBlank();
        assertThat(response.getHeader("X-Request-Id")).isEqualTo(seen[0]);
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void propagatesIncomingRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("X-Request-Id", "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestIdFilter().doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader("X-Request-Id")).isEqualTo("trace-123");
    }

    @Test
    void generatesRequestIdForControlWhitespaceAndIllegalCharacters() throws Exception {
        for (String incoming : new String[] {"", "   ", "trace\rvalue", "trace value", " trace-123 ", "trace/value"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
            request.addHeader(RequestIds.HEADER, incoming);
            MockHttpServletResponse response = new MockHttpServletResponse();

            new RequestIdFilter().doFilter(request, response, (req, res) -> { });

            assertThat(response.getHeader(RequestIds.HEADER))
                    .as("incoming request ID %s", incoming)
                    .isNotEqualTo(incoming)
                    .matches(value -> isUuid(value));
        }
    }

    @Test
    void propagatesValidRequestIdAtMaximumLength() throws Exception {
        String incoming = "a".repeat(100);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(RequestIds.HEADER, incoming);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestIdFilter().doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(RequestIds.HEADER)).isEqualTo(incoming);
    }

    @Test
    void generatesRequestIdWhenIncomingValueExceedsMaximumLength() throws Exception {
        String incoming = "a".repeat(101);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader(RequestIds.HEADER, incoming);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestIdFilter().doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(RequestIds.HEADER))
                .isNotEqualTo(incoming)
                .matches(value -> isUuid(value));
    }

    @Test
    void propagatesChainExceptionAndClearsMdc() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        IOException failure = new IOException("chain failed");

        assertThatThrownBy(() -> new RequestIdFilter().doFilter(request, response, (req, res) -> {
                    assertThat(MDC.get("requestId")).isNotBlank();
                    throw failure;
                }))
                .isSameAs(failure);
        assertThat(MDC.get("requestId")).isNull();
    }

    private static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
