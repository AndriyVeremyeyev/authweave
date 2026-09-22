-- Keyset listing is scoped to one workspace and ordered newest first.
CREATE INDEX assessments_workspace_created_id_idx
  ON core.assessments (workspace_id, created_at DESC, id DESC);
