-- Changeset liquibase/ext/trino/golden/changelogs/dropView.xml::create-schema::Leonid-Efremov
CREATE SCHEMA IF NOT EXISTS iceberg_catalog.golden_drop_view;

-- Changeset liquibase/ext/trino/golden/changelogs/dropView.xml::create-table::Leonid-Efremov
CREATE TABLE golden_drop_view.t (id INT, txt VARCHAR(64));

-- Changeset liquibase/ext/trino/golden/changelogs/dropView.xml::create-view::Leonid-Efremov
CREATE VIEW golden_drop_view.v AS SELECT id, txt FROM iceberg_catalog.golden_drop_view.t;

-- Changeset liquibase/ext/trino/golden/changelogs/dropView.xml::drop-view::Leonid-Efremov
DROP VIEW golden_drop_view.v;
