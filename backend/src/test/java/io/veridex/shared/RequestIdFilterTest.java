package io.veridex.shared;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.shared.infrastructure.RequestIdFilter;
import io.veridex.shared.infrastructure.RequestIds;
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
    void rejectsRidiculousIncomingLength() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        request.addHeader("X-Request-Id", "x".repeat(200));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestIdFilter().doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader("X-Request-Id"))
                .hasSizeLessThanOrEqualTo(100)
                .isNotEqualTo("x".repeat(200));
    }
}
