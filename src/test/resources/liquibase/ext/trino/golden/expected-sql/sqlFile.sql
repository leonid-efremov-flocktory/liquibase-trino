-- Changeset liquibase/ext/trino/golden/changelogs/sqlFile.xml::create-schema::Leonid-Efremov
CREATE SCHEMA IF NOT EXISTS iceberg_catalog.golden_sql_file;

-- Changeset liquibase/ext/trino/golden/changelogs/sqlFile.xml::sql-file::Leonid-Efremov
-- Body for the sqlFile golden case. Sits next to the changelog, so sqlFile resolves it
-- relative to the changelog file rather than from the classpath root.
CREATE TABLE iceberg_catalog.golden_sql_file.t (
    id INT,
    txt VARCHAR(64)
) WITH (format = 'PARQUET');
