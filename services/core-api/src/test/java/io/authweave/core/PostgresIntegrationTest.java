package io.authweave.core;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

public abstract class PostgresIntegrationTest {

    protected static final String CORE_RUNTIME_PASSWORD = "core-test-password";

    protected static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            "pgvector/pgvector:0.8.6-pg18-bookworm")
            .withDatabaseName("authweave")
            .withUsername("authweave_admin")
            .withPassword("admin-test-password")
            .withInitScript("db/test/init-runtime-roles.sql");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "authweave_core_runtime");
        registry.add("spring.datasource.password", () -> CORE_RUNTIME_PASSWORD);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }
}
