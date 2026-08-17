package io.veridex.shared.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.veridex.shared.infrastructure.security.OutboundAccessPolicy;
import io.veridex.shared.infrastructure.security.SafeHttpClient;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SafeHttpClientTest {

    private static final SafeHttpClient.ResponseBudget BUDGET =
            new SafeHttpClient.ResponseBudget(Duration.ofSeconds(5), 1024);

    private OutboundAccessPolicy policy() {
        return new OutboundAccessPolicy(Set.of("allowed.example"), Set.of(443), true);
    }

    private static InetAddress addr(int... bytes) {
        byte[] raw = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            raw[i] = (byte) bytes[i];
        }
        try {
            return InetAddress.getByAddress(raw);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void rejectsPrivateResolvedAddressBeforeRequest() {
        HttpClient httpClient = mock(HttpClient.class);
        SafeHttpClient client = new SafeHttpClient(policy(), httpClient,
                host -> List.of(addr(127, 0, 0, 1)));

        assertThatThrownBy(() -> client.get(URI.create("https://allowed.example/"), BUDGET))
                .hasMessage("outbound_private_address");
    }

    @Test
    void rejectsRedirectResponse() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> response = response(302, new byte[0]);
        when(httpClient.<byte[]>send(any(), any())).thenReturn(response);

        SafeHttpClient client = new SafeHttpClient(policy(), httpClient,
                host -> List.of(addr(93, 184, 216, 34)));

        assertThatThrownBy(() -> client.get(URI.create("https://allowed.example/"), BUDGET))
                .hasMessage("outbound_redirect_denied");
    }

    @Test
    void rejectsOversizedResponse() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<byte[]> response = response(200, new byte[2048]);
        when(httpClient.<byte[]>send(any(), any())).thenReturn(response);

        SafeHttpClient client = new SafeHttpClient(policy(), httpClient,
                host -> List.of(addr(93, 184, 216, 34)));

        assertThatThrownBy(() -> client.get(URI.create("https://allowed.example/"), BUDGET))
                .hasMessage("outbound_response_too_large");
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(int status, byte[] body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
