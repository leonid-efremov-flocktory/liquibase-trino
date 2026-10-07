-- Changeset liquibase/ext/trino/golden/changelogs/dropTable.xml::create-schema::Leonid-Efremov
CREATE SCHEMA IF NOT EXISTS iceberg_catalog.golden_drop_table;

-- Changeset liquibase/ext/trino/golden/changelogs/dropTable.xml::create-table::Leonid-Efremov
CREATE TABLE golden_drop_table.t (id INT);

-- Changeset liquibase/ext/trino/golden/changelogs/dropTable.xml::drop-table::Leonid-Efremov
DROP TABLE golden_drop_table.t;
