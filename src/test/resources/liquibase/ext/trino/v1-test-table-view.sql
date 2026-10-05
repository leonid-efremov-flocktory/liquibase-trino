-- liquibase formatted sql

-- changeset Leonid-Efremov:v1-test-table-and-view
-- comment: Create test_table with data and test_view over it

DROP VIEW IF EXISTS ${catalog.default}.${schema.test_schema}.test_view;

DROP TABLE IF EXISTS ${catalog.default}.${schema.test_schema}.test_table;

CREATE TABLE ${catalog.default}.${schema.test_schema}.test_table (
  id INT
, txt VARCHAR
, ts TIMESTAMP
);

INSERT INTO ${catalog.default}.${schema.test_schema}.test_table (id, txt, ts) VALUES
  (1, 'первая строка', TIMESTAMP '2024-01-15 10:00:00')
, (2, 'вторая строка', TIMESTAMP '2024-02-20 12:30:45')
, (3, 'третья строка', TIMESTAMP '2024-03-25 23:59:59')
;

CREATE VIEW ${catalog.default}.${schema.test_schema}.test_view COMMENT 'Тестовая вью' SECURITY DEFINER AS
SELECT
  id
, txt
, ts
FROM
  ${catalog.default}.${schema.test_schema}.test_table;

-- rollback DROP VIEW IF EXISTS ${catalog.default}.${schema.test_schema}.test_view; DROP TABLE IF EXISTS ${catalog.default}.${schema.test_schema}.test_table;