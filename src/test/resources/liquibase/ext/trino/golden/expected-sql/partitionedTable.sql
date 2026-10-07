-- Changeset liquibase/ext/trino/golden/changelogs/partitionedTable.xml::create-schema::Leonid-Efremov
CREATE SCHEMA IF NOT EXISTS iceberg_catalog.golden_partitioned;

-- Changeset liquibase/ext/trino/golden/changelogs/partitionedTable.xml::partitioned-table::Leonid-Efremov
CREATE TABLE iceberg_catalog.golden_partitioned.t (
                id INT,
                ts TIMESTAMP(6)
            )
            WITH (
                format = 'PARQUET',
                partitioning = ARRAY['day(ts)']
            );
