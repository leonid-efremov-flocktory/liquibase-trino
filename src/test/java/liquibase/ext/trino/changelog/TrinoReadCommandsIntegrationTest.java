package liquibase.ext.trino.changelog;

import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeLogHistoryService;
import liquibase.changelog.ChangeSetStatus;
import liquibase.changelog.RanChangeSet;
import liquibase.database.Database;
import liquibase.ext.trino.TrinoTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Liquibase commands that read the tracking table back rather than write to it:
 * {@code status}, {@code history} and {@code tag}.
 * <p>
 * Milestone 1 of the "add a database" guide lists these as things that must work once
 * {@code update} does. All of them funnel through
 * {@link liquibase.ext.trino.sqlgenerator.TrinoSelectFromDatabaseChangeLogGenerator}, the
 * plugin's only SQL generator for reads, so a defect in it surfaces here as wrong output
 * rather than as an exception — which is why these assert on values, not on "did not throw".
 * <p>
 * The fixture is applied once in {@link #setup()} and only read from here. {@code tag} is
 * the exception: it writes a tag column, which {@link #tearDown()} clears.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TrinoReadCommandsIntegrationTest {

    private static final String TAG = "release-1";

    private static Database db;
    private static Liquibase liquibase;

    @BeforeAll
    static void setup() throws Exception {
        assumeTrue(TrinoTestSupport.isReachable(), "Trino is unreachable: " + TrinoTestSupport.url());
        TrinoTestSupport.applyIfNeeded();
        db = TrinoTestSupport.openChangelogDatabase();
        liquibase = TrinoTestSupport.liquibase(db);
    }

    @AfterAll
    static void tearDown() throws Exception {
        // The guard repeats the assumeTrue in setup(): a failed assumption skips @AfterAll in
        // JUnit, so without it cleanup would fail instead of skipping.
        assumeTrue(TrinoTestSupport.isReachable(), "Trino is unreachable: " + TrinoTestSupport.url());
        // tag() wrote a tag column; clearing it keeps the shared fixture in the state the
        // other classes expect. Plain SQL, since liquibase.tag(null) is rejected.
        TrinoTestSupport.execute("UPDATE " + TrinoTestSupport.CHANGELOG_SCHEMA
                + ".databasechangelog SET tag = NULL WHERE tag = '" + TAG + "'");
    }

    // --- status ---

    @Test
    void statusReportsNothingPendingAfterUpdate() throws Exception {
        StringWriter output = new StringWriter();
        liquibase.reportStatus(true, TrinoTestSupport.CONTEXT, output);

        String status = output.toString();
        assertTrue(status.contains("is up to date"),
                "status must report an up-to-date database after update: " + status);
    }

    @Test
    void statusKnowsEveryChangesetAlreadyRan() throws Exception {
        List<ChangeSetStatus> statuses =
                liquibase.getChangeSetStatuses(TrinoTestSupport.CONTEXT, new LabelExpression());

        assertEquals(TrinoTestSupport.APPLIED_CHANGESETS, idsOf(statuses),
                "status must see exactly the fixture's changesets");
        for (ChangeSetStatus status : statuses) {
            String id = status.getChangeSet().getId();
            assertTrue(status.getPreviouslyRan(), "changeset " + id + " must be seen as already run");
            assertFalse(status.getWillRun(), "changeset " + id + " must not be pending after update");
            // A null stored checksum would mean status could not read the row back, which is
            // exactly what the read generator is supposed to make possible.
            assertNotNull(status.getStoredCheckSum(), "status must read a stored checksum for " + id);
        }
    }

    @Test
    void listUnrunChangeSetsIsEmptyAfterUpdate() throws Exception {
        assertEquals(List.of(),
                liquibase.listUnrunChangeSets(TrinoTestSupport.CONTEXT, new LabelExpression()),
                "nothing may be pending once the whole fixture is applied");
    }

    // --- history ---

    @Test
    void historyReadsChangesetsBackInExecutionOrder() throws Exception {
        ChangeLogHistoryService history = TrinoTestSupport.historyService(db);
        List<RanChangeSet> ran = history.getRanChangeSets();

        assertEquals(TrinoTestSupport.APPLIED_CHANGESETS, ranIds(ran),
                "history must return the applied changesets in execution order");

        List<Integer> orders = new ArrayList<>();
        for (RanChangeSet changeSet : ran) {
            orders.add(changeSet.getOrderExecuted());
            assertNotNull(changeSet.getDateExecuted(),
                    "history must read a timestamp for " + changeSet.getId());
        }
        assertEquals(List.of(1, 2, 3), orders,
                "history must expose orderexecuted so a rollback could target a changeset");
    }

    // --- tag / tagExists ---

    /**
     * Runs before {@link #tagLeavesEarlierChangesetsUntagged()}: that test asserts the tag is
     * present, so the two cannot be reordered freely.
     */
    @Test
    @Order(1)
    void tagIsRecordedAndFoundAgain() throws Exception {
        assertFalse(liquibase.tagExists(TAG), "the tag must not exist before it is written");

        liquibase.tag(TAG);

        assertTrue(liquibase.tagExists(TAG), "tagExists must find the tag just written");
        // Read back through a tagged SELECT, not merely stored: this is the ByTag where-clause
        // path of the read generator.
        assertEquals(List.of("v2-extend-test-table-and-view"), taggedChangesetIds(),
                "the tag must land on the last applied changeset and be found by the WHERE clause");
    }

    @Test
    @Order(2)
    void tagLeavesEarlierChangesetsUntagged() throws Exception {
        assertEquals(List.of("common-schema-setup", "v1-test-table-and-view"), untaggedChangesetIds(),
                "tag must only touch the changeset it was applied to, not rewrite the tag column");
    }

    private static List<String> taggedChangesetIds() throws Exception {
        return TrinoTestSupport.queryFirstColumn("SELECT id FROM "
                + TrinoTestSupport.CHANGELOG_SCHEMA + ".databasechangelog WHERE tag = '" + TAG + "'");
    }

    private static List<String> untaggedChangesetIds() throws Exception {
        // tag IS NULL, not tag = '': a tag column written as an empty string would satisfy the
        // equality check and hide the difference between "no tag" and "empty tag".
        return TrinoTestSupport.queryFirstColumn("SELECT id FROM "
                + TrinoTestSupport.CHANGELOG_SCHEMA + ".databasechangelog WHERE tag IS NULL"
                + " ORDER BY orderexecuted");
    }

    private static List<String> idsOf(List<ChangeSetStatus> statuses) {
        List<String> ids = new ArrayList<>();
        for (ChangeSetStatus status : statuses) {
            ids.add(status.getChangeSet().getId());
        }
        return ids;
    }

    private static List<String> ranIds(List<RanChangeSet> ran) {
        List<String> ids = new ArrayList<>();
        for (RanChangeSet changeSet : ran) {
            ids.add(changeSet.getId());
        }
        return ids;
    }
}