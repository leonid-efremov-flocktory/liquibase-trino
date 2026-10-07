-- Changeset liquibase/ext/trino/golden/changelogs/sql.xml::create-schema::Leonid-Efremov
CREATE SCHEMA IF NOT EXISTS iceberg_catalog.golden_sql;

-- Changeset liquibase/ext/trino/golden/changelogs/sql.xml::raw-sql::Leonid-Efremov
CREATE TABLE iceberg_catalog.golden_sql.t (id INT, txt VARCHAR(64));
