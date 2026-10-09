-- Body for the sqlFile change case. Sits next to the changelog, so <sqlFile> resolves it
-- relative to the changelog file rather than from the classpath root.
CREATE TABLE iceberg_catalog.trino_harness.sql_file_t (
    id INT,
    txt VARCHAR(64)
) WITH (format = 'PARQUET');
