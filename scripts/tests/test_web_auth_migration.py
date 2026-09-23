"""Offline tests for the isolated web authentication migration runner."""

import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("migrate_web_auth", Path(__file__).resolve().parents[1] / "migrate_web_auth.py")
migration = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(migration)


class MigrationRunnerTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="authweave-web-migration-test-")
        self.addCleanup(temporary.cleanup)
        self.environment = Path(temporary.name) / ".env"
        self.environment.write_text(
            "AUTHWEAVE_POSTGRES_ADMIN_USER=authweave_admin\n"
            "AUTHWEAVE_POSTGRES_DB=authweave\n"
            "AUTHWEAVE_POSTGRES_ADMIN_PASSWORD=synthetic-secret\n",
            encoding="utf-8",
        )
        self.environment.chmod(0o600)

    def test_uses_exact_local_compose_database_and_sanitized_environment(self):
        with patch.object(migration, "ENV", self.environment), \
                patch.dict(os.environ, {"COMPOSE_FILE": "other.yaml",
                                         "AUTHWEAVE_POSTGRES_DB": "other_database"}), \
                patch.object(migration.subprocess, "run") as run:
            migration.migrate()
        self.assertEqual(run.call_count, 4)
        command = run.call_args.args[0]
        self.assertEqual(command[:4], ["docker", "compose", "--project-name", "authweave"])
        self.assertEqual(command[-5:], ["--set=ON_ERROR_STOP=1", "--username", "authweave_admin",
                                        "--dbname", "authweave"])
        self.assertIn("CREATE TABLE web.sessions", run.call_args_list[0].kwargs["input"])
        self.assertIn("ADD COLUMN workspace_id", run.call_args_list[1].kwargs["input"])
        self.assertIn("ADD COLUMN curator_project_id", run.call_args_list[2].kwargs["input"])
        self.assertIn("ADD COLUMN purpose", run.call_args_list[3].kwargs["input"])
        self.assertNotIn("synthetic-secret", str(command))
        self.assertNotIn("COMPOSE_FILE", run.call_args.kwargs["env"])
        self.assertNotIn("AUTHWEAVE_POSTGRES_DB", run.call_args.kwargs["env"])
        self.assertTrue(run.call_args.kwargs["capture_output"])

    def test_rejects_symlink_and_world_readable_environment(self):
        self.environment.chmod(0o644)
        with patch.object(migration, "ENV", self.environment):
            with self.assertRaisesRegex(ValueError, "mode 600"):
                migration.config()
        self.environment.unlink()
        self.environment.symlink_to(migration.COMPOSE)
        with patch.object(migration, "ENV", self.environment):
            with self.assertRaisesRegex(ValueError, "symlink"):
                migration.config()

    def test_rejects_unexpected_database_name_before_docker(self):
        self.environment.write_text("AUTHWEAVE_POSTGRES_ADMIN_USER=authweave_admin\n"
                                    "AUTHWEAVE_POSTGRES_DB=authweave;other\n")
        with patch.object(migration, "ENV", self.environment), patch.object(migration.subprocess, "run") as run:
            with self.assertRaisesRegex(ValueError, "invalid"):
                migration.migrate()
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
