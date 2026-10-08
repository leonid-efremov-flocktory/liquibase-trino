package liquibase.ext.trino;

import liquibase.CatalogAndSchema;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.changelog.ChangeLogHistoryService;
import liquibase.changelog.ChangeLogHistoryServiceFactory;
import liquibase.changelog.FastCheckService;
import liquibase.command.CommandResults;
import liquibase.command.CommandScope;
import liquibase.command.core.DiffChangelogCommandStep;
import liquibase.command.core.DiffCommandStep;
import liquibase.command.core.GenerateChangelogCommandStep;
import liquibase.command.core.SnapshotCommandStep;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.diff.DiffResult;
import liquibase.exception.LiquibaseException;
import liquibase.LabelExpression;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Table;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Connection to Trino for integration tests, plus the shared fixture operations.
 * <p>
 * URL and user come from {@code trino.test.url}/{@code trino.test.user} system properties
 * or {@code TRINO_TEST_URL}/{@code TRINO_TEST_USER}, defaulting to the stand from
 * {@code src/test/trino/docker-compose.yml}. Run it with {@code ./run-tests.sh}.
 * <p>
 * What belongs here: an operation that two classes would otherwise write out again. What does
 * not: setup that only one class needs, and helpers with a single caller. Those stay private
 * next to their use — moved here "for reuse" without a second user, they turn a local detail
 * into something the next reader has to look up. Two judgement calls that look like exceptions
 * are deliberate. The {@code @BeforeAll} blocks in the integration classes are near-identical
 * (five of them call {@link #applyIfNeeded()}), and are left as they are: those few lines say
 * which fixture a class stands on, which is the one thing worth stating in a test class, and a
 * base class would hide it. And {@code snapshotJson} has two overloads rather than one, because
 * they exercise different routes through Liquibase — see below.
 */
public final class TrinoTestSupport {

    /** Shared {@code test-changelog.xml} fixture for all plugin tests. */
    public static final String CHANGELOG = "liquibase/ext/trino/test-changelog.xml";

    /**
     * Second fixture: the same objects expressed through {@code <sqlFile>} instead of
     * formatted-SQL includes, in XML, YAML and JSON. See TrinoChangelogFormatIntegrationTest.
     */
    public static final String CHANGELOG_SQLFILE_XML = "liquibase/ext/trino/sqlfile-probe.xml";
    public static final String CHANGELOG_SQLFILE_YAML = "liquibase/ext/trino/sqlfile-probe.yaml";
    public static final String CHANGELOG_SQLFILE_JSON = "liquibase/ext/trino/sqlfile-probe.json";

    /** Context under which the fixture expands its properties ({@code dev_test_schema}). */
    public static final Contexts CONTEXT = new Contexts("dev");

    public static final String CHANGELOG_SCHEMA = "liquibase_changelog";

    /**
     * Tracking schema of the {@code <sqlFile>} fixtures, kept apart from {@link #CHANGELOG_SCHEMA}
     * so that applying them neither reads nor destroys the main fixture's tracking table.
     */
    public static final String SQLFILE_CHANGELOG_SCHEMA = "liquibase_changelog_sqlfile";

    /** Schema name without the catalog, as used in {@code information_schema} filters. */
    public static final String FIXTURE_SCHEMA_NAME = "dev_test_schema";
    public static final String FIXTURE_SCHEMA = "iceberg_catalog." + FIXTURE_SCHEMA_NAME;
    public static final String FIXTURE_TABLE = FIXTURE_SCHEMA + ".test_table";
    public static final String FIXTURE_VIEW = FIXTURE_SCHEMA + ".test_view";

    /** Objects the sqlFile fixtures create, next to the ones above. */
    public static final String SQLFILE_TABLE = FIXTURE_SCHEMA + ".sqlfile_table";
    public static final String SQLFILE_VIEW = FIXTURE_SCHEMA + ".sqlfile_view";

    public static final String TABLE_NAME = "test_table";
    public static final String VIEW_NAME = "test_view";

    /** Changesets in the fixture: schema creation, table + view, extension. */
    public static final int FIXTURE_CHANGESETS = 3;

    /** Expected tracking-table state after the fixture is applied, in execution order. */
    public static final List<String> APPLIED_CHANGESETS = List.of(
            "common-schema-setup",
            "v1-test-table-and-view",
            "v2-extend-test-table-and-view");

    private static final LabelExpression NO_LABELS = new LabelExpression();

    /** Cached answer of {@link #isReachable()}: {@code null} until the first probe. */
    private static Boolean reachable;

    private TrinoTestSupport() {
    }

    private static String url() {
        return System.getProperty("trino.test.url",
                System.getenv().getOrDefault("TRINO_TEST_URL", "jdbc:trino://localhost:8081/iceberg_catalog"));
    }

    private static String user() {
        return System.getProperty("trino.test.user",
                System.getenv().getOrDefault("TRINO_TEST_USER", "smoke"));
    }

    /**
     * Whether the stand is up. Probed once per JVM and remembered: the answer cannot change
     * mid-run, and every integration class asks it.
     * <p>
     * A real query, not {@code DriverManager.getConnection}: the Trino JDBC driver connects
     * lazily and happily returns a Connection for a dead port, so a connection-based probe
     * would always report true and the {@code @EnabledIf} conditions would never fire — the
     * run would fail with a ConnectException inside {@code @BeforeAll} instead of skipping.
     */
    public static synchronized boolean isReachable() {
        if (reachable == null) {
            try {
                queryFirstColumn("SELECT 1");
                reachable = true;
            } catch (Exception e) {
                reachable = false;
            }
        }
        return reachable;
    }

    public static Connection openRaw() throws Exception {
        Class.forName("io.trino.jdbc.TrinoDriver");
        return DriverManager.getConnection(url() + "?user=" + user() + "&source=liquibase-trino-test");
    }

    public static Database openDatabase() throws Exception {
        Connection connection = openRaw();
        return DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
    }

    /** Runs a single SQL statement (the Trino JDBC driver rejects several per execute). */
    public static void execute(String sql) throws Exception {
        try (Connection c = openRaw(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    /**
     * Runs a query and returns its rows as strings, taking the first column only.
     * <p>
     * An empty result is treated as a broken query ({@code count(*)} always returns a row)
     * and fails instead of returning an empty list, so that a typo in a schema name cannot
     * masquerade as a "0".
     */
    public static List<String> queryFirstColumn(String sql) throws Exception {
        List<String> result = new ArrayList<>();
        try (Connection c = openRaw();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }
        if (result.isEmpty()) {
            throw new AssertionError("Query returned no rows: " + sql);
        }
        return result;
    }

    /** {@code count(*)} of a table or view. */
    public static String count(String relation) throws Exception {
        return queryFirstColumn("SELECT count(*) FROM " + relation).get(0);
    }

    /** Number of tables or views with this name in the given schema. */
    public static String countObjects(String schema, String tableName) throws Exception {
        return queryFirstColumn("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = '" + schema + "' AND table_name = '" + tableName + "'").get(0);
    }

    /** Number of fixture tables or views with this name in {@link #FIXTURE_SCHEMA_NAME}. */
    public static String countFixtureObjects(String tableName) throws Exception {
        return countObjects(FIXTURE_SCHEMA_NAME, tableName);
    }

    /**
     * Opens a Database pointed at the test stand: Liquibase tracking tables live in their
     * own schema, fixture objects in the {@code iceberg_catalog} catalog.
     */
    public static Database openChangelogDatabase() throws Exception {
        return openChangelogDatabase(CHANGELOG_SCHEMA);
    }

    /** As {@link #openChangelogDatabase()}, with the tracking tables in the given schema. */
    public static Database openChangelogDatabase(String changelogSchema) throws Exception {
        Database db = openDatabase();
        db.setDefaultSchemaName(changelogSchema);
        db.setLiquibaseSchemaName(changelogSchema);
        db.setLiquibaseCatalogName(db.getDefaultCatalogName());
        return db;
    }

    /**
     * Clears Liquibase's fast-check cache.
     * <p>
     * Liquibase 5 asks {@code FastCheckService} whether there is anything to run before
     * taking the lock, and that service caches its answer per JVM under a key of
     * contexts/labels/schema/catalog/URL/logicalFilePath. {@code rollback} never
     * invalidates it, so "update → no-op update → rollback → update" makes the last update
     * print "no changesets to execute" and apply nothing while its summary still counts
     * "Run: 2". That is a Liquibase bug, worked around here; see Known limitations in README.
     *
     * @see <a href="https://github.com/liquibase/liquibase/blob/v5.0.4/liquibase-standard/src/main/java/liquibase/changelog/FastCheckService.java">FastCheckService</a>
     */
    private static void clearFastCheckCache() {
        // Scope.getSingleton walks up to the root scope, so this is the very same instance
        // the update command uses, not a fresh empty one.
        Scope.getCurrentScope().getSingleton(FastCheckService.class).clearCache();
    }

    /** Applies the whole fixture. Idempotent: v1 starts with {@code DROP ... IF EXISTS}. */
    public static void update(Database db) throws LiquibaseException {
        clearFastCheckCache();
        new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), db).update(CONTEXT, NO_LABELS);
    }

    /** Really rolls back the last {@code count} fixture changesets. */
    public static void rollback(Database db, int count) throws LiquibaseException {
        clearFastCheckCache();
        new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), db).rollback(count, CONTEXT, NO_LABELS);
    }

    /** A {@code Liquibase} handle over the shared fixture and the given Database. */
    public static Liquibase liquibase(Database db) {
        return new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), db);
    }

    /** A {@code Liquibase} handle over an arbitrary changelog on the classpath. */
    public static Liquibase liquibase(String changelog, Database db) {
        return new Liquibase(changelog, new ClassLoaderResourceAccessor(), db);
    }

    /** Applies an arbitrary changelog under {@link #CONTEXT}. */
    public static void update(String changelog, Database db) throws LiquibaseException {
        clearFastCheckCache();
        liquibase(changelog, db).update(CONTEXT, NO_LABELS);
    }

    /** Rolls back the last {@code count} changesets of an arbitrary changelog. */
    public static void rollback(String changelog, Database db, int count) throws LiquibaseException {
        clearFastCheckCache();
        liquibase(changelog, db).rollback(count, CONTEXT, NO_LABELS);
    }

    /** The tracking-table service Liquibase itself uses, to read applied changesets back. */
    public static ChangeLogHistoryService historyService(Database db) {
        return ChangeLogHistoryServiceFactory.getInstance().getChangeLogService(db);
    }

    /**
     * Resets the stand before a test: drops the fixture objects and the tracking tables so
     * the next {@link #update} runs the changesets from scratch.
     * <p>
     * {@code liquibase.dropAll} cannot do this: it relies on JDBC metadata and Trino has no
     * {@code information_schema.table_constraints} view, which Liquibase queries for foreign
     * keys. Hence plain DROP SCHEMA.
     */
    public static void dropAll() throws Exception {
        dropAll(CHANGELOG_SCHEMA, FIXTURE_SCHEMA);
    }

    /**
     * As {@link #dropAll()}, with the tracking and case schemas named.
     * <p>
     * Only the tracking schema is recreated, and that is the point: Liquibase rewrites
     * {@code DATABASECHANGELOG} on the next update, whereas the case schema has to stay absent or
     * its leftovers become the subject of the assertions.
     */
    public static void dropAll(String trackingSchema, String caseSchema) throws Exception {
        execute("DROP SCHEMA IF EXISTS " + trackingSchema + " CASCADE");
        execute("DROP SCHEMA IF EXISTS " + caseSchema + " CASCADE");
        execute("CREATE SCHEMA IF NOT EXISTS " + trackingSchema);
    }

    /** Brings the stand to the state where all fixture changesets are applied. */
    private static void resetToApplied() throws Exception {
        dropAll();
        update(openChangelogDatabase());
    }

    // --- Tracking-table state: what the tests actually compare against the stand. ---

    /** Whether both Liquibase tracking tables exist. */
    public static boolean trackingTablesExist() throws Exception {
        String tables = queryFirstColumn("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = '" + CHANGELOG_SCHEMA
                + "' AND table_name IN ('databasechangelog', 'databasechangeloglock')").get(0);
        return "2".equals(tables);
    }

    /**
     * {@code DATABASECHANGELOG} contents as {@code id|orderexecuted|exectype|filename}
     * rows in execution order.
     */
    public static List<String> changelogRows() throws Exception {
        return queryRows("SELECT id, orderexecuted, exectype, filename FROM "
                + CHANGELOG_SCHEMA + ".databasechangelog ORDER BY orderexecuted");
    }

    /** Ids of the changesets marked as executed, in execution order. */
    public static List<String> appliedChangesetIds() throws Exception {
        List<String> ids = new ArrayList<>();
        for (String row : changelogRows()) {
            ids.add(row.substring(0, row.indexOf('|')));
        }
        return ids;
    }

    /**
     * {@code DATABASECHANGELOGLOCK} contents as {@code locked|lockedby}.
     * <p>
     * An empty list means there is no lock row at all, i.e. the lock was not merely released
     * but never created — a distinction the tests care about.
     */
    public static List<String> lockState() throws Exception {
        try {
            return queryRows("SELECT locked, lockedby FROM " + CHANGELOG_SCHEMA + ".databasechangeloglock");
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Runs a query and returns its rows as {@code |}-separated column strings.
     * <p>
     * SQL NULL becomes an empty string: Trino JDBC returns it as {@code null}, which in an
     * expected value would just read as "row does not equal row".
     */
    private static List<String> queryRows(String sql) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection c = openRaw();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= columns; i++) {
                    row.append(i == 1 ? "" : "|").append(nullToEmpty(rs.getString(i)));
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * The stand's catalog, i.e. the one the fixture objects live in.
     * <p>
     * Asked of the server rather than written down, because the six classes that used to spell
     * {@code "iceberg_catalog"} out and the two that discovered it disagreed, and a disagreement
     * there fails as an empty result instead of an error.
     */
    public static String catalog() throws Exception {
        try (Database db = openDatabase()) {
            return db.getDefaultCatalogName();
        }
    }

    /**
     * A schema name qualified with {@code catalog}, for the assertions that match on names.
     * <p>
     * Takes the catalog rather than calling {@link #catalog()} itself: the callers already hold it
     * from {@code getDefaultCatalogName()}, and this way the two never disagree. A helper that
     * re-read the catalog on each call would be one more connection per assertion, and a name
     * assembled from a different reading is exactly what made the snapshot tests fail on an
     * empty result instead of on an error.
     */
    public static String qualified(String catalog, String schema) {
        return catalog + "." + schema;
    }

    /**
     * Drops and recreates each of the given schemas, so a case starts from nothing.
     * <p>
     * Both halves are needed and the tracking schema is not special: Liquibase records changesets
     * in a schema of its own, and leaving it behind means it considers them already run and skips
     * them while the objects they would have created are gone — the case then fails on its first
     * state assertion instead of on the thing it means to test. Recreating it empty is safe, since
     * {@code DATABASECHANGELOG} is written on the next update.
     */
    public static void resetSchemas(String... qualifiedSchemas) throws Exception {
        for (String schema : qualifiedSchemas) {
            execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
        for (String schema : qualifiedSchemas) {
            execute("CREATE SCHEMA " + schema);
        }
    }

/**
 * Drops each of the given schemas, leaving none of them behind.
 * <p>
 * For a class's teardown: a stand that is only rebuilt at container start-up accumulates
 * every schema a failed run left behind, and the next run inherits them. What that costs
 * depends on the class — a leftover table changes a snapshot's contents, a leftover
 * {@code DATABASECHANGELOG} makes Liquibase skip changesets whose objects are gone — and in
 * both cases the failure lands on an assertion about state that has nothing to do with the
 * change under test. {@code IF EXISTS} keeps the teardown safe to run twice, which matters
 * because a test that throws still runs it.
 */
public static void dropSchemas(String... qualifiedSchemas) throws Exception {
    for (String schema : qualifiedSchemas) {
        execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
}

/**
 * Object names shared by the {@code diff} and {@code diffChangelog} probe fixtures.
 * <p>
 * The two commands report on the same shape of difference — one object only on the reference
 * side, one only on the target, one common — so both fixtures create the same three tables and
 * name them the same way. Giving one side its own names would mean reading the expected output
 * of two different commands, which is where the drift this removes would come back from.
 */
public static final String DIFF_SHARED_TABLE = "shared_table";
public static final String DIFF_REFERENCE_ONLY = "reference_only_table";
public static final String DIFF_TARGET_ONLY = "target_only_table";

/**
 * The two halves of a diff fixture, created from scratch: two empty schemas, then a common
 * table in both, one only on the reference side and one only on the target.
 * <p>
 * Built whole rather than a table at a time so that both commands get an identical starting
 * state. {@code sharedColumns} is a column list because the two callers want different ones —
 * the diff probe varies the shared table's columns mid-test to check that a change is reported,
 * while the diffChangelog probe needs it stable and partitioned tables it can compare verbatim.
 *
 * @param referenceSchema        the side playing the already-deployed database
 * @param targetSchema           the side playing the one about to be deployed
 * @param sharedColumns          column list for {@link #DIFF_SHARED_TABLE} in both schemas
 * @param referenceOnlyColumns   column list for {@link #DIFF_REFERENCE_ONLY}, or {@code null}
 *                               to skip that table — the diffChangelog fixture keeps it,
 *                               the diff fixture starts from it and adds its own
 */
public static void createDiffFixture(String referenceSchema, String targetSchema,
                                     String sharedColumns, String referenceOnlyColumns) throws Exception {
    resetSchemas(referenceSchema, targetSchema);
    execute("CREATE TABLE " + referenceSchema + "." + DIFF_SHARED_TABLE + " " + sharedColumns);
    execute("CREATE TABLE " + targetSchema + "." + DIFF_SHARED_TABLE + " " + sharedColumns);
    execute("CREATE TABLE " + targetSchema + "." + DIFF_TARGET_ONLY + " " + sharedColumns);
    if (referenceOnlyColumns != null) {
        execute("CREATE TABLE " + referenceSchema + "." + DIFF_REFERENCE_ONLY + " " + referenceOnlyColumns);
    }
}

/**
 * {@code snapshot} of one schema as JSON, driven the way a user runs it: the command opens
 * its own connection from the URL.
 * <p>
 * Not a duplicate of {@link #snapshotJson(Database, String)} below, and worth keeping apart: this
 * one covers the command building its own connection from {@code url}, which is what the CLI
 * does and the only test of that route, while the other covers a connection handed in.
 */
    public static String snapshotJson(String schema) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CommandScope(SnapshotCommandStep.COMMAND_NAME[0])
                .addArgumentValue("url", url())
                .addArgumentValue("username", user())
                // The schema has to be named. Left unset, Trino reports no session schema for a
                // catalog-only URL, so the default schema renders as "iceberg_catalog.DEFAULT",
                // matches nothing, and the snapshot comes back holding only the catalog — with no
                // error. Putting the schema in the URL resolves the same way; see README.
                .addArgumentValue(SnapshotCommandStep.SCHEMAS_ARG, schema)
                .addArgumentValue("snapshotFormat", "json")
                .setOutput(out)
                .execute();
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * The same command against a Database handed in rather than built from the URL.
     * <p>
     * {@code DbUrlConnectionArgumentsCommandStep} declares a hidden {@code database} argument that
     * supersedes {@code url}, so the command uses this instance and never builds its own. That is
     * what makes a failure injectable: see {@code FailingTrinoDatabase}.
     */
    public static String snapshotJson(Database db, String schema) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CommandScope(SnapshotCommandStep.COMMAND_NAME[0])
                .addArgumentValue("database", db)
                .addArgumentValue(SnapshotCommandStep.SCHEMAS_ARG, schema)
                .addArgumentValue("snapshotFormat", "json")
                .setOutput(out)
                .execute();
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * Runs {@code generate-changelog} against a real connection and returns where the file landed.
     * <p>
     * Not {@code java.io.tmpdir}: the test JVM runs inside a container and the file has to be
     * readable by whatever applies it afterwards, in the same JVM.
     * <p>
     * The {@code .trino.sql} suffix is required, not cosmetic: Liquibase picks the SQL serializer
     * from the extension and refuses a bare {@code .sql} name because it cannot tell which
     * dialect's formatting to apply.
     */
    public static Generated generateChangelog(String schema, String filePrefix) throws Exception {
        File file = new File("target", filePrefix + System.nanoTime() + ".trino.sql");
        new CommandScope(GenerateChangelogCommandStep.COMMAND_NAME[0])
                .addArgumentValue("url", url())
                .addArgumentValue("username", user())
                .addArgumentValue(GenerateChangelogCommandStep.REFERENCE_SCHEMAS_ARG, schema)
                .addArgumentValue(GenerateChangelogCommandStep.CHANGELOG_FILE_ARG, file.getAbsolutePath())
                .execute();
        return new Generated(file, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    /**
     * {@code generate-changelog} against a Database handed in, which is what makes an injected
     * failure reach the command. The file is returned rather than its text: with every object
     * skipped the writer emits nothing at all, so the file's absence is the outcome under test.
     */
    public static File generateChangelogTo(Database db, String schema, String filePrefix) throws Exception {
        File file = new File("target", filePrefix + System.nanoTime() + ".trino.sql");
        new CommandScope(GenerateChangelogCommandStep.COMMAND_NAME[0])
                .addArgumentValue("database", db)
                .addArgumentValue(GenerateChangelogCommandStep.REFERENCE_SCHEMAS_ARG, schema)
                .addArgumentValue(GenerateChangelogCommandStep.CHANGELOG_FILE_ARG, file.getAbsolutePath())
                .execute();
        return file;
    }

    /**
     * {@code diffChangelog} of two schemas in the one catalog, as the text of the written file.
     * <p>
     * Two schemas rather than two servers: the command takes {@code referenceUrl} against the
     * same cluster, which is enough to compare two schemas and keeps the stand to one container.
     */
    public static String diffChangelog(String referenceSchema, String targetSchema) throws Exception {
        File file = new File("target", "diff-changelog-" + System.nanoTime() + ".trino.sql");
        new CommandScope(DiffChangelogCommandStep.COMMAND_NAME[0])
                .addArgumentValue("url", url())
                .addArgumentValue("username", user())
                .addArgumentValue("referenceUrl", url())
                .addArgumentValue("referenceUsername", user())
                .addArgumentValue("schemas", targetSchema)
                .addArgumentValue("referenceSchemas", referenceSchema)
                .addArgumentValue(DiffChangelogCommandStep.CHANGELOG_FILE_ARG, file.getAbsolutePath())
                .execute();
        return Files.readString(file.toPath(), StandardCharsets.UTF_8);
    }

    /**
     * One {@code diff} run, keeping both the structured result and the text a user would have read.
     * <p>
     * Both halves earn their place: the result is what the assertions match on, and the output is
     * what to print when a match fails, since it shows what the command actually reported.
     */
    public static Diff diff(String referenceSchema, String targetSchema) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandResults results = new CommandScope(DiffCommandStep.COMMAND_NAME[0])
                .addArgumentValue("url", url())
                .addArgumentValue("username", user())
                .addArgumentValue("referenceUrl", url())
                .addArgumentValue("referenceUsername", user())
                // The schema arguments live in PreCompareCommandStep, not DiffCommandStep, and are
                // passed by name because they are hidden arguments with no public constant to reach for.
                .addArgumentValue("schemas", targetSchema)
                .addArgumentValue("referenceSchemas", referenceSchema)
                .setOutput(out)
                .execute();
        return new Diff(results.getResult(DiffCommandStep.DIFF_RESULT),
                out.toString(StandardCharsets.UTF_8));
    }

    /** A generated changelog: where it landed and what is in it. */
    public record Generated(File file, String text) {
    }

    /** One diff run. See {@link TrinoTestSupport#diff(String, String)}. */
    public record Diff(DiffResult result, String output) {

        /** What to print when an assertion about this run fails. */
        public String render() {
            return output.isEmpty()
                    ? "missing=" + names(result.getMissingObjects(Table.class))
                        + " unexpected=" + names(result.getUnexpectedObjects(Table.class))
                        + " changed=" + result.getChangedObjects(Table.class).keySet()
                    : output;
        }
    }

    /** Reads an object's DDL straight from the server, for the verbatim comparisons. */
    public static String showCreate(String catalog, String schema, String object) throws Exception {
        try (Database db = openDatabase()) {
            return ((liquibase.ext.trino.database.TrinoDatabase) db)
                    .getTableDefinition(new CatalogAndSchema(catalog, schema), object);
        }
    }

    /**
     * Ids of the given items, in order.
     * <p>
     * Every read command reports the same shape — a list of something carrying an id — and each of
     * them spelled the extraction out again.
     */
    public static <T> List<String> idsOf(List<T> items, Function<T, String> id) {
        List<String> ids = new ArrayList<>();
        for (T item : items) {
            ids.add(id.apply(item));
        }
        return ids;
    }

    /** Names of the given objects, for matching a diff's tables by name rather than by key. */
    public static Set<String> names(Set<? extends DatabaseObject> objects) {
        return objects.stream().map(DatabaseObject::getName).collect(Collectors.toSet());
    }

    /**
     * Drops everything {@code update-sql} emits that is not the changelog's own SQL.
     * <p>
     * The raw output cannot be compared as it stands, because it is not reproducible: it carries
     * the wall-clock time it ran, the JDBC URL it ran against, the hostname and IP of whoever took
     * the lock, and a freshly generated deployment id. A golden file holding any of those would
     * fail on the next run for reasons that have nothing to do with the SQL generator.
     * <p>
     * What goes, and nothing else:
     * <ul>
     *   <li>statements against {@code databasechangelog} / {@code databasechangeloglock} — Liquibase
     *       creating its tracking tables, taking the lock and recording the run;</li>
     *   <li>Liquibase's own headings and header lines, which is why the section titles would
     *       otherwise survive as headings with nothing under them;</li>
     *   <li>the blank lines those removals leave behind.</li>
     * </ul>
     * Comments inside a changeset are <em>not</em> removed: a user writing
     * {@code <sql>-- note</sql>} gets that line compared like any other. What is left is the
     * changeset markers and the statements themselves, verbatim — no trimming, no whitespace
     * collapsing, no case folding — and that is the part a change in this extension can affect.
     */
    public static String withoutLiquibaseBookkeeping(String updateSqlOutput) {
        StringBuilder result = new StringBuilder();
        for (String line : updateSqlOutput.split("\n", -1)) {
            String text = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (isLiquibaseNoise(text)) {
                continue;
            }
            if (text.isBlank()) {
                continue;
            }
            if (text.startsWith("-- Changeset ") && !result.isEmpty()) {
                result.append('\n');
            }
            result.append(text).append('\n');
        }
        return result.toString();
    }

    /** Whether a line is Liquibase's bookkeeping rather than something the changelog produced. */
    private static boolean isLiquibaseNoise(String line) {
        String lower = line.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("databasechangelog")) {
            return true;
        }
        if (lower.startsWith("-- ****")) {
            return true;
        }
        return LIQUIBASE_HEADING_PREFIXES.stream().anyMatch(lower::startsWith);
    }

    /**
     * Prefixes of the headings {@code update-sql} prints around the run, lower-cased and without
     * the surrounding comment markers. Matched by prefix because the header lines carry a value:
     * {@code -- Ran at: 10/7/26, 8:14 AM} would slip past an equality check.
     */
    private static final Set<String> LIQUIBASE_HEADING_PREFIXES = Set.of(
            "-- create database lock table",
            "-- initialize database lock table",
            "-- lock database",
            "-- create database change log table",
            "-- release database lock",
            "-- custom sql",
            "-- update database script",
            "-- change log",
            "-- liquibase version",
            "-- ran at",
            "-- against");

    /**
     * Applies the fixture unless it is already applied. For tests that rely on the applied
     * state: the fixture is not re-applied before every class, yet the result stays
     * independent of test execution order.
     */
    public static void applyIfNeeded() throws Exception {
        if (trackingTablesExist() && appliedChangesetIds().size() == FIXTURE_CHANGESETS) {
            return;
        }
        resetToApplied();
        // Compare against plain JDBC rather than the Liquibase summary: the summary counts
        // what the command predicted at start, not what ran. Without this check an update
        // that silently took the fast-check path would leave the stand rolled back, and
        // nothing would fail — the tests assert on objects nobody has recreated.
        int applied = appliedChangesetIds().size();
        if (applied != FIXTURE_CHANGESETS) {
            throw new AssertionError("Expected " + FIXTURE_CHANGESETS
                    + " changesets in DATABASECHANGELOG after update, but found " + applied
                    + ". The fixture is not applied — see Known limitations in README"
                    + " (Liquibase fast-check after rollback).");
        }
    }
}
