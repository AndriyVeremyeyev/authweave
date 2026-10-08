package io.authweave.core.assessment.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import io.authweave.core.PostgresIntegrationTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit local interoperability check; never part of default tests or secret-free CI. */
@EnabledIfSystemProperty(named = "authweave.local-zitadel-browser", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "AUTHWEAVE_CORE_SERVICE_TOKEN=synthetic-browser-core-token-0000000000000000000000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LocalZitadelBrowserIT extends PostgresIntegrationTest {

    @Value("${local.server.port}") private int port;

    @Test
    void existingSyntheticUsersUseRealZitadelAndIsolatedApplicationData() throws Exception {
        var web = Path.of("..", "..", "apps", "web").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(web.resolve(".next/standalone/server.js")), "Run make check-web first");
        assertTrue(List.of("localhost", "127.0.0.1").contains(postgres.getHost()), "Browser DB must be loopback-only");
        try (var admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var sql = admin.createStatement()) {
            for (int pass = 0; pass < 2; pass++) for (var migration : List.of("001_auth_sessions.sql", "002_session_workspace.sql",
                    "003_session_curator_scope.sql", "004_reauthentication_transactions.sql")) {
                sql.execute(Files.readString(web.resolve("db/migrations").resolve(migration)));
            }
        }
        var log = Path.of("target", "local-zitadel-browser.log").toAbsolutePath();
        var command = new ProcessBuilder("node", "tests/local-zitadel-browser.mts").directory(web.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        var env = command.environment();
        env.keySet().removeIf(key -> key.startsWith("AUTHWEAVE_") || key.startsWith("DEBUG")
                || List.of("PWDEBUG", "NODE_DEBUG", "NODE_DEBUG_NATIVE", "NODE_OPTIONS").contains(key));
        env.put("AUTHWEAVE_TEST_BROWSER", "local-zitadel-browser-v1");
        env.put("AUTHWEAVE_TEST_CORE_ORIGIN", "http://127.0.0.1:" + port);
        env.put("AUTHWEAVE_POSTGRES_DB", postgres.getDatabaseName());
        env.put("AUTHWEAVE_POSTGRES_PORT", String.valueOf(postgres.getMappedPort(5432)));
        env.put("AUTHWEAVE_CORE_SERVICE_TOKEN", "synthetic-browser-core-token-0000000000000000000000");
        var process = command.start();
        try {
            assertTrue(process.waitFor(300, TimeUnit.SECONDS), "Local ZITADEL browser check timed out; see " + log);
            var output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("Real ZITADEL browser: 2 users passed"), output);
            System.out.println(output);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(child -> child.destroyForcibly());
                process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        try (var admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var sql = admin.createStatement()) {
            for (var table : List.of("web.sessions", "web.oidc_login_transactions")) {
                try (var rows = sql.executeQuery("SELECT count(*) FROM " + table)) {
                    assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
                }
            }
            try (var rows = sql.executeQuery("""
                    SELECT count(*), count(DISTINCT subject) FROM core.personal_workspaces
                    WHERE issuer = 'http://localhost:8081'
                    """)) {
                assertTrue(rows.next()); assertEquals(2, rows.getInt(1)); assertEquals(2, rows.getInt(2));
            }
            try (var rows = sql.executeQuery("""
                    SELECT a.lock_version,
                      (SELECT count(*) FROM core.assessment_revisions r WHERE r.assessment_id = a.id) AS revisions,
                      (SELECT count(*) FROM audit.assessment_events e WHERE e.assessment_id = a.id) AS events
                    FROM core.assessments a
                    """)) {
                int count = 0;
                while (rows.next()) {
                    assertEquals(1, rows.getLong("lock_version"));
                    assertEquals(2, rows.getInt("revisions")); assertEquals(2, rows.getInt("events")); count++;
                }
                assertEquals(2, count);
            }
        }
    }
}
