package liquibase.ext.trino;

import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.Scope;
import liquibase.changelog.ChangeLogHistoryService;
import liquibase.changelog.ChangeLogHistoryServiceFactory;
import liquibase.changelog.FastCheckService;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.LabelExpression;
import liquibase.resource.ClassLoaderResourceAccessor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Connection to Trino for integration tests, plus the shared fixture operations.
 * <p>
 * URL and user come from {@code trino.test.url}/{@code trino.test.user} system properties
 * or {@code TRINO_TEST_URL}/{@code TRINO_TEST_USER}, defaulting to the stand from
 * {@code src/test/trino/docker-compose.yml}. Run it with {@code ./run-tests.sh}.
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

    private TrinoTestSupport() {
    }

    public static String url() {
        return System.getProperty("trino.test.url",
                System.getenv().getOrDefault("TRINO_TEST_URL", "jdbc:trino://localhost:8081/iceberg_catalog"));
    }

    public static String user() {
        return System.getProperty("trino.test.user",
                System.getenv().getOrDefault("TRINO_TEST_USER", "smoke"));
    }

    /**
     * Whether the stand is up.
     * <p>
     * A real query, not {@code DriverManager.getConnection}: the Trino JDBC driver connects
     * lazily and happily returns a Connection for a dead port, so a connection-based probe
     * would always report true and the assume-guards in the integration tests would never
     * fire — the run would fail with a ConnectException inside {@code @BeforeAll} instead
     * of skipping.
     */
    public static boolean isReachable() {
        try {
            queryFirstColumn("SELECT 1");
            return true;
        } catch (Exception e) {
            return false;
        }
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
        Database db = openDatabase();
        db.setDefaultSchemaName(CHANGELOG_SCHEMA);
        db.setLiquibaseSchemaName(CHANGELOG_SCHEMA);
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
        execute("DROP SCHEMA IF EXISTS " + CHANGELOG_SCHEMA + " CASCADE");
        execute("DROP SCHEMA IF EXISTS " + FIXTURE_SCHEMA + " CASCADE");
        execute("CREATE SCHEMA IF NOT EXISTS " + CHANGELOG_SCHEMA);
    }

    /** Brings the stand to the state where all fixture changesets are applied. */
    public static void resetToApplied() throws Exception {
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
