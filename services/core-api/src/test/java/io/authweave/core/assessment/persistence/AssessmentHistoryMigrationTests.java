package io.authweave.core.assessment.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import io.authweave.core.PostgresIntegrationTest;
import io.authweave.core.assessment.domain.profile.ApplicationIdentityProfile;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class AssessmentHistoryMigrationTests extends PostgresIntegrationTest {

    @Test
    void upgradesExistingAssessmentsWithoutInventingEarlierVersions() throws Exception {
        // A separate database inside the disposable test container, never the owner's local database.
        String database = "history_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
        try (var admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var adminSql = admin.createStatement()) {
            adminSql.execute("CREATE DATABASE " + database);
            try {
                migrate(url, "2");
                var workspace = UUID.randomUUID();
                var assessment = UUID.randomUUID();
                String profile = JsonMapper.builder().build().writeValueAsString(ApplicationIdentityProfile.unknown());
                try (var connection = connect(url)) {
                    try (var insert = connection.prepareStatement("INSERT INTO core.workspaces(id) VALUES (?)")) {
                        insert.setObject(1, workspace);
                        insert.executeUpdate();
                    }
                    try (var insert = connection.prepareStatement("""
                            INSERT INTO core.assessments(workspace_id, id, status, profile, lock_version)
                            VALUES (?, ?, 'ARCHIVED', ?::jsonb, 7)
                            """)) {
                        insert.setObject(1, workspace);
                        insert.setObject(2, assessment);
                        insert.setString(3, profile);
                        insert.executeUpdate();
                    }
                }
                migrate(url, "3");
                migrate(url, "3"); // Re-running Flyway must not duplicate the baseline.
                try (var connection = connect(url); var sql = connection.createStatement()) {
                    try (var rows = sql.executeQuery("""
                            SELECT r.version, r.status, r.origin, r.profile = a.profile AS profile_matches,
                                   r.recorded_at = e.occurred_at AS timestamp_matches,
                                   e.action, e.actor_type, e.actor_id, e.previous_version,
                                   cardinality(e.changed_sections) AS section_count
                            FROM core.assessment_revisions r
                            JOIN core.assessments a ON a.workspace_id = r.workspace_id AND a.id = r.assessment_id
                            JOIN audit.assessment_events e ON e.workspace_id = r.workspace_id
                              AND e.assessment_id = r.assessment_id AND e.version = r.version
                            """)) {
                        assertTrue(rows.next());
                        assertEquals(7, rows.getLong("version"));
                        assertEquals("ARCHIVED", rows.getString("status"));
                        assertEquals("BASELINE", rows.getString("origin"));
                        assertTrue(rows.getBoolean("profile_matches"));
                        assertTrue(rows.getBoolean("timestamp_matches"));
                        assertEquals("assessment.baseline", rows.getString("action"));
                        assertEquals("MIGRATION", rows.getString("actor_type"));
                        assertEquals("flyway-v3", rows.getString("actor_id"));
                        assertNull(rows.getObject("previous_version"));
                        assertEquals(0, rows.getInt("section_count"));
                        assertFalse(rows.next());
                    }
                    try (var rows = sql.executeQuery("SELECT lock_version FROM core.assessments")) {
                        assertTrue(rows.next());
                        assertEquals(7, rows.getLong(1));
                    }
                }
            } finally {
                adminSql.execute("DROP DATABASE " + database);
            }
        }
    }

    private static Connection connect(String url) throws Exception {
        return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
    }

    private static void migrate(String url, String target) {
        Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
                .defaultSchema("authweave_migrations").locations("classpath:db/migration")
                .target(target).load().migrate();
    }
}
