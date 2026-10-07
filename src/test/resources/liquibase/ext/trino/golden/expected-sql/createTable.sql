-- Changeset liquibase/ext/trino/golden/changelogs/createTable.xml::create-schema::Leonid-Efremov
CREATE SCHEMA IF NOT EXISTS iceberg_catalog.golden_create_table;

-- Changeset liquibase/ext/trino/golden/changelogs/createTable.xml::create-table::Leonid-Efremov
CREATE TABLE golden_create_table.t (id INT, txt VARCHAR(64));
