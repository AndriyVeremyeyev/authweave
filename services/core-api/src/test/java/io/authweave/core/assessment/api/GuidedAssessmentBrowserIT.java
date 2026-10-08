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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Browser/production BFF/real Core integration with a signed-token OIDC protocol double. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "AUTHWEAVE_CORE_SERVICE_TOKEN=synthetic-browser-core-token-0000000000000000000000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GuidedAssessmentBrowserIT extends PostgresIntegrationTest {

    @Value("${local.server.port}") private int port;

    @Test
    void desktopAndMobileGuidedFlowsUseOidcLoginAndRealCoreWithoutOwnerData() throws Exception {
        var web = Path.of("..", "..", "apps", "web").toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(web.resolve(".next/standalone/server.js")), "Run make check-web first");
        assertTrue(List.of("localhost", "127.0.0.1").contains(postgres.getHost()), "Browser DB must be loopback-only");
        try (var admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var sql = admin.createStatement()) {
            for (int pass = 0; pass < 2; pass++) {
                for (var migration : List.of("001_auth_sessions.sql", "002_session_workspace.sql",
                        "003_session_curator_scope.sql", "004_reauthentication_transactions.sql")) {
                    sql.execute(Files.readString(web.resolve("db/migrations").resolve(migration)));
                }
            }
        }
        var log = Path.of("target", "guided-browser-integration.log").toAbsolutePath();
        var command = new ProcessBuilder("node", "tests/browser-harness.mts").directory(web.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        var env = command.environment();
        env.keySet().removeIf(key -> key.startsWith("AUTHWEAVE_"));
        env.put("AUTHWEAVE_TEST_BROWSER", "synthetic-browser-core-v1");
        env.put("AUTHWEAVE_TEST_CORE_ORIGIN", "http://127.0.0.1:" + port);
        env.put("AUTHWEAVE_POSTGRES_DB", postgres.getDatabaseName());
        env.put("AUTHWEAVE_POSTGRES_PORT", String.valueOf(postgres.getMappedPort(5432)));
        env.put("AUTHWEAVE_CORE_SERVICE_TOKEN", "synthetic-browser-core-token-0000000000000000000000");
        var process = command.start();
        try {
            assertTrue(process.waitFor(300, TimeUnit.SECONDS), "Browser integration timed out; see " + log);
            var output = Files.readString(log);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("Browser/OIDC E2E: 8 passed"), output);
            System.out.println(output);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(child -> child.destroyForcibly());
                process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        try (var admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var sql = admin.createStatement()) {
            try (var rows = sql.executeQuery("""
                    SELECT w.subject, a.lock_version,
                      (SELECT count(*) FROM core.assessment_revisions r
                       WHERE r.workspace_id = a.workspace_id AND r.assessment_id = a.id) AS revisions,
                      (SELECT count(*) FROM audit.assessment_events e
                       WHERE e.workspace_id = a.workspace_id AND e.assessment_id = a.id) AS events
                    FROM core.assessments a JOIN core.personal_workspaces w ON w.workspace_id = a.workspace_id
                    WHERE w.subject LIKE 'synthetic-browser-%'
                    """)) {
                int guided = 0, security = 0;
                while (rows.next()) {
                    boolean failurePath = rows.getString("subject").endsWith("-security-owner");
                    assertEquals(failurePath ? 1 : 5, rows.getLong("lock_version"));
                    assertEquals(failurePath ? 2 : 6, rows.getInt("revisions"));
                    assertEquals(failurePath ? 2 : 6, rows.getInt("events"));
                    if (failurePath) security++; else guided++;
                }
                assertEquals(6, guided); assertEquals(2, security);
            }
            try (var rows = sql.executeQuery("SELECT count(*) FROM web.sessions")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
            try (var rows = sql.executeQuery("SELECT count(*) FROM core.personal_workspaces WHERE subject LIKE '%-invalid' OR subject LIKE '%-different'")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
        }
    }
}
