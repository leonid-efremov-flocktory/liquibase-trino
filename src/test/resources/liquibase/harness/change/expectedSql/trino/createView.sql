CREATE TABLE trino_harness.create_view (id INT, txt VARCHAR(64))
CREATE VIEW trino_harness.create_view_v AS SELECT id, txt FROM iceberg_catalog.trino_harness.create_view