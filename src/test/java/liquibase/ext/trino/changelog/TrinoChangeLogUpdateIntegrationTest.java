package liquibase.ext.trino.changelog;

import liquibase.database.Database;
import liquibase.ext.trino.TrinoTestSupport;
import liquibase.snapshot.SnapshotGeneratorFactory;
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
                "Таблица DATABASECHANGELOG должна существовать после update");
        assertTrue(SnapshotGeneratorFactory.getInstance().hasDatabaseChangeLogLockTable(db),
                "Таблица DATABASECHANGELOGLOCK должна существовать после update");
        assertTrue(TrinoTestSupport.trackingTablesExist(),
                "Обе tracking-таблицы должны быть видны в information_schema");
    }

    @Test
    void updateRecordsEveryChangeset() throws Exception {
        assertEquals(TrinoTestSupport.APPLIED_CHANGESETS, TrinoTestSupport.appliedChangesetIds(),
                "В DATABASECHANGELOG должны быть записи о всех трёх changeset'ах фикстуры "
                        + "в порядке выполнения");
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
                "Каждая запись DATABASECHANGELOG должна хранить свой orderexecuted, "
                        + "EXECTYPE и имя файла-источника");
    }

    @Test
    void updateReleasesChangeLogLock() throws Exception {
        // The lock row must remain but be released: a stuck lock would only show up on the
        // next update, i.e. after the test run.
        assertEquals(List.of("false|"),
                TrinoTestSupport.lockState(),
                "DATABASECHANGELOGLOCK должна остаться с LOCKED = false и пустым LOCKEDBY");
    }

    @Test
    void updateCreatesTableWithDataAndViewOverIt() throws Exception {
        // Iceberg gives no row order guarantee, hence the explicit ORDER BY.
        List<String> ids = TrinoTestSupport.queryFirstColumn(
                "SELECT id FROM " + TrinoTestSupport.FIXTURE_TABLE + " ORDER BY id");
        assertEquals(List.of("1", "2", "3", "4", "5"), ids,
                "v1 добавляет 3 строки, v2 ещё 2");

        assertEquals("5", TrinoTestSupport.count(TrinoTestSupport.FIXTURE_TABLE));
        assertEquals("4", TrinoTestSupport.count(TrinoTestSupport.FIXTURE_VIEW),
                "v2 сужает вью условием id > 1, поэтому вью отдаёт 4 строки из 5");

        assertEquals("первая строка",
                TrinoTestSupport.queryFirstColumn(
                        "SELECT txt FROM " + TrinoTestSupport.FIXTURE_TABLE + " WHERE id = 1").get(0));
        assertEquals("2024-01-15 10:00:00.000000",
                TrinoTestSupport.queryFirstColumn(
                        "SELECT ts FROM " + TrinoTestSupport.FIXTURE_TABLE + " WHERE id = 1").get(0),
                "Колонка ts должна сохранить timestamp из INSERT");
    }

    @Test
    void repeatedUpdateDoesNotDuplicateData() throws Exception {
        TrinoTestSupport.update(db);

        assertEquals("5", TrinoTestSupport.count(TrinoTestSupport.FIXTURE_TABLE),
                "Повторный update не должен дублировать данные");
        assertEquals(TrinoTestSupport.FIXTURE_CHANGESETS, TrinoTestSupport.changelogRows().size(),
                "Повторный update не должен добавлять строки в DATABASECHANGELOG");
    }
}