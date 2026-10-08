package io.authweave.core.assessment.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import io.authweave.core.PostgresIntegrationTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit cross-language integration target, not a browser or OIDC login test. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "AUTHWEAVE_CORE_SERVICE_TOKEN=synthetic-guided-real-core-token-00000000000000000000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GuidedAssessmentBffIT extends PostgresIntegrationTest {

    @Value("${local.server.port}") private int port;

    @Test
    void threeGuidedBffFlowsUseRealHttpAuthorizationAndIsolatedRuntimeDatabaseRoles() throws Exception {
        var web = Path.of("..", "..", "apps", "web").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(web.resolve("node_modules/next")), "Run make setup-web before this integration target");
        assertTrue(List.of("localhost", "127.0.0.1").contains(postgres.getHost()), "The BFF session store is loopback-only");
        try (var admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var sql = admin.createStatement()) {
            for (int pass = 0; pass < 2; pass++) {
                for (var migration : List.of("001_auth_sessions.sql", "002_session_workspace.sql",
                        "003_session_curator_scope.sql", "004_reauthentication_transactions.sql")) {
                    sql.execute(Files.readString(web.resolve("db/migrations").resolve(migration)));
                }
            }
        }
        var log = Path.of("target", "guided-bff-integration.log").toAbsolutePath();
        var command = new ProcessBuilder("node", "--test", "--test-reporter=tap", "tests/guided-core.integration.mts")
                .directory(web.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        // Never inherit local app credentials or configuration. All connections are to this test container/server.
        var env = command.environment();
        env.keySet().removeIf(key -> key.startsWith("AUTHWEAVE_"));
        env.put("AUTHWEAVE_TEST_GUIDED_BFF", "synthetic-guided-real-core-v1");
        env.put("AUTHWEAVE_TEST_CORE_ORIGIN", "http://127.0.0.1:" + port);
        env.put("AUTHWEAVE_POSTGRES_DB", postgres.getDatabaseName());
        env.put("AUTHWEAVE_POSTGRES_PORT", String.valueOf(postgres.getMappedPort(5432)));
        env.put("AUTHWEAVE_WEB_DB_PASSWORD", "web-test-password");
        env.put("AUTHWEAVE_CORE_SERVICE_TOKEN", "synthetic-guided-real-core-token-00000000000000000000");
        env.put("AUTHWEAVE_OIDC_ISSUER", "http://localhost:8081");
        env.put("AUTHWEAVE_OIDC_CLIENT_ID", "synthetic-guided-real-core-client");
        env.put("AUTHWEAVE_PUBLIC_ORIGIN", "http://localhost:3000");
        var process = command.start();
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "Guided BFF integration timed out; see " + log);
            var output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("# tests 3") && output.contains("# pass 3") && output.contains("# skipped 0"), output);
            System.out.println(output);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(child -> child.destroyForcibly());
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        try (var admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var sql = admin.createStatement()) {
            // Only creation + the five explicit saves create history. No-op saves, previews and denials do not.
            try (var rows = sql.executeQuery("""
                    SELECT a.lock_version,
                      (SELECT count(*) FROM core.assessment_revisions r
                       WHERE r.workspace_id = a.workspace_id AND r.assessment_id = a.id) AS revisions,
                      (SELECT count(*) FROM audit.assessment_events e
                       WHERE e.workspace_id = a.workspace_id AND e.assessment_id = a.id) AS events
                    FROM core.assessments a JOIN core.personal_workspaces w ON w.workspace_id = a.workspace_id
                    WHERE w.subject IN ('synthetic-guided-real-core-b2b', 'synthetic-guided-real-core-citizen',
                                        'synthetic-guided-real-core-workforce')
                    """)) {
                int count = 0;
                while (rows.next()) {
                    assertEquals(5, rows.getLong("lock_version"));
                    assertEquals(6, rows.getInt("revisions"));
                    assertEquals(6, rows.getInt("events"));
                    count++;
                }
                assertEquals(3, count);
            }
            try (var rows = sql.executeQuery("SELECT count(*) FROM web.sessions")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); assertFalse(rows.next());
            }
        }
    }
}
