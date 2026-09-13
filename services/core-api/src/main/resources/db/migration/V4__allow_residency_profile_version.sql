-- Permit both stored formats without rewriting current profiles or immutable history.
ALTER TABLE core.assessments DROP CONSTRAINT assessments_profile_schema_version_ck;
ALTER TABLE core.assessments ADD CONSTRAINT assessments_profile_schema_version_ck
  CHECK (profile_schema_version IN (1, 2));
ALTER TABLE core.assessment_revisions DROP CONSTRAINT assessment_revisions_profile_schema_version_check;
ALTER TABLE core.assessment_revisions ADD CONSTRAINT assessment_revisions_profile_schema_version_check
  CHECK (profile_schema_version IN (1, 2));
