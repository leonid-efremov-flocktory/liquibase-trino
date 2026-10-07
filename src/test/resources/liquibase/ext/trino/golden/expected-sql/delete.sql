-- Changeset liquibase/ext/trino/golden/changelogs/delete.xml::create-schema::Leonid-Efremov
CREATE SCHEMA IF NOT EXISTS iceberg_catalog.golden_delete;

-- Changeset liquibase/ext/trino/golden/changelogs/delete.xml::create-table::Leonid-Efremov
CREATE TABLE golden_delete.t (id INT, txt VARCHAR(64));

-- Changeset liquibase/ext/trino/golden/changelogs/delete.xml::seed-rows::Leonid-Efremov
INSERT INTO golden_delete.t (id, txt) VALUES (1, 'остаётся');
INSERT INTO golden_delete.t (id, txt) VALUES (2, 'удаляется');

-- Changeset liquibase/ext/trino/golden/changelogs/delete.xml::delete-row::Leonid-Efremov
DELETE FROM golden_delete.t WHERE id = 2;
