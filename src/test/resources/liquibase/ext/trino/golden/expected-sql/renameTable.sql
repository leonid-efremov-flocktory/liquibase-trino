-- Changeset liquibase/ext/trino/golden/changelogs/renameTable.xml::create-schema::Leonid-Efremov
CREATE SCHEMA IF NOT EXISTS iceberg_catalog.golden_rename_table;

-- Changeset liquibase/ext/trino/golden/changelogs/renameTable.xml::create-table::Leonid-Efremov
CREATE TABLE golden_rename_table.t_before (id INT);

-- Changeset liquibase/ext/trino/golden/changelogs/renameTable.xml::rename-table::Leonid-Efremov
ALTER TABLE golden_rename_table.t_before RENAME TO t_after;
