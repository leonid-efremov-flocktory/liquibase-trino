package liquibase.ext.trino.changelog

import liquibase.database.Database
import liquibase.ext.trino.TrinoIntegrationSpec
import liquibase.ext.trino.TrinoTestSupport
import liquibase.snapshot.SnapshotGeneratorFactory
import spock.lang.Shared

/**
 * A full Liquibase {@code update} run against a live Trino.
 *
 * <p>The stand is Iceberg, not the memory connector: Liquibase's tracking tables need
 * DELETE/UPDATE, which memory does not support. This is the one class that starts from
 * scratch — it drops everything and applies the fixture in {@code setupSpec()}, so it does not
 * depend on what ran before it. Direct queries are used purely for verification.
 */
class TrinoChangeLogUpdateSpec extends TrinoIntegrationSpec {

    @Shared
    Database db

    def setupSpec() {
        TrinoTestSupport.dropAll()
        db = TrinoTestSupport.openChangelogDatabase()
        TrinoTestSupport.update(db)
    }

    // The tracking tables stay on purpose: the classes that read them call applyIfNeeded(),
    // which finds the fixture applied and does nothing. Restoring it is not this class's job,
    // and applying it here as well would run the same changesets twice per suite.

    def "update applies all changesets and creates tracking tables"() {
        expect:
        SnapshotGeneratorFactory.getInstance().hasDatabaseChangeLogTable(db)
        SnapshotGeneratorFactory.getInstance().hasDatabaseChangeLogLockTable(db)
        TrinoTestSupport.trackingTablesExist()
    }

    def "update records every changeset"() {
        expect:
        TrinoTestSupport.appliedChangesetIds() == TrinoTestSupport.APPLIED_CHANGESETS
    }

    def "changelog rows carry execution metadata"() {
        // Not just that a row was written, but what: a broken EXECTYPE or a shifted
        // ORDEREXECUTED would make rollback later pick the wrong changeset.
        expect:
        TrinoTestSupport.changelogRows() == [
                "common-schema-setup|1|EXECUTED|liquibase/ext/trino/test-changelog.xml",
                "v1-test-table-and-view|2|EXECUTED|liquibase/ext/trino/v1-test-table-view.sql",
                "v2-extend-test-table-and-view|3|EXECUTED|liquibase/ext/trino/v2-test-table-view-extend.sql"]
    }

    def "update releases the change log lock"() {
        // The lock row must remain but be released: a stuck lock would only show up on the
        // next update, i.e. after the test run.
        expect:
        TrinoTestSupport.lockState() == ["false|"]
    }

    def "update creates the table with data and the view over it"() {
        expect:
        // Iceberg gives no row order guarantee, hence the explicit ORDER BY.
        TrinoTestSupport.queryFirstColumn("SELECT id FROM " + TrinoTestSupport.FIXTURE_TABLE + " ORDER BY id")
                == ["1", "2", "3", "4", "5"]

        and: "v1 adds 3 rows, v2 adds 2 more"
        TrinoTestSupport.count(TrinoTestSupport.FIXTURE_TABLE) == "5"
        TrinoTestSupport.count(TrinoTestSupport.FIXTURE_VIEW) == "4"

        and: "the row values and the timestamp survive the round trip"
        TrinoTestSupport.queryFirstColumn(
                "SELECT txt FROM " + TrinoTestSupport.FIXTURE_TABLE + " WHERE id = 1")[0] == "первая строка"
        TrinoTestSupport.queryFirstColumn(
                "SELECT ts FROM " + TrinoTestSupport.FIXTURE_TABLE + " WHERE id = 1")[0]
                == "2024-01-15 10:00:00.000000"
    }

    def "a repeated update does not duplicate data"() {
        when:
        TrinoTestSupport.update(db)

        then:
        TrinoTestSupport.count(TrinoTestSupport.FIXTURE_TABLE) == "5"
        TrinoTestSupport.changelogRows().size() == TrinoTestSupport.FIXTURE_CHANGESETS
    }
}
