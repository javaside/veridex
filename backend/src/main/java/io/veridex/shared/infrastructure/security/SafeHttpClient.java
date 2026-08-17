package io.veridex.shared.infrastructure.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/**
 * 统一出站 HTTP 客户端：连接前校验 URL 与 DNS 解析地址，禁用自动重定向，
 * 限制响应字节数和连接/读取超时。任何策略违规返回固定错误码。
 */
public final class SafeHttpClient {

    private final OutboundAccessPolicy policy;
    private final HttpClient httpClient;
    private final Function<String, List<InetAddress>> resolver;

    public SafeHttpClient(OutboundAccessPolicy policy) {
        this(policy, defaultHttpClient(), SafeHttpClient::resolveAll);
    }

    public SafeHttpClient(OutboundAccessPolicy policy, HttpClient httpClient, Function<String, List<InetAddress>> resolver) {
        this.policy = policy;
        this.httpClient = httpClient;
        this.resolver = resolver;
    }

    public SafeHttpResponse get(URI target, ResponseBudget budget) {
        OutboundAccessPolicy.ValidatedTarget validated = policy.validate(target);
        policy.validateResolvedAddresses(target, resolver.apply(validated.host()));

        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(budget.timeout())
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                throw new IllegalStateException("outbound_redirect_denied");
            }
            if (response.body() != null && response.body().length > budget.maxResponseBytes()) {
                throw new IllegalStateException("outbound_response_too_large");
            }
            return new SafeHttpResponse(response.statusCode(), response.body(), response.headers().map());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IllegalStateException("outbound_timeout", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("outbound_timeout", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("outbound_request_failed", e);
        }
    }

    private static HttpClient defaultHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private static List<InetAddress> resolveAll(String host) {
        try {
            return List.of(InetAddress.getAllByName(host));
        } catch (Exception e) {
            throw new IllegalStateException("outbound_request_failed", e);
        }
    }

    public record ResponseBudget(Duration timeout, long maxResponseBytes) {
    }

    public record SafeHttpResponse(int statusCode, byte[] body, java.util.Map<String, List<String>> headers) {
    }
}
