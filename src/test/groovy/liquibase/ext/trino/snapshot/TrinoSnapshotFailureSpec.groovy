package liquibase.ext.trino.snapshot

import liquibase.database.Database
import liquibase.database.jvm.JdbcConnection
import liquibase.ext.trino.TrinoIntegrationSpec
import liquibase.ext.trino.TrinoTestSupport
import spock.lang.Shared

import java.nio.charset.StandardCharsets
import java.nio.file.Files

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
 * {@code TrinoDdlFetcher.attachVerbatimDdl} is the only place that decides, and it decides for
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
 * The fallback was not a degraded copy of the object but a different one, so both object types are
 * now treated the same way.
 *
 * <p>The failure is injected through {@link FailingTrinoDatabase} rather than produced by a broken
 * server: the read that fails is the same read either way, and the plugin cannot tell them apart.
 *
 * <p>These features drive the commands through {@code CommandScope} with a pre-built Database rather
 * than a URL. {@code DbUrlConnectionArgumentsCommandStep} declares a hidden {@code database} argument
 * that supersedes {@code url}, so the command uses the injected instance instead of building its own —
 * which is what makes the failure controllable at all.
 */
class TrinoSnapshotFailureSpec extends TrinoIntegrationSpec {

    private static final String SCHEMA = "snapshot_failure_rt"
    private static final String GOOD_TABLE = "good_table"
    private static final String BROKEN_TABLE = "broken_table"
    private static final String GOOD_VIEW = "good_view"
    private static final String BROKEN_VIEW = "broken_view"

    @Shared
    String qualifiedSchema

    def setupSpec() {
        TrinoTestSupport.applyIfNeeded()
        qualifiedSchema = TrinoTestSupport.qualified(TrinoTestSupport.catalog(), SCHEMA)

        TrinoTestSupport.resetSchemas(qualifiedSchema)
        TrinoTestSupport.execute("CREATE TABLE " + qualifiedSchema + "." + GOOD_TABLE + " (id integer)")
        TrinoTestSupport.execute("CREATE TABLE " + qualifiedSchema + "." + BROKEN_TABLE + " (id integer)")
        TrinoTestSupport.execute("CREATE VIEW " + qualifiedSchema + "." + GOOD_VIEW
                + " AS SELECT id FROM " + qualifiedSchema + "." + GOOD_TABLE)
        TrinoTestSupport.execute("CREATE VIEW " + qualifiedSchema + "." + BROKEN_VIEW
                + " AS SELECT id FROM " + qualifiedSchema + "." + BROKEN_TABLE)
    }

    /** The probe schema is this class's own, so it leaves nothing behind for the next one to read. */
    def cleanupSpec() {
        TrinoTestSupport.dropSchemas(qualifiedSchema)
    }

    // --- snapshot ---

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
    def "an unreadable table is dropped from the snapshot"() {
        when:
        def json = snapshotJson(FailingTrinoDatabase.on(BROKEN_TABLE))

        then: "without its DDL the table is left out, and only it"
        !json.contains("\"" + BROKEN_TABLE + "\"")
        json.contains("\"" + GOOD_TABLE + "\"")
    }

    /**
     * A view is a different case: its body comes from a read the generator controls, so a failure
     * there takes the whole object out. A fix that only guarded tables would leave the view in the
     * snapshot, broken.
     */
    def "the snapshot drops an unreadable view and keeps the rest"() {
        when:
        def json = snapshotJson(FailingTrinoDatabase.on(BROKEN_VIEW))

        then:
        json.contains("\"" + GOOD_VIEW + "\"")
        !json.contains("\"" + BROKEN_VIEW + "\"")
    }

