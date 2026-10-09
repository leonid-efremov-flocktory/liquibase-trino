package liquibase.ext.trino.harness

import liquibase.Scope
import liquibase.database.Database
import liquibase.executor.ExecutorService
import liquibase.ext.trino.TrinoTestSupport
import liquibase.harness.config.TestConfig
import liquibase.harness.util.DatabaseConnectionUtil
import liquibase.statement.core.RawSqlStatement
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Tag

/**
 * Stage 2 of the test rewrite (.opencode/plan.md): proof that the Liquibase Test Harness
 * stack runs against the live stand.
 *
 * What it demonstrates, feature by feature: the harness reads our harness-config.yml and
 * honours the dbUrl/dbUsername seam; DatabaseConnectionUtil opens a real connection that
 * Liquibase picks as the Trino dialect, creates the lock table via
 * TrinoCreateDatabaseChangeLogLockTableGenerator, and executes queries; harness.initScript.sql
 * creates the schema and is safe to re-run. initDB is true — TrinoDatabase keeps the connection
 * on autoCommit so the harness's own autoCommit(false) flip cannot poison the session.
 *
 * Tagged like the Java integration classes: {@code -Punit} drops it, and without a stand
 * the {@code @IgnoreIf} probe skips it the same way their {@code @EnabledIf} does.
 */
@Tag("integration")
@IgnoreIf({ !TrinoTestSupport.isReachable() })
class TrinoHarnessConnectionSpec extends Specification {

    @Shared
    TestConfig config

    @Shared
    def databaseUnderTest

    @Shared
    Database database

    def setupSpec() throws Exception {
        // The schema must exist before the harness connects: the URL puts the session INTO
        // it (current_schema), and anything schema-scoped — stage 3's lock table among
        // them — lands there. The harness never runs this file itself (grep of the v1.0.12
        // sources: README only) — this is the seam that does.
        TrinoTestSupport.execute(initScriptText())

        // Seams BEFORE the first touch of TestConfig: its yaml is read once, but
        // getFilteredDatabasesUnderTest() re-reads these properties on every call,
        // and the URL must name the schema (see harnessUrl()).
        if (!System.getProperty("dbUrl")) {
            System.setProperty("dbUrl", harnessUrl())
        }
        if (!System.getProperty("dbUsername")) {
            System.setProperty("dbUsername", TrinoTestSupport.user())
        }

        // The same two steps a harness suite takes to get a database.
        config = TestConfig.instance
        databaseUnderTest = config.filteredDatabasesUnderTest[0]
        database = DatabaseConnectionUtil.initializeDatabase(
                databaseUnderTest.url, databaseUnderTest.username, databaseUnderTest.password)
    }

    def cleanupSpec() {
        // DatabaseTestContext reopens a closed connection lazily (its isClosed check), so
        // closing here cannot poison a later stage that reuses the cached connection.
        database?.connection?.close()
    }

    // ===FEATURES===

    def "harness config points at the stand through the dbUrl/dbUsername seam"() {
        expect: "the yaml carries only keys TestConfig knows"
        config.inputFormat == "xml"
        config.context == "testContext"
        !config.revalidateSql

        and: "the getter, not just the yaml, applies the system-property overrides"
        def entry = config.filteredDatabasesUnderTest[0]
        entry.url == System.getProperty("dbUrl")
        entry.username == TrinoTestSupport.user()

        and: "the entry describes our dialect and schema"
        databaseUnderTest.name == "trino"
        databaseUnderTest.version == "464"
        databaseUnderTest.dbSchema == "trino_harness"
    }

    def "initializeDatabase opens a live connection picked as the trino dialect"() {
        expect:
        assert database != null: "initializeDatabase returned null — the harness log above holds the cause"
        database.shortName == "trino"
    }

    def "the harness-opened connection executes queries"() {
        when:
        def executor = Scope.getCurrentScope().getSingleton(ExecutorService)
                .getExecutor("jdbc", database)
        def one = executor.queryForObject(new RawSqlStatement("SELECT 1"), Integer)

        then:
        one == 1
    }

    def "initDB created the lock table through our Trino generator"() {
        expect: "DATABASECHANGELOGLOCK exists in the session schema"
        TrinoTestSupport.queryFirstColumn("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'trino_harness' "
                + "AND table_name = 'databasechangeloglock'") == ["1"]

        and: "and it carries the generator's four columns"
        TrinoTestSupport.queryFirstColumn("SELECT count(*) FROM information_schema.columns "
                + "WHERE table_schema = 'trino_harness' "
                + "AND table_name = 'databasechangeloglock'") == ["4"]
    }

    def "harness.initScript.sql creates the schema and is idempotent"() {
        when:
        TrinoTestSupport.execute(initScriptText())

        then:
        TrinoTestSupport.queryFirstColumn("SELECT count(*) FROM information_schema.schemata "
                + "WHERE schema_name = 'trino_harness'") == ["1"]
    }

    def "the harness session schema is the one the URL named"() {
        when:
        def executor = Scope.getCurrentScope().getSingleton(ExecutorService)
                .getExecutor("jdbc", database)
        def schema = executor.queryForObject(new RawSqlStatement("SELECT current_schema"), String)

        then:
        schema == "trino_harness"
    }

    /**
     * URL for the dbUrl seam. url() names a catalog only, but the harness writes its lock
     * table into the session schema, and Liquibase reads the default schema from the session
     * — for a catalog-only URL Trino reports none and the init() DDL would have nowhere to
     * go. A URL that already names both parts is taken as-is.
     */
    private static String harnessUrl() {
        String base = TrinoTestSupport.url()
        int queryAt = base.indexOf('?')
        String query = queryAt >= 0 ? base.substring(queryAt) : ''
        String path = queryAt >= 0 ? base.substring(0, queryAt) : base
        String authority = path.substring(path.indexOf('//') + 2)
        int slash = authority.indexOf('/')
        String rest = slash >= 0 ? authority.substring(slash) : ''
        List<String> segments = rest.split('/').findAll { !it.isEmpty() }
        if (segments.size() >= 2) {
            return path + query
        }
        if (segments.size() == 1) {
            return path + "/trino_harness" + query
        }
        return path + "/iceberg_catalog/trino_harness" + query
    }

    private static String initScriptText() {
        URL resource = TrinoHarnessConnectionSpec.getResource("/harness.initScript.sql")
        assert resource != null: "harness.initScript.sql is missing from the test classpath"
        return resource.text
    }
}
