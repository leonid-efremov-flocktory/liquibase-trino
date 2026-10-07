-- Changeset liquibase/ext/trino/golden/changelogs/renameColumn.xml::create-schema::Leonid-Efremov
CREATE SCHEMA IF NOT EXISTS iceberg_catalog.golden_rename_column;

-- Changeset liquibase/ext/trino/golden/changelogs/renameColumn.xml::create-table::Leonid-Efremov
CREATE TABLE golden_rename_column.t (id INT, txt VARCHAR(64));

-- Changeset liquibase/ext/trino/golden/changelogs/renameColumn.xml::rename-column::Leonid-Efremov
ALTER TABLE golden_rename_column.t RENAME COLUMN txt TO body;
