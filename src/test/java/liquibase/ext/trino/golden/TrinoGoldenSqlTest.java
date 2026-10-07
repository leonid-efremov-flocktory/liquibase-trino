package liquibase.ext.trino.golden;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.ext.trino.TrinoTestSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
@EnabledIf("liquibase.ext.trino.TrinoTestSupport#isReachable")
class TrinoGoldenSqlTest {

    private static final String CHANGELOG = "liquibase/ext/trino/golden/changelogs/%s.xml";
    private static final String EXPECTED_SQL = "liquibase/ext/trino/golden/expected-sql/%s.sql";
    private static final String CATALOG = "iceberg_catalog";

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
     * {@code verifySql} is the case's own definition of "applied correctly" and is compared with
     * {@code expected}, so the check is per case rather than a uniform object count. The queries
     * are comma-free so that the semicolon delimiter below is unambiguous; {@code count(*)} is used
     * throughout because an empty result set would throw instead of reading as "0".
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
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', value = {
            "createSchema;     golden_create_schema;     SELECT count(*) FROM information_schema.schemata WHERE catalog_name = 'iceberg_catalog' AND schema_name = 'golden_create_schema';                              1",
            "dropSchema;       golden_drop_schema;       SELECT count(*) FROM information_schema.schemata WHERE catalog_name = 'iceberg_catalog' AND schema_name = 'golden_drop_schema';                                0",
            "createTable;      golden_create_table;      SELECT count(*) FROM information_schema.tables WHERE table_schema = 'golden_create_table' AND table_name = 't';                                      1",
            "addColumn;        golden_add_column;        SELECT count(*) FROM information_schema.columns WHERE table_schema = 'golden_add_column' AND table_name = 't';                                      2",
            "dropColumn;       golden_drop_column;       SELECT count(*) FROM information_schema.columns WHERE table_schema = 'golden_drop_column' AND table_name = 't' AND column_name = 'txt';                     0",
            "renameColumn;     golden_rename_column;     SELECT count(*) FROM information_schema.columns WHERE table_schema = 'golden_rename_column' AND table_name = 't' AND column_name = 'body';                  1",
            "renameTable;      golden_rename_table;      SELECT count(*) FROM information_schema.tables WHERE table_schema = 'golden_rename_table' AND table_name = 't_after';                                  1",
            "dropTable;        golden_drop_table;        SELECT count(*) FROM information_schema.tables WHERE table_schema = 'golden_drop_table' AND table_name = 't';                                        0",
            "createView;       golden_create_view;       SELECT count(*) FROM information_schema.views WHERE table_schema = 'golden_create_view' AND table_name = 'v';                                       1",
            "dropView;         golden_drop_view;         SELECT count(*) FROM information_schema.views WHERE table_schema = 'golden_drop_view' AND table_name = 'v';                                             0",
            "insert;           golden_insert;            SELECT count(*) FROM iceberg_catalog.golden_insert.t;                                                                                              2",
            "delete;           golden_delete;            SELECT count(*) FROM iceberg_catalog.golden_delete.t;                                                                                              1",
            "sql;              golden_sql;               SELECT count(*) FROM information_schema.tables WHERE table_schema = 'golden_sql' AND table_name = 't';                                           1",
            "sqlFile;          golden_sql_file;          SELECT count(*) FROM information_schema.tables WHERE table_schema = 'golden_sql_file' AND table_name = 't';                                     1",
            "partitionedTable; golden_partitioned;       SELECT count(*) FROM information_schema.columns WHERE table_schema = 'golden_partitioned' AND table_name = 't';                                                    2",
            "comments;         golden_comments;          SELECT count(*) FROM system.metadata.table_comments WHERE catalog_name = 'iceberg_catalog' AND schema_name = 'golden_comments';                   1",
            "commentsColumns;  golden_comments;          SELECT count(*) FROM information_schema.columns WHERE table_schema = 'golden_comments' AND comment IS NOT NULL;                            1",
            "complexTypes;     golden_complex_types;     SELECT count(*) FROM information_schema.columns WHERE table_schema = 'golden_complex_types' AND table_name = 't' AND is_nullable = 'NO';          0",
    })
    void generatesSqlVerbatimAndAppliesIt(String testCase, String schema, String verifySql,
                                          String expected) throws Exception {
        String changelog = String.format(CHANGELOG, changelogFor(testCase));

        Database database = TrinoTestSupport.openChangelogDatabase(CHANGELOG_SCHEMA);
        try {
            resetStand(schema);

            assertEquals(readResource(String.format(EXPECTED_SQL, changelogFor(testCase))),
                    generateSql(database, changelog),
                    "generated SQL does not match the golden file for " + testCase
                            + "; regenerate it only if the change is intended");

            TrinoTestSupport.update(changelog, database);

            assertEquals(expected, TrinoTestSupport.queryFirstColumn(verifySql).get(0),
                    "the changelog must leave the state " + testCase + " claims it does");

            TrinoTestSupport.rollback(changelog, database, ROLLBACK_EVERYTHING);

            assertEquals("0", TrinoTestSupport.queryFirstColumn(
                            "SELECT count(*) FROM information_schema.schemata"
                                    + " WHERE catalog_name = '" + CATALOG + "'"
                                    + " AND schema_name = '" + schema + "'").get(0),
                    "rollback must remove the schema, which means every changeset rolled back");
        } finally {
            resetStand(schema);
            database.close();
        }
    }

    /**
     * The two {@code comments} rows share one changelog but assert different things, so the row
     * name does not have to match the file name.
     */
    private static String changelogFor(String testCase) {
        return testCase.startsWith("comments") ? "comments" : testCase;
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
        TrinoTestSupport.execute("DROP SCHEMA IF EXISTS " + CATALOG + "." + CHANGELOG_SCHEMA + " CASCADE");
        TrinoTestSupport.execute("DROP SCHEMA IF EXISTS " + CATALOG + "." + schema + " CASCADE");
        TrinoTestSupport.execute("CREATE SCHEMA " + CATALOG + "." + CHANGELOG_SCHEMA);
    }

    /**
     * The SQL the changelog generates, exactly as {@code update-sql} would print it, minus
     * Liquibase's own bookkeeping.
     * <p>
     * The {@link Liquibase#updateSql} call is used rather than the {@code updateSql} command with
     * an {@code outputWriter} argument: the command writes to the console instead, which would send
     * the golden text to stdout and leave the comparison with nothing.
     */
    private static String generateSql(Database database, String changelog) throws Exception {
        StringWriter out = new StringWriter();
        TrinoTestSupport.liquibase(changelog, database).updateSql(new Contexts(), new LabelExpression(), out);
        return withoutLiquibaseBookkeeping(out.toString());
    }

    /**
     * Drops everything {@code update-sql} emits that is not the case's own SQL.
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
    static String withoutLiquibaseBookkeeping(String updateSqlOutput) {
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

    private static String readResource(String resource) throws Exception {
        Path path = Paths.get("src/test/resources").resolve(resource);
        assertEquals(true, Files.exists(path),
                "missing golden file " + path + "; write the expected SQL by hand or regenerate it"
                        + " deliberately");
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}