-- Changeset liquibase/ext/trino/golden/changelogs/dropSchema.xml::create-schema::Leonid-Efremov
CREATE SCHEMA IF NOT EXISTS iceberg_catalog.golden_drop_schema;

-- Changeset liquibase/ext/trino/golden/changelogs/dropSchema.xml::drop-schema::Leonid-Efremov
DROP SCHEMA IF EXISTS iceberg_catalog.golden_drop_schema CASCADE;
