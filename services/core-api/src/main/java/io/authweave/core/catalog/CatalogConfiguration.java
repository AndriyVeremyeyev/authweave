package io.authweave.core.catalog;

import java.io.IOException;
import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class CatalogConfiguration {

    @Bean
    ProviderCatalog syntheticProviderCatalog(ObjectMapper mapper) throws IOException {
        try (var input = new ClassPathResource("catalog/synthetic.v4.json").getInputStream()) {
            return mapper.readValue(input, ProviderCatalog.class);
        }
    }

    @Bean
    AuditabilityCatalog syntheticAuditabilityCatalog(ObjectMapper mapper, ProviderCatalog catalog) throws IOException {
        try (var input = new ClassPathResource("catalog/auditability-evidence.v1.json").getInputStream()) {
            var evidence = mapper.readValue(input, AuditabilityCatalog.class);
            evidence.validateBase(catalog);
            return evidence;
        }
    }

    @Bean
    Clock evaluationClock() {
        return Clock.systemUTC();
    }
}
