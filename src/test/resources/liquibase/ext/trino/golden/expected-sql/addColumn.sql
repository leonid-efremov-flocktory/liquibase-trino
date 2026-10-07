-- Changeset liquibase/ext/trino/golden/changelogs/addColumn.xml::create-schema::Leonid-Efremov
CREATE SCHEMA IF NOT EXISTS iceberg_catalog.golden_add_column;

-- Changeset liquibase/ext/trino/golden/changelogs/addColumn.xml::create-table::Leonid-Efremov
CREATE TABLE golden_add_column.t (id INT);

-- Changeset liquibase/ext/trino/golden/changelogs/addColumn.xml::add-column::Leonid-Efremov
ALTER TABLE golden_add_column.t ADD COLUMN txt VARCHAR(64);
