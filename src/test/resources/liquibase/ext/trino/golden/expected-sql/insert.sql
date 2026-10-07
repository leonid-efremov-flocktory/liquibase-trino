-- Changeset liquibase/ext/trino/golden/changelogs/insert.xml::create-schema::Leonid-Efremov
CREATE SCHEMA IF NOT EXISTS iceberg_catalog.golden_insert;

-- Changeset liquibase/ext/trino/golden/changelogs/insert.xml::create-table::Leonid-Efremov
CREATE TABLE golden_insert.t (id INT, txt VARCHAR(64));

-- Changeset liquibase/ext/trino/golden/changelogs/insert.xml::insert-rows::Leonid-Efremov
INSERT INTO golden_insert.t (id, txt) VALUES (1, 'из insert');
INSERT INTO golden_insert.t (id, txt) VALUES (2, 'ещё из insert');
