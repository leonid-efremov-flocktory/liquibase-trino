package liquibase.ext.trino.changelog;

import liquibase.database.Database;
import liquibase.ext.trino.TrinoTestSupport;
import liquibase.snapshot.SnapshotGeneratorFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A full Liquibase {@code update} run against a live Trino.
 * <p>
 * The stand is Iceberg, not the memory connector: Liquibase's tracking tables need
 * DELETE/UPDATE, which memory does not support. This is the one class that starts from
 * scratch — it drops everything and applies the fixture in {@link #setup()}, so it does not
 * depend on what ran before it. Direct queries are used purely for verification.
 */
@Tag("integration")
@EnabledIf("liquibase.ext.trino.TrinoTestSupport#isReachable")
class TrinoChangeLogUpdateIntegrationTest {

    private static Database db;

    @BeforeAll
    static void setup() throws Exception {
        TrinoTestSupport.dropAll();
        db = TrinoTestSupport.openChangelogDatabase();
        TrinoTestSupport.update(db);
    }

    // The tracking tables stay on purpose: the classes that read them call applyIfNeeded(),
    // which finds the fixture applied and does nothing. Restoring it is not this class's job,
    // and applying it here as well would run the same changesets twice per suite.

    @Test
    void updateAppliesAllChangesetsAndCreatesTrackingTables() throws Exception {
        assertTrue(SnapshotGeneratorFactory.getInstance().hasDatabaseChangeLogTable(db),
                "DATABASECHANGELOG must exist after the update");
        assertTrue(SnapshotGeneratorFactory.getInstance().hasDatabaseChangeLogLockTable(db),
                "DATABASECHANGELOGLOCK must exist after the update");
        assertTrue(TrinoTestSupport.trackingTablesExist(),
                "both tracking tables must be visible in information_schema");
    }

    @Test
    void updateRecordsEveryChangeset() throws Exception {
        assertEquals(TrinoTestSupport.APPLIED_CHANGESETS, TrinoTestSupport.appliedChangesetIds(),
                "DATABASECHANGELOG must hold a row for all three fixture changesets, "
                        + "in execution order");
    }

    @Test
    void changelogRowsCarryExecutionMetadata() throws Exception {
        // Not just that a row was written, but what: a broken EXECTYPE or a shifted
        // ORDEREXECUTED would make rollback later pick the wrong changeset.
        assertEquals(List.of(
                "common-schema-setup|1|EXECUTED|liquibase/ext/trino/test-changelog.xml",
                "v1-test-table-and-view|2|EXECUTED|liquibase/ext/trino/v1-test-table-view.sql",
                "v2-extend-test-table-and-view|3|EXECUTED|liquibase/ext/trino/v2-test-table-view-extend.sql"),
                TrinoTestSupport.changelogRows(),
                "every DATABASECHANGELOG row must carry its own orderexecuted, "
                        + "EXECTYPE and source file name");
    }

    @Test
    void updateReleasesChangeLogLock() throws Exception {
        // The lock row must remain but be released: a stuck lock would only show up on the
        // next update, i.e. after the test run.
        assertEquals(List.of("false|"),
                TrinoTestSupport.lockState(),
                "DATABASECHANGELOGLOCK must remain with LOCKED = false and an empty LOCKEDBY");
    }

    @Test
    void updateCreatesTableWithDataAndViewOverIt() throws Exception {
        // Iceberg gives no row order guarantee, hence the explicit ORDER BY.
        List<String> ids = TrinoTestSupport.queryFirstColumn(
                "SELECT id FROM " + TrinoTestSupport.FIXTURE_TABLE + " ORDER BY id");
        assertEquals(List.of("1", "2", "3", "4", "5"), ids,
                "v1 adds 3 rows, v2 adds 2 more");

        assertEquals("5", TrinoTestSupport.count(TrinoTestSupport.FIXTURE_TABLE));
        assertEquals("4", TrinoTestSupport.count(TrinoTestSupport.FIXTURE_VIEW),
                "v2 narrows the view to id > 1, so the view returns 4 of the 5 rows");

        assertEquals("первая строка",
                TrinoTestSupport.queryFirstColumn(
                        "SELECT txt FROM " + TrinoTestSupport.FIXTURE_TABLE + " WHERE id = 1").get(0));
        assertEquals("2024-01-15 10:00:00.000000",
                TrinoTestSupport.queryFirstColumn(
                        "SELECT ts FROM " + TrinoTestSupport.FIXTURE_TABLE + " WHERE id = 1").get(0),
                "the ts column must keep the timestamp from the INSERT");
    }

    @Test
    void repeatedUpdateDoesNotDuplicateData() throws Exception {
        TrinoTestSupport.update(db);

        assertEquals("5", TrinoTestSupport.count(TrinoTestSupport.FIXTURE_TABLE),
                "a repeated update must not duplicate data");
        assertEquals(TrinoTestSupport.FIXTURE_CHANGESETS, TrinoTestSupport.changelogRows().size(),
                "a repeated update must not add rows to DATABASECHANGELOG");
    }
}