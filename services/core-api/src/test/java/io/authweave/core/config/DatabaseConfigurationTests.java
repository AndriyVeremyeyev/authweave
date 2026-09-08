package io.authweave.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseConfigurationTests {

    @Test
    void databaseSettingsFollowComposeAndAllowSeparateMigrationConnections() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));
        assertUrls(environment, "jdbc:postgresql://localhost:5432/authweave",
                "jdbc:postgresql://localhost:5432/authweave");

        environment.withProperty("AUTHWEAVE_POSTGRES_PORT", "55432")
                .withProperty("AUTHWEAVE_POSTGRES_DB", "review_db")
                .withProperty("AUTHWEAVE_POSTGRES_ADMIN_USER", "review_admin");
        assertUrls(environment, "jdbc:postgresql://localhost:55432/review_db",
                "jdbc:postgresql://localhost:55432/review_db");
        assertEquals("review_admin", environment.getProperty("spring.flyway.user"));

        environment.withProperty("AUTHWEAVE_CORE_DB_URL", "jdbc:postgresql://localhost:55433/runtime_db");
        assertUrls(environment, "jdbc:postgresql://localhost:55433/runtime_db",
                "jdbc:postgresql://localhost:55433/runtime_db");

        environment.withProperty("AUTHWEAVE_MIGRATION_DB_URL", "jdbc:postgresql://localhost:55434/migration_db");
        assertUrls(environment, "jdbc:postgresql://localhost:55433/runtime_db",
                "jdbc:postgresql://localhost:55434/migration_db");
    }

    private static void assertUrls(MockEnvironment environment, String runtimeUrl, String migrationUrl) {
        assertEquals(runtimeUrl, environment.getProperty("spring.datasource.url"));
        assertEquals(migrationUrl, environment.getProperty("spring.flyway.url"));
    }
}
