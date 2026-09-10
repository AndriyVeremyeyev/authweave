package io.authweave.core.config;

import java.net.InetAddress;

import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/** Remove only when the API has an authenticated, authorized deployment boundary. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
final class LocalOnlyServerConfiguration
        implements WebServerFactoryCustomizer<ConfigurableServletWebServerFactory>, Ordered {

    private final ServerProperties properties;

    LocalOnlyServerConfiguration(ServerProperties properties) {
        this.properties = properties;
    }

    @Override
    public void customize(ConfigurableServletWebServerFactory factory) {
        InetAddress address = properties.getAddress();
        if (address == null || !address.isLoopbackAddress()) {
            throw new IllegalStateException(
                    "The unauthenticated Core API requires a loopback server.address.");
        }
        factory.setAddress(address);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
