CREATE TABLE trino_harness.drop_view_t (id INT, txt VARCHAR(64))
CREATE VIEW trino_harness.drop_view_v AS SELECT id, txt FROM iceberg_catalog.trino_harness.drop_view_t
DROP VIEW trino_harness.drop_view_v