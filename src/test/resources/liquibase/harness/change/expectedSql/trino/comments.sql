CREATE TABLE iceberg_catalog.trino_harness.comments_t (
id INT,
txt VARCHAR(64)
) WITH (format = 'PARQUET')
COMMENT ON TABLE iceberg_catalog.trino_harness.comments_t IS 'таблица с комментарием'
COMMENT ON COLUMN iceberg_catalog.trino_harness.comments_t.txt IS 'колонка с комментарием'