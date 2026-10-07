-- Changeset liquibase/ext/trino/golden/changelogs/comments.xml::create-schema::Leonid-Efremov
CREATE SCHEMA IF NOT EXISTS iceberg_catalog.golden_comments;

-- Changeset liquibase/ext/trino/golden/changelogs/comments.xml::commented-table::Leonid-Efremov
CREATE TABLE iceberg_catalog.golden_comments.t (
                id INT,
                txt VARCHAR(64)
            ) WITH (format = 'PARQUET');
COMMENT ON TABLE iceberg_catalog.golden_comments.t IS 'таблица с комментарием';
COMMENT ON COLUMN iceberg_catalog.golden_comments.t.txt IS 'колонка с комментарием';
