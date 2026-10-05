DROP VIEW IF EXISTS ${catalog.default}.${schema.test_schema}.sqlfile_view;

DROP TABLE IF EXISTS ${catalog.default}.${schema.test_schema}.sqlfile_table;

CREATE TABLE ${catalog.default}.${schema.test_schema}.sqlfile_table (
  id INT
, txt VARCHAR
);

INSERT INTO ${catalog.default}.${schema.test_schema}.sqlfile_table (id, txt) VALUES
  (10, 'из sqlFile')
, (11, 'ещё из sqlFile')
;

CREATE VIEW ${catalog.default}.${schema.test_schema}.sqlfile_view AS
SELECT
  id
, txt
FROM
  ${catalog.default}.${schema.test_schema}.sqlfile_table;