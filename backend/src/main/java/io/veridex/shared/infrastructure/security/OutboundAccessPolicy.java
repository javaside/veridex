package io.veridex.shared.infrastructure.security;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * 出站访问策略：只允许配置的 scheme、host 和 port，并在 DNS 解析后拒绝
 * loopback、link-local、RFC1918、CGNAT、metadata 和本地/组播地址，防止 SSRF。
 */
public final class OutboundAccessPolicy {

    private final Set<String> allowedHosts;
    private final Set<Integer> allowedPorts;
    private final boolean allowInsecureHttp;

    public OutboundAccessPolicy(Set<String> allowedHosts, Set<Integer> allowedPorts, boolean allowInsecureHttp) {
        this.allowedHosts = allowedHosts;
        this.allowedPorts = allowedPorts;
        this.allowInsecureHttp = allowInsecureHttp;
    }

    public ValidatedTarget validate(URI target) {
        String scheme = target.getScheme();
        boolean schemeAllowed = "https".equalsIgnoreCase(scheme)
                || (allowInsecureHttp && "http".equalsIgnoreCase(scheme));
        if (scheme == null || !schemeAllowed) {
            throw new IllegalStateException("outbound_scheme_denied");
        }
        String host = target.getHost();
        if (host == null || !allowedHosts.contains(host)) {
            throw new IllegalStateException("outbound_host_denied");
        }
        int port = target.getPort();
        if (port > 0 && !allowedPorts.contains(port)) {
            throw new IllegalStateException("outbound_host_denied");
        }
        return new ValidatedTarget(scheme.toLowerCase(java.util.Locale.ROOT), host, port);
    }

    public void validateResolvedAddresses(URI target, List<InetAddress> addresses) {
        for (InetAddress address : addresses) {
            if (isDangerous(address)) {
                throw new IllegalStateException("outbound_private_address");
            }
        }
    }

    private boolean isDangerous(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            // 100.64.0.0/10 (CGNAT)
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
        }
        return false;
    }

    public record ValidatedTarget(String scheme, String host, int port) {
        public int effectivePort() {
            return port > 0 ? port : ("http".equals(scheme) ? 80 : 443);
        }
    }
}
