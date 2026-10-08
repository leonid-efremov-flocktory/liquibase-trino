package liquibase.ext.trino.golden;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.database.Database;
import liquibase.ext.trino.TrinoTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden test for the SQL this extension generates.
 *
 * <p>Each case is a changelog under {@code golden/changelogs/} and the SQL it is expected to
 * produce, checked in under {@code golden/expected-sql/}. Both halves matter and they cover each
 * other: a verbatim comparison turns any change in generated SQL into a readable diff instead of a
 * mysterious failure on the stand, and applying the changelog catches SQL that Trino rejects —
 * which a comparison alone would happily pass.
 *
 * <p>The expected files are the specification, not a recording of whatever the code last printed.
 * Regenerating one is a deliberate act: read the diff and decide whether the new SQL is still what
 * the changelog means.
 *
 * <p>Every case owns its schema, so the cases share one stand and can run in any order.
 */
@Tag("integration")
@EnabledIf("liquibase.ext.trino.TrinoTestSupport#isReachable")
class TrinoGoldenSqlTest {

    private static final String CHANGELOG = "liquibase/ext/trino/golden/changelogs/%s.xml";
    private static final String EXPECTED_SQL = "liquibase/ext/trino/golden/expected-sql/%s.sql";

    /**
     * Hardcoded on purpose, unlike the rest of the suite. Every changelog here carries
     * {@code <property name="catalog.default" value="iceberg_catalog"/>}, and the generated SQL
     * quotes that value, so the expected files quote it too — deriving it from the stand would
     * mean regenerating all 17 expected files whenever the test catalog is renamed.
     */
    private static final String CATALOG = "iceberg_catalog";

    /**
     * One tracking schema for all cases, dropped before each of them. A per-case schema would work
     * too, but a shared one makes a leftover tracking table from a failed run impossible to miss:
     * the case would replay nothing and fail on its own state assertion.
     */
    private static final String CHANGELOG_SCHEMA = "liquibase_changelog_golden";

    /**
     * More changesets than any case has. {@code rollback} stops when it runs out, so one number
     * covers every case instead of a count per changelog that would silently drift.
     */
    private static final int ROLLBACK_EVERYTHING = 20;

    /**
     * A case, read as a whole.
     *
     * @param name      how the case reports itself; not necessarily the changelog's name
     * @param changelog base name under {@code golden/changelogs/} and {@code golden/expected-sql/}
     * @param schema    the case's own schema, created and dropped by the changelog
     * @param verifySql what "applied correctly" means here, checked against {@code expected}
     * @param expected  the state {@code verifySql} must report once the changelog has run
     */
    private record GoldenCase(String name, String changelog, String schema,
                              String verifySql, String expected) {
    }

    /**
     * {@code verifySql} is each case's own definition of "applied correctly" and is compared with
     * {@code expected}, so the check is per case rather than a uniform object count. The queries are
     * comma-free because {@code count(*)} is used throughout: an empty result set would throw
     * instead of reading as "0".
     * <p>
     * The partitioning case checks its columns rather than its partitioning. Iceberg's
     * {@code t$partitions} metadata table is not reachable over the test connector
     * ({@code ConnectorMetadata getTableHandle() is not implemented}), so partitioning cannot be
     * observed from here. What still guards it is that the golden file records the
     * {@code partitioning = ARRAY['day(ts)']} clause verbatim and that Trino accepted it — a
     * table which was not partitioned would have been created just the same, but the emitted SQL
     * would have differed, and that is the part this layer exists to catch.
     * <p>
     * Column comments are read from {@code information_schema.columns} rather than
     * {@code system.metadata.column_comments}: the latter resolves the table first and the test
     * connector answers {@code ConnectorMetadata getTableHandle() is not implemented}. The table
     * comment comes from {@code system.metadata.table_comments}, which needs no such lookup.
     * <p>
     * Long for a single method, and deliberately not split. Every entry is one line of the same
     * five fields, so the list reads as the table of cases it is; grouping them by changelog or by
     * expected result would hide one case per line and scatter a case's own parts apart.
     */
    private static Stream<GoldenCase> goldenCases() {
        return Stream.of(
                caseOf("createSchema", "golden_create_schema",
                        "SELECT count(*) FROM information_schema.schemata"
                                + " WHERE catalog_name = 'iceberg_catalog' AND schema_name = 'golden_create_schema'", "1"),
                caseOf("dropSchema", "golden_drop_schema",
                        "SELECT count(*) FROM information_schema.schemata"
                                + " WHERE catalog_name = 'iceberg_catalog' AND schema_name = 'golden_drop_schema'", "0"),
                caseOf("createTable", "golden_create_table",
                        "SELECT count(*) FROM information_schema.tables"
                                + " WHERE table_schema = 'golden_create_table' AND table_name = 't'", "1"),
                caseOf("addColumn", "golden_add_column",
                        "SELECT count(*) FROM information_schema.columns"
                                + " WHERE table_schema = 'golden_add_column' AND table_name = 't'", "2"),
                caseOf("dropColumn", "golden_drop_column",
                        "SELECT count(*) FROM information_schema.columns"
                                + " WHERE table_schema = 'golden_drop_column'"
                                + " AND table_name = 't' AND column_name = 'txt'", "0"),
                caseOf("renameColumn", "golden_rename_column",
                        "SELECT count(*) FROM information_schema.columns"
                                + " WHERE table_schema = 'golden_rename_column'"
                                + " AND table_name = 't' AND column_name = 'body'", "1"),
                caseOf("renameTable", "golden_rename_table",
                        "SELECT count(*) FROM information_schema.tables"
                                + " WHERE table_schema = 'golden_rename_table' AND table_name = 't_after'", "1"),
                caseOf("dropTable", "golden_drop_table",
                        "SELECT count(*) FROM information_schema.tables"
                                + " WHERE table_schema = 'golden_drop_table' AND table_name = 't'", "0"),
                caseOf("createView", "golden_create_view",
                        "SELECT count(*) FROM information_schema.views"
                                + " WHERE table_schema = 'golden_create_view' AND table_name = 'v'", "1"),
                caseOf("dropView", "golden_drop_view",
                        "SELECT count(*) FROM information_schema.views"
                                + " WHERE table_schema = 'golden_drop_view' AND table_name = 'v'", "0"),
                caseOf("insert", "golden_insert",
                        "SELECT count(*) FROM iceberg_catalog.golden_insert.t", "2"),
                caseOf("delete", "golden_delete",
                        "SELECT count(*) FROM iceberg_catalog.golden_delete.t", "1"),
                caseOf("sql", "golden_sql",
                        "SELECT count(*) FROM information_schema.tables"
                                + " WHERE table_schema = 'golden_sql' AND table_name = 't'", "1"),
                caseOf("sqlFile", "golden_sql_file",
                        "SELECT count(*) FROM information_schema.tables"
                                + " WHERE table_schema = 'golden_sql_file' AND table_name = 't'", "1"),
                caseOf("partitionedTable", "golden_partitioned",
                        "SELECT count(*) FROM information_schema.columns"
                                + " WHERE table_schema = 'golden_partitioned' AND table_name = 't'", "2"),
                // Two cases over one changelog: the table's own comment and its column comments are
                // read back through different catalogs, so they need different queries.
                caseOf("comments", "golden_comments",
                        "SELECT count(*) FROM system.metadata.table_comments"
                                + " WHERE catalog_name = 'iceberg_catalog' AND schema_name = 'golden_comments'", "1"),
                caseOfSharedSql("commentsColumns", "comments", "golden_comments",
                        "SELECT count(*) FROM information_schema.columns"
                                + " WHERE table_schema = 'golden_comments' AND comment IS NOT NULL", "1"),
                caseOf("complexTypes", "golden_complex_types",
                        "SELECT count(*) FROM information_schema.columns"
                                + " WHERE table_schema = 'golden_complex_types'"
                                + " AND table_name = 't' AND is_nullable = 'NO'", "0"));
    }

    /** A case whose changelog and expected SQL are both named after it. */
    private static GoldenCase caseOf(String name, String schema, String verifySql, String expected) {
        return new GoldenCase(name, name, schema, verifySql, expected);
    }

    /**
     * A case that reads one changelog's expected SQL while asserting something else about it.
     * {@code commentsColumns} checks the column comments of the table whose own comment
     * {@code comments} checks: same changelog, same expected file, different query.
     */
    private static GoldenCase caseOfSharedSql(String name, String changelog, String schema,
                                              String verifySql, String expected) {
        return new GoldenCase(name, changelog, schema, verifySql, expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenCases")
    void generatesSqlVerbatimAndAppliesIt(GoldenCase golden) throws Exception {
        String changelog = String.format(CHANGELOG, golden.changelog());

        Database database = TrinoTestSupport.openChangelogDatabase(CHANGELOG_SCHEMA);
        try {
            resetStand(golden.schema());

            assertEquals(readResource(String.format(EXPECTED_SQL, golden.changelog())),
                    generateSql(database, changelog),
                    "generated SQL does not match the golden file for " + golden.name()
                            + "; regenerate it only if the change is intended");

            TrinoTestSupport.update(changelog, database);

            assertEquals(golden.expected(), TrinoTestSupport.queryFirstColumn(golden.verifySql()).get(0),
                    "the changelog must leave the state " + golden.name() + " claims it does");

            TrinoTestSupport.rollback(changelog, database, ROLLBACK_EVERYTHING);

            assertEquals("0", TrinoTestSupport.queryFirstColumn(
                            "SELECT count(*) FROM information_schema.schemata"
                                    + " WHERE catalog_name = '" + CATALOG + "'"
                                    + " AND schema_name = '" + golden.schema() + "'").get(0),
                    "rollback must remove the schema, which means every changeset rolled back");
        } finally {
            resetStand(golden.schema());
            database.close();
        }
    }

    /**
     * Removes the case schema and the tracking schema, so the case runs from nothing.
     * <p>
     * The tracking schema has to go as well. Left in place, Liquibase would consider the
     * changesets already run, skip them, and run the assertions against an empty schema — the case
     * would fail on its own state check instead of on the thing it means to test. Recreating it
     * empty is safe: Liquibase writes {@code DATABASECHANGELOG} on the next update.
     */
    private static void resetStand(String schema) throws Exception {
        TrinoTestSupport.dropAll(TrinoTestSupport.qualified(CATALOG, CHANGELOG_SCHEMA),
                TrinoTestSupport.qualified(CATALOG, schema));
    }

    /**
     * The SQL the changelog generates, exactly as {@code update-sql} would print it, minus
     * Liquibase's own bookkeeping.
     * <p>
     * The {@link liquibase.Liquibase#updateSql} call is used rather than the {@code updateSql}
     * command with an {@code outputWriter} argument: the command writes to the console instead,
     * which would send the golden text to stdout and leave the comparison with nothing.
     */
    private static String generateSql(Database database, String changelog) throws Exception {
        StringWriter out = new StringWriter();
        TrinoTestSupport.liquibase(changelog, database).updateSql(new Contexts(), new LabelExpression(), out);
        return TrinoTestSupport.withoutLiquibaseBookkeeping(out.toString());
    }

    private static String readResource(String resource) throws Exception {
        Path path = Paths.get("src/test/resources").resolve(resource);
        assertTrue(Files.exists(path),
                "missing golden file " + path + "; write the expected SQL by hand or regenerate it"
                        + " deliberately");
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}