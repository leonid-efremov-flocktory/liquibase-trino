-- Changeset liquibase/ext/trino/golden/changelogs/createView.xml::create-schema::Leonid-Efremov
CREATE SCHEMA IF NOT EXISTS iceberg_catalog.golden_create_view;

-- Changeset liquibase/ext/trino/golden/changelogs/createView.xml::create-table::Leonid-Efremov
CREATE TABLE golden_create_view.t (id INT, txt VARCHAR(64));

-- Changeset liquibase/ext/trino/golden/changelogs/createView.xml::create-view::Leonid-Efremov
CREATE VIEW golden_create_view.v AS SELECT id, txt FROM iceberg_catalog.golden_create_view.t;
