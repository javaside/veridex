package io.veridex.shared.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.veridex.shared.infrastructure.security.OutboundAccessPolicy;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OutboundAccessPolicyTest {

    private OutboundAccessPolicy policy() {
        return new OutboundAccessPolicy(Set.of("allowed.example", "localhost"), Set.of(443, 8443), true);
    }

    @Test
    void rejectsNonHttpAndHttpsSchemes() {
        assertThatThrownBy(() -> policy().validate(URI.create("file:///etc/passwd")))
                .hasMessage("outbound_scheme_denied");
        assertThatThrownBy(() -> policy().validate(URI.create("gopher://127.0.0.1/")))
                .hasMessage("outbound_scheme_denied");
        assertThatThrownBy(() -> policy().validate(URI.create("ftp://allowed.example/file")))
                .hasMessage("outbound_scheme_denied");
    }

    @Test
    void rejectsUnlistedHost() {
        assertThatThrownBy(() -> policy().validate(URI.create("https://evil.example/path")))
                .hasMessage("outbound_host_denied");
    }

    @Test
    void acceptsListedHostAndPort() {
        assertThatCode(() -> policy().validate(URI.create("https://allowed.example/path")))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy().validate(URI.create("https://allowed.example:8443/path")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnlistedPort() {
        assertThatThrownBy(() -> policy().validate(URI.create("https://allowed.example:9999/path")))
                .hasMessage("outbound_host_denied");
    }

    @Test
    void rejectsLoopbackResolvedAddress() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        assertThatThrownBy(() -> policy().validateResolvedAddresses(
                URI.create("https://allowed.example/"), List.of(loopback)))
                .hasMessage("outbound_private_address");
    }

    @Test
    void rejectsPrivateAndLinkLocalResolvedAddress() throws Exception {
        assertThatThrownBy(() -> policy().validateResolvedAddresses(
                URI.create("https://allowed.example/"), List.of(InetAddress.getByName("10.0.0.2"))))
                .hasMessage("outbound_private_address");
        assertThatThrownBy(() -> policy().validateResolvedAddresses(
                URI.create("https://allowed.example/"), List.of(InetAddress.getByName("169.254.169.254"))))
                .hasMessage("outbound_private_address");
    }

    @Test
    void rejectsMixedPublicAndPrivateResolvedAddresses() throws Exception {
        InetAddress publicAddr = InetAddress.getByName("93.184.216.34");
        InetAddress privateAddr = InetAddress.getByName("192.168.1.10");
        assertThatThrownBy(() -> policy().validateResolvedAddresses(
                URI.create("https://allowed.example/"), List.of(publicAddr, privateAddr)))
                .hasMessage("outbound_private_address");
    }

    @Test
    void acceptsPublicResolvedAddress() throws Exception {
        InetAddress publicAddr = InetAddress.getByName("93.184.216.34");
        assertThatCode(() -> policy().validateResolvedAddresses(
                URI.create("https://allowed.example/"), List.of(publicAddr)))
                .doesNotThrowAnyException();
    }
}