    /** Both broken at once: state left by the first skip must not poison the second. */
    def "the snapshot survives several unreadable objects"() {
        when:
        def json = snapshotJson(FailingTrinoDatabase.on(FailingTrinoDatabase.ANY_OBJECT))

        then: "the schema comes from a different generator and must survive"
        json.contains("\"" + SCHEMA + "\"")

        and: "no table or view survives without its DDL"
        !json.contains("\"" + GOOD_TABLE + "\"")
        !json.contains("\"" + GOOD_VIEW + "\"")
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
    def "generate-changelog survives an unreadable object"() {
        when:
        def changelog = generateChangelog(FailingTrinoDatabase.on(FailingTrinoDatabase.ANY_OBJECT), true)

        then:
        !changelog.contains("CREATE TABLE")
        !changelog.contains("CREATE VIEW")
    }

    /**
     * With nothing left to record there is no changelog file at all, and the command still succeeds.
     *
     * <p>Worth pinning because it reads like a failure and is not one: the writer produces no output
     * for an empty change set, so the file the user named simply does not appear. That is the correct
     * outcome for "every object in this schema was skipped", and it is a visible difference from the
     * behaviour this replaced, where every skipped table still produced a structural create.
     */
    def "every object skipped writes no changelog file"() {
        when:
        def file = generateChangelogFile(FailingTrinoDatabase.on(FailingTrinoDatabase.ANY_OBJECT))

        then:
        !file.exists()
    }

    /**
     * A view whose DDL cannot be read is dropped rather than emitted as a lossy {@code <createView>}.
     * Losing the object is the honest outcome: a view recreated from {@code information_schema} would
     * silently lose its comment and security mode, and a {@code CREATE VIEW} that fails at apply time
     * is worse than one that is absent.
     */
    def "an unreadable view is absent from the generated changelog"() {
        when:
        def changelog = generateChangelog(FailingTrinoDatabase.on(BROKEN_VIEW))

        then:
        !changelog.contains("CREATE VIEW " + qualifiedSchema + "." + BROKEN_VIEW)
        changelog.contains("CREATE VIEW " + qualifiedSchema + "." + GOOD_VIEW)
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
    def "an unreadable table is absent from the generated changelog"() {
        when:
        def changelog = generateChangelog(FailingTrinoDatabase.on(BROKEN_TABLE))

        then:
        // Matched on "CREATE TABLE ... broken_table" rather than on the bare name: broken_view's
        // body selects from broken_table, so the name alone cannot tell a recreated table from a
        // reference to one.
        !changelog.contains("CREATE TABLE " + qualifiedSchema + "." + BROKEN_TABLE)
        changelog.contains("CREATE TABLE " + qualifiedSchema + "." + GOOD_TABLE)
    }

    // --- The warning ---

    /**
     * A skip nobody is told about is indistinguishable from a bug. The message has to name the
     * object: "something was skipped" leaves the user with a changelog that is quietly short and no
     * way to find out what is missing.
     *
     * <p>Both commands are covered, because both walk the same objects and either one could be the
     * one that swallows the warning — a snapshot that warns correctly while the changelog generator
     * stays silent is exactly the kind of half-working behaviour this asserts against. The two
     * fail on different object types as well, so table and view are both exercised.
     */
    def "the #commandName command names the skipped #objectKind in a warning"() {
        when:
        def recorded = WarningRecorder.record({ run(FailingTrinoDatabase.on(objectName)) })

        then:
        recorded.mentions(objectName)

        where:
        commandName          | objectKind | objectName   | run
        "snapshot"           | "view"     | BROKEN_VIEW  | { f -> snapshotJson(f) }
        "generate-changelog" | "table"    | BROKEN_TABLE | { f -> generateChangelog(f, false) }
    }

    // --- Unchecked failures ---

    /**
     * Not every failure is a {@code DatabaseException}. Acquiring the executor throws
     * {@code UnexpectedLiquibaseException} when it has been shut down, and the JDBC type conversion
     * layer throws bare {@code RuntimeException}s on a type it cannot map. A catch narrowed to the
     * checked exception leaves the command dying exactly as before.
     */
    def "an unchecked failure is caught too"() {
        when:
        def json = snapshotJson(FailingTrinoDatabase.uncheckedOn(BROKEN_VIEW))

        then:
        !json.contains("\"" + BROKEN_VIEW + "\"")
        json.contains("\"" + GOOD_VIEW + "\"")
    }

    // --- Nothing to fix here ---

    /**
     * The control: with nothing injected the same command records everything, verbatim DDL included.
     * Without it, a fixture that had quietly stopped creating its objects would pass every test above
     * by skipping the right things for the wrong reason.
     */
    def "a healthy connection records everything"() {
        when:
        def json = snapshotJsonHealthy()

        then:
        json.contains("\"" + GOOD_TABLE + "\"")
        json.contains("\"" + BROKEN_TABLE + "\"")
        json.contains("\"" + GOOD_VIEW + "\"")
        json.contains("\"" + BROKEN_VIEW + "\"")
        json.contains("CREATE VIEW " + qualifiedSchema + "." + BROKEN_VIEW)
    }

    // --- Plumbing ---

    private static String snapshotJson(FailingTrinoDatabase failing) {
        try (FailingTrinoDatabase db = open(failing)) {
            return TrinoTestSupport.snapshotJson(db, SCHEMA)
        }
    }

    private static String generateChangelog(FailingTrinoDatabase failing) {
        return generateChangelog(failing, false)
    }

    /**
     * @param absentIsAcceptable true when an empty change set may leave the file unwritten, which it
     *                           does: the writer emits nothing rather than an empty changelog
     */
    private static String generateChangelog(FailingTrinoDatabase failing, boolean absentIsAcceptable) {
        File file = generateChangelogFile(failing)
        if (!file.exists() && absentIsAcceptable) {
            return ""
        }
        return Files.readString(file.toPath(), StandardCharsets.UTF_8)
    }

    private static File generateChangelogFile(FailingTrinoDatabase failing) {
        try (FailingTrinoDatabase db = open(failing)) {
            return TrinoTestSupport.generateChangelogTo(db, SCHEMA, "snapshot-failure-")
        }
    }

    /** The failing dialect on a real connection. */
    private static FailingTrinoDatabase open(FailingTrinoDatabase failing) {
        def raw = TrinoTestSupport.openRaw()
        failing.setConnection(new JdbcConnection(raw))
        return failing
    }

    /** The same commands with a plain, healthy connection. */
    private static String snapshotJsonHealthy() {
        try (Database db = TrinoTestSupport.openDatabase()) {
            return TrinoTestSupport.snapshotJson(db, SCHEMA)
        }
    }
}
