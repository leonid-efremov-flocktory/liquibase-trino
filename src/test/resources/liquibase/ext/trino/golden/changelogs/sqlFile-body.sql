-- Body for the sqlFile golden case. Sits next to the changelog, so sqlFile resolves it
-- relative to the changelog file rather than from the classpath root.
CREATE TABLE iceberg_catalog.golden_sql_file.t (
    id INT,
    txt VARCHAR(64)
) WITH (format = 'PARQUET');
