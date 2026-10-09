CREATE TABLE iceberg_catalog.trino_harness.sql_file_t (
id INT,
txt VARCHAR(64)
) WITH (format = 'PARQUET')