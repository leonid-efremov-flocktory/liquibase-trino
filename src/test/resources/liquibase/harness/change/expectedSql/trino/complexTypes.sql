CREATE TABLE iceberg_catalog.trino_harness.complex_types_t (
id INT,
tags ARRAY(VARCHAR),
payload ROW(a INT, b VARCHAR)
) WITH (format = 'PARQUET')