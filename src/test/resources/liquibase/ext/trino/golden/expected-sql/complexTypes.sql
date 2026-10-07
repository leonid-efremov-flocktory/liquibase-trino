-- Changeset liquibase/ext/trino/golden/changelogs/complexTypes.xml::create-schema::Leonid-Efremov
CREATE SCHEMA IF NOT EXISTS iceberg_catalog.golden_complex_types;

-- Changeset liquibase/ext/trino/golden/changelogs/complexTypes.xml::complex-typed-table::Leonid-Efremov
CREATE TABLE iceberg_catalog.golden_complex_types.t (
                id INT,
                tags ARRAY(VARCHAR),
                payload ROW(a INT, b VARCHAR)
            ) WITH (format = 'PARQUET');
