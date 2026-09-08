package io.authweave.core.config;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class LocalOnlyServerConfigurationTests {

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1"})
    void bindsOnlyToLoopback(String host) throws Exception {
        ServerProperties properties = new ServerProperties();
        properties.setAddress(InetAddress.getByName(host));
        var factory = mock(ConfigurableServletWebServerFactory.class);
        new LocalOnlyServerConfiguration(properties).customize(factory);
        verify(factory).setAddress(properties.getAddress());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::", "192.0.2.1"})
    void refusesNetworkExposure(String host) throws Exception {
        ServerProperties properties = new ServerProperties();
        properties.setAddress(InetAddress.getByName(host));
        assertRejected(properties);
    }

    @Test
    void refusesAnUnspecifiedBinding() {
        assertRejected(new ServerProperties());
    }

    private static void assertRejected(ServerProperties properties) {
        var factory = mock(ConfigurableServletWebServerFactory.class);
        assertThrows(IllegalStateException.class,
                () -> new LocalOnlyServerConfiguration(properties).customize(factory));
        verifyNoInteractions(factory);
    }
}
