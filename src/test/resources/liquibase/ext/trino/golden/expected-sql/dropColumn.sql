-- Changeset liquibase/ext/trino/golden/changelogs/dropColumn.xml::create-schema::Leonid-Efremov
CREATE SCHEMA IF NOT EXISTS iceberg_catalog.golden_drop_column;

-- Changeset liquibase/ext/trino/golden/changelogs/dropColumn.xml::create-table::Leonid-Efremov
CREATE TABLE golden_drop_column.t (id INT, txt VARCHAR(64));

-- Changeset liquibase/ext/trino/golden/changelogs/dropColumn.xml::drop-column::Leonid-Efremov
ALTER TABLE golden_drop_column.t DROP COLUMN txt;
