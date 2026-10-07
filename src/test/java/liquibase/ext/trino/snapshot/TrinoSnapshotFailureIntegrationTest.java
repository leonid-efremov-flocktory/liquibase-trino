package liquibase.ext.trino.snapshot;

import liquibase.command.CommandScope;
import liquibase.command.core.GenerateChangelogCommandStep;
import liquibase.command.core.SnapshotCommandStep;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.ext.trino.TrinoTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One unreadable object must not take the whole command down.
 *
 * <p>The failure mode this guards against is the expensive one. {@code SnapshotGeneratorChain} does
 * not isolate generators per object, so anything a generator throws escapes to the command: a
 * {@code generate-changelog} that dies on one bad table of two hundred produces nothing at all, and
 * the user is left with no changelog and no indication of which object was responsible.
 *
 * <p>What survives is now uniform: an object whose verbatim statement cannot be read is left out of
 * the snapshot, whether it is a table or a view. That holds for both because
 * {@link TrinoDdlFetcher#attachVerbatimDdl} is the only place that decides, and it decides for
 * both. Getting there took two steps, and the intermediate one is worth recording because it is
 * what the asymmetry above would have produced:
 * <ul>
 *   <li>a <b>view</b> is dropped by the generator's guard on {@code super.snapshotObject}, since
 *       {@code TrinoDatabase.getViewDefinition} is a read the plugin owns and it throws;
 *   <li>a <b>table</b> is already in the snapshot by then, found through cached metadata, so that
 *       guard cannot reach it. It used to be kept without its statement, and
 *       {@code TrinoDdlChangeGenerator} then fell back to core, emitting a structural
 *       {@code CREATE TABLE} with no {@code WITH (...)}.
 * </ul>
 * The fallback was not a degraded copy of the object but a different one — see
 * {@link #anUnreadableTableIsAbsentFromTheGeneratedChangelog()} — so both object types are now
 * treated the same way.
 *
 * <p>The failure is injected through {@link FailingTrinoDatabase} rather than produced by a broken
 * server: the read that fails is the same read either way, and the plugin cannot tell them apart.
 *
 * <p>These tests drive the commands through {@link CommandScope} with a pre-built Database rather
 * than a URL. {@code DbUrlConnectionArgumentsCommandStep} declares a hidden {@code database} argument
 * that supersedes {@code url}, so the command uses the injected instance instead of building its own —
 * which is what makes the failure controllable at all.
 */
@EnabledIf("liquibase.ext.trino.TrinoTestSupport#isReachable")
class TrinoSnapshotFailureIntegrationTest {

    private static final String SCHEMA = "snapshot_failure_rt";
    private static final String GOOD_TABLE = "good_table";
    private static final String BROKEN_TABLE = "broken_table";
    private static final String GOOD_VIEW = "good_view";
    private static final String BROKEN_VIEW = "broken_view";

    private static String catalog;
    private static String qualifiedSchema;

    @BeforeAll
    static void setup() throws Exception {
        TrinoTestSupport.applyIfNeeded();

        try (Database db = TrinoTestSupport.openDatabase()) {
            catalog = db.getDefaultCatalogName();
        }
        qualifiedSchema = catalog + "." + SCHEMA;

        TrinoTestSupport.execute("DROP SCHEMA IF EXISTS " + qualifiedSchema + " CASCADE");
        TrinoTestSupport.execute("CREATE SCHEMA " + qualifiedSchema);
        TrinoTestSupport.execute("CREATE TABLE " + qualifiedSchema + "." + GOOD_TABLE + " (id integer)");
        TrinoTestSupport.execute("CREATE TABLE " + qualifiedSchema + "." + BROKEN_TABLE + " (id integer)");
        TrinoTestSupport.execute("CREATE VIEW " + qualifiedSchema + "." + GOOD_VIEW
                + " AS SELECT id FROM " + qualifiedSchema + "." + GOOD_TABLE);
        TrinoTestSupport.execute("CREATE VIEW " + qualifiedSchema + "." + BROKEN_VIEW
                + " AS SELECT id FROM " + qualifiedSchema + "." + BROKEN_TABLE);
    }

    // --- snapshot ---

    /**
     * The command has to come back with a document, and the document has to cover the objects that
     * could be read. Before the guard this threw and produced nothing at all.
     */
    @Test
    void snapshotSurvivesAnUnreadableTable() throws Exception {
        String json = snapshotJson(FailingTrinoDatabase.on(BROKEN_TABLE));

        assertTrue(json.contains("\"" + GOOD_TABLE + "\""),
                "the readable table must still be snapshotted:\n" + json);
    }

/**
     * A table whose verbatim DDL cannot be read is dropped, not kept with degraded metadata.
     *
     * <p>This is the decision that changed. A table is found through cached metadata before its DDL
     * is read, so the generator <i>can</i> keep it — and core would then emit a structural
     * {@code CREATE TABLE} with no {@code WITH (...)}. For a table this plugin cannot describe that
     * is not a degraded copy but a different object: a Trino catalog over a shared metastore
     * routinely holds tables of another connector, and a leftover Hive table needs
     * {@code external_location}, {@code format} and {@code serde}, none of which have a column in
     * {@code information_schema}. So an unreadable table is treated exactly like an unreadable view.
     */
    @Test
    void anUnreadableTableIsDroppedFromTheSnapshot() throws Exception {
        String json = snapshotJson(FailingTrinoDatabase.on(BROKEN_TABLE));

        assertFalse(json.contains("\"" + BROKEN_TABLE + "\""),
                "without its DDL the table cannot be reproduced, so it must be left out:\n" + json);
        assertTrue(json.contains("\"" + GOOD_TABLE + "\""),
                "only the unreadable table is dropped:\n" + json);
    }

    /**
     * A view is a different case: its body comes from a read the generator controls, so a failure
     * there takes the whole object out. A fix that only guarded tables would leave the view in the
     * snapshot, broken.
     */
    @Test
    void snapshotDropsAnUnreadableViewAndKeepsTheRest() throws Exception {
        String json = snapshotJson(FailingTrinoDatabase.on(BROKEN_VIEW));

        assertTrue(json.contains("\"" + GOOD_VIEW + "\""),
                "the readable view must still be snapshotted:\n" + json);
        assertFalse(json.contains("\"" + BROKEN_VIEW + "\""),
                "a view whose definition cannot be read must be left out:\n" + json);
    }

    /** Both broken at once: state left by the first skip must not poison the second. */
    @Test
    void snapshotSurvivesSeveralUnreadableObjects() throws Exception {
        String json = snapshotJson(FailingTrinoDatabase.on(FailingTrinoDatabase.ANY_OBJECT));

        assertTrue(json.contains("\"" + SCHEMA + "\""),
                "the schema comes from a different generator and must survive:\n" + json);
        assertFalse(json.contains("\"" + GOOD_TABLE + "\""),
                "a table without verbatim DDL cannot be reproduced, so it is dropped as well:\n" + json);
        assertFalse(json.contains("\"" + GOOD_VIEW + "\""),
                "with every DDL read failing, no view survives:\n" + json);
    }

    // --- generate-changelog ---

/**
 * The reason this matters: {@code generate-changelog} is how a user bootstraps a changelog for an
 * existing database, so one unreadable object previously meant no changelog at all.
 *
 * <p>With every DDL read failing, every table and view is absent, and what remains is the schema
 * itself. The command completing is the claim. The absence is asserted too, because the alternative
 * this replaced was worse: a structural {@code CREATE TABLE} per unreadable table, silently
 * describing an object the server had refused to describe.
 */
@Test
    void generateChangelogSurvivesAnUnreadableObject() throws Exception {
        String changelog = generateChangelog(FailingTrinoDatabase.on(FailingTrinoDatabase.ANY_OBJECT),
                /* absentIsAcceptable= */ true);

        assertFalse(changelog.contains("CREATE TABLE"),
                "no table can be recreated without its verbatim DDL:\n" + changelog);
        assertFalse(changelog.contains("CREATE VIEW"),
                "with every view unreadable, no view can be recreated:\n" + changelog);
    }

    /**
     * With nothing left to record there is no changelog file at all, and the command still succeeds.
     *
     * <p>Worth pinning because it reads like a failure and is not one: the writer produces no output
     * for an empty change set, so the file the user named simply does not appear. That is the correct
     * outcome for "every object in this schema was skipped", and it is a visible difference from the
     * behaviour this replaced, where every skipped table still produced a structural create.
     */
    @Test
    void everyObjectSkippedWritesNoChangelogFile() throws Exception {
        File file = generateChangelogFile(FailingTrinoDatabase.on(FailingTrinoDatabase.ANY_OBJECT));

        assertFalse(file.exists(),
                "an empty change set produces no file, and the command did not fail:\n" + file);
    }

    /**
     * A view whose DDL cannot be read is dropped rather than emitted as a lossy {@code <createView>}.
     * Losing the object is the honest outcome: a view recreated from {@code information_schema} would
     * silently lose its comment and security mode, and a {@code CREATE VIEW} that fails at apply time
     * is worse than one that is absent.
     */
    @Test
    void anUnreadableViewIsAbsentFromTheGeneratedChangelog() throws Exception {
        String changelog = generateChangelog(FailingTrinoDatabase.on(BROKEN_VIEW));

        assertFalse(changelog.contains("CREATE VIEW " + qualifiedSchema + "." + BROKEN_VIEW),
                "a view that could not be read must not be recreated from partial information:\n" + changelog);
        assertTrue(changelog.contains("CREATE VIEW " + qualifiedSchema + "." + GOOD_VIEW),
                "the readable view must be in the changelog:\n" + changelog);
    }

    /**
     * An unreadable table leaves no trace in the changelog.
     *
     * <p>Pinned because the opposite behaviour was deliberate once and was wrong. The table used to
     * survive with degraded metadata, and core then emitted a structural
     * {@code CREATE TABLE broken_table (id INT)} with no {@code WITH (...)}. That reads as a lossy
     * copy of the original, but it is not one: {@code SHOW CREATE TABLE} refused because Trino does
     * not consider the object an Iceberg table at all, which means its storage is described by
     * {@code external_location}, {@code format} and {@code serde} — properties with no column in
     * {@code information_schema}. Applying that changelog would create a new, empty Iceberg table
     * where a Hive table had been. An absent object is recoverable; a wrong one is not.
     */
    @Test
    void anUnreadableTableIsAbsentFromTheGeneratedChangelog() throws Exception {
        String changelog = generateChangelog(FailingTrinoDatabase.on(BROKEN_TABLE));

        // Matched on "CREATE TABLE ... broken_table" rather than on the bare name: broken_view's
        // body selects from broken_table, so the name alone cannot tell a recreated table from a
        // reference to one.
        assertFalse(changelog.contains("CREATE TABLE " + qualifiedSchema + "." + BROKEN_TABLE),
                "a table that cannot be described must not be recreated from partial information:\n"
                        + changelog);
        assertTrue(changelog.contains("CREATE TABLE " + qualifiedSchema + "." + GOOD_TABLE),
                "the readable table must still be in the changelog:\n" + changelog);
    }

    // --- The warning ---

    /**
     * A skip nobody is told about is indistinguishable from a bug. The message has to name the
     * object: "something was skipped" leaves the user with a changelog that is quietly short and no
     * way to find out what is missing.
     */
    @Test
    void theSkippedObjectIsNamedInAWarning() throws Exception {
        WarningRecorder.Result<String> recorded = WarningRecorder.record(
                () -> snapshotJson(FailingTrinoDatabase.on(BROKEN_VIEW)));

        assertTrue(recorded.mentions(BROKEN_VIEW),
                "the warning must name the object that was skipped, got:\n"
                        + String.join("\n", recorded.warnings()));
    }

    /** The warning has to say which object, not merely that the statement could not be read. */
    @Test
    void theWarningDistinguishesTheDdlReadFromTheDroppedObject() throws Exception {
        WarningRecorder.Result<String> recorded = WarningRecorder.record(
                () -> generateChangelog(FailingTrinoDatabase.on(BROKEN_TABLE)));

        assertTrue(recorded.mentions(BROKEN_TABLE),
                "a table whose DDL could not be read must still be reported, got:\n"
                        + String.join("\n", recorded.warnings()));
    }

    // --- Unchecked failures ---

    /**
     * Not every failure is a {@code DatabaseException}. Acquiring the executor throws
     * {@code UnexpectedLiquibaseException} when it has been shut down, and the JDBC type conversion
     * layer throws bare {@code RuntimeException}s on a type it cannot map. A catch narrowed to the
     * checked exception leaves the command dying exactly as before.
     */
    @Test
    void anUncheckedFailureIsCaughtToo() throws Exception {
        String json = snapshotJson(FailingTrinoDatabase.uncheckedOn(BROKEN_VIEW));

        assertFalse(json.contains("\"" + BROKEN_VIEW + "\""),
                "an unchecked failure must be handled like a checked one:\n" + json);
        assertTrue(json.contains("\"" + GOOD_VIEW + "\""),
                "the readable objects must survive an unchecked failure:\n" + json);
    }

    // --- Nothing to fix here ---

    /**
     * The control: with nothing injected the same command records everything, verbatim DDL included.
     * Without it, a fixture that had quietly stopped creating its objects would pass every test above
     * by skipping the right things for the wrong reason.
     */
    @Test
    void aHealthyConnectionRecordsEverything() throws Exception {
        String json = snapshotJsonHealthy();

        assertTrue(json.contains("\"" + GOOD_TABLE + "\""), "the table must be there:\n" + json);
        assertTrue(json.contains("\"" + BROKEN_TABLE + "\""),
                "a table that can be read must not be skipped:\n" + json);
        assertTrue(json.contains("\"" + GOOD_VIEW + "\""), "the view must be there:\n" + json);
        assertTrue(json.contains("\"" + BROKEN_VIEW + "\""),
                "a view that can be read must not be skipped:\n" + json);
        assertTrue(json.contains("CREATE VIEW " + qualifiedSchema + "." + BROKEN_VIEW),
                "a healthy connection must record the verbatim view DDL:\n" + json);
    }

    // --- Plumbing ---

    private static String snapshotJson(FailingTrinoDatabase failing) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (FailingTrinoDatabase db = open(failing)) {
            new CommandScope(SnapshotCommandStep.COMMAND_NAME[0])
                    .addArgumentValue("database", db)
                    .addArgumentValue(SnapshotCommandStep.SCHEMAS_ARG, SCHEMA)
                    .addArgumentValue("snapshotFormat", "json")
                    .setOutput(out)
                    .execute();
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String generateChangelog(FailingTrinoDatabase failing) throws Exception {
        return generateChangelog(failing, false);
    }

    /**
     * @param absentIsAcceptable true when an empty change set may leave the file unwritten, which it
     *                           does: the writer emits nothing rather than an empty changelog
     */
    private static String generateChangelog(FailingTrinoDatabase failing, boolean absentIsAcceptable)
            throws Exception {
        File file = generateChangelogFile(failing);
        if (!file.exists() && absentIsAcceptable) {
            return "";
        }
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    private static File generateChangelogFile(FailingTrinoDatabase failing) throws Exception {
        File file = new File("target", "snapshot-failure-" + System.nanoTime() + ".trino.sql");
        try (FailingTrinoDatabase db = open(failing)) {
            new CommandScope(GenerateChangelogCommandStep.COMMAND_NAME[0])
                    .addArgumentValue("database", db)
                    .addArgumentValue(GenerateChangelogCommandStep.REFERENCE_SCHEMAS_ARG, SCHEMA)
                    .addArgumentValue(GenerateChangelogCommandStep.CHANGELOG_FILE_ARG, file.getAbsolutePath())
                    .execute();
        }
        return file;
    }

    /** The failing dialect on a real connection. */
    private static FailingTrinoDatabase open(FailingTrinoDatabase failing) throws Exception {
        Connection raw = TrinoTestSupport.openRaw();
        failing.setConnection(new JdbcConnection(raw));
        return failing;
    }

    /** The same commands with a plain, healthy connection. */
    private static String snapshotJsonHealthy() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Database db = TrinoTestSupport.openDatabase()) {
            new CommandScope(SnapshotCommandStep.COMMAND_NAME[0])
                    .addArgumentValue("database", db)
                    .addArgumentValue(SnapshotCommandStep.SCHEMAS_ARG, SCHEMA)
                    .addArgumentValue("snapshotFormat", "json")
                    .setOutput(out)
                    .execute();
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Kept so an unused-import change cannot quietly break the assertion above. */
    @Test
    void qualifiedSchemaIsCatalogPrefixed() {
        assertEquals(catalog + "." + SCHEMA, qualifiedSchema,
                "the fixture's qualified name is what the assertions above match against");
    }
}