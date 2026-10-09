CREATE TABLE iceberg_catalog.trino_harness.partitioned_table_t (
id INT,
ts TIMESTAMP(6)
)
WITH (
format = 'PARQUET',
partitioning = ARRAY['day(ts)']
)