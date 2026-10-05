-- liquibase formatted sql

-- changeset Leonid-Efremov:v2-extend-test-table-and-view
-- comment: Add rows to test_table and redefine test_view

INSERT INTO ${catalog.default}.${schema.test_schema}.test_table (id, txt, ts) VALUES
  (4, 'четвёртая строка', TIMESTAMP '2024-04-30 08:15:00')
, (5, 'пятая строка', TIMESTAMP '2024-05-05 18:45:30')
;

CREATE OR REPLACE VIEW ${catalog.default}.${schema.test_schema}.test_view COMMENT 'Тестовая вью (v2)' SECURITY DEFINER AS
SELECT
  id
, txt
, ts
FROM
  ${catalog.default}.${schema.test_schema}.test_table
WHERE id > 1;

-- rollback CREATE OR REPLACE VIEW ${catalog.default}.${schema.test_schema}.test_view COMMENT 'Тестовая вью' SECURITY DEFINER AS SELECT id, txt, ts FROM ${catalog.default}.${schema.test_schema}.test_table; DELETE FROM ${catalog.default}.${schema.test_schema}.test_table WHERE id IN (4, 5);