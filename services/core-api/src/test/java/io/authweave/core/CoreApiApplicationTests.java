package io.authweave.core;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CoreApiApplicationTests {

	private static final String CORE_RUNTIME_PASSWORD = "core-test-password";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(
			"pgvector/pgvector:0.8.6-pg18-bookworm")
			.withDatabaseName("authweave")
			.withUsername("authweave_admin")
			.withPassword("admin-test-password")
			.withInitScript("db/test/init-runtime-roles.sql");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void contextLoads() {
	}

	@Test
	void reportsLiveness() throws Exception {
		mockMvc.perform(get("/actuator/health/liveness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void reportsReadiness() throws Exception {
		mockMvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void appliesDatabaseBaseline() {
		String vectorVersion = jdbcTemplate.queryForObject(
				"SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class);
		assertEquals("0.8.6", vectorVersion);

		Map<String, String> schemaOwners = jdbcTemplate.query(
				"""
				SELECT nspname, pg_get_userbyid(nspowner)
				FROM pg_namespace
				WHERE nspname IN ('core', 'web', 'audit')
				""",
				resultSet -> {
					Map<String, String> owners = new HashMap<>();
					while (resultSet.next()) {
						owners.put(resultSet.getString(1), resultSet.getString(2));
					}
					return owners;
				});

		assertEquals(Set.of("core", "web", "audit"), schemaOwners.keySet());
		assertEquals(Set.of("authweave_admin"), Set.copyOf(schemaOwners.values()));
	}

	@Test
	void coreRuntimeCannotCreateTables() throws SQLException {
		try (Connection connection = DriverManager.getConnection(
				postgres.getJdbcUrl(), "authweave_core_runtime", CORE_RUNTIME_PASSWORD);
				Statement statement = connection.createStatement()) {
			SQLException exception = assertThrows(SQLException.class,
					() -> statement.execute("CREATE TABLE core.forbidden_runtime_table (id bigint)"));
			assertEquals("42501", exception.getSQLState());
		}
	}

}
