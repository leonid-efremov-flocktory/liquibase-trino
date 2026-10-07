package liquibase.ext.trino.changelog;

import liquibase.database.Database;
import liquibase.ext.trino.TrinoTestSupport;
import liquibase.parser.ChangeLogParser;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Changelog formats other than the main XML fixture: {@code <sqlFile>} in XML and the same
 * fixture in YAML and JSON.
 * <p>
 * Milestone 1 of the "add a database" guide asks that xml/yaml/json changelogs built only from
 * {@code <sql>} and {@code <sqlFile>} behave the same. Nothing in the plugin is
 * format-specific, so what these check is the seam the guide cares about: property expansion
 * and relative resource paths must resolve identically when the SQL lives in a separate file
 * rather than in a formatted-SQL include.
 * <p>
 * These fixtures record their changesets in {@link TrinoTestSupport#SQLFILE_CHANGELOG_SCHEMA},
 * their own tracking schema, so applying them neither reads nor destroys the main fixture's
 * tracking table. Without that separation this class would have to drop
 * {@code liquibase_changelog} between cases, and the classes reading the main fixture would
 * then have to re-apply it — which is why they used to run the same update three times over.
 */
@EnabledIf("liquibase.ext.trino.TrinoTestSupport#isReachable")
class TrinoChangelogFormatIntegrationTest {

    private static Database db;

    @BeforeAll
    static void setUp() throws Exception {
        TrinoTestSupport.execute("CREATE SCHEMA IF NOT EXISTS " + TrinoTestSupport.SQLFILE_CHANGELOG_SCHEMA);
        db = TrinoTestSupport.openChangelogDatabase(TrinoTestSupport.SQLFILE_CHANGELOG_SCHEMA);
    }

    @AfterAll
    static void tearDown() throws Exception {
        TrinoTestSupport.execute("DROP SCHEMA IF EXISTS " + TrinoTestSupport.SQLFILE_CHANGELOG_SCHEMA + " CASCADE");
        TrinoTestSupport.execute("DROP VIEW IF EXISTS " + TrinoTestSupport.SQLFILE_VIEW);
        TrinoTestSupport.execute("DROP TABLE IF EXISTS " + TrinoTestSupport.SQLFILE_TABLE);
    }

    /**
     * Each format must reach its own parser, apply its objects from the external body file, and
     * record its changesets — and the XML variant additionally runs the structured
     * {@code <insert>} / {@code <delete>} changes.
     * <p>
     * The parser is pinned because it is what makes the runs meaningfully different: the JSON
     * parser extends the YAML one, so only its name differs — the files do not.
     * <p>
     * The stand is reset before each case: the three fixtures build the same table in the same
     * schema, so without a reset the second case would find the first one's rows still there and
     * its own counts would be wrong.
     */
    @ParameterizedTest(name = "{0} is parsed by {2}")
    // Semicolon-separated: the row texts and the id lists contain commas, which CSV would split.
    @CsvSource(delimiter = ';', value = {
            // sqlFile body inserts 10 and 11; <insert> adds 20 and 21; <delete> removes 11.
            "xml;  liquibase/ext/trino/sqlfile-probe.xml;  XMLChangeLogSAXParser; 4; 3; из sqlFile, из insert, ещё из insert",
            "yaml; liquibase/ext/trino/sqlfile-probe.yaml; YamlChangeLogParser; 2; 2; из sqlFile, ещё из sqlFile",
            "json; liquibase/ext/trino/sqlfile-probe.json; JsonChangeLogParser; 2; 2; из sqlFile, ещё из sqlFile",
    })
    void sqlFileChangelogAppliesItsObjects(String format, String changelog, String expectedParser,
                                           int expectedChangesets, int expectedRows,
                                           String expectedRowTexts) throws Exception {

        assertEquals(expectedParser, parserFor(changelog));

        resetProbeState();
        // A missing body file would fail here as a LiquibaseException before any SQL runs.
        TrinoTestSupport.update(changelog, db);

        assertEquals("1", TrinoTestSupport.countObjects(
                TrinoTestSupport.FIXTURE_SCHEMA_NAME, "sqlfile_table"),
                "the sqlFile body must create the table");
        assertEquals("1", TrinoTestSupport.countObjects(
                TrinoTestSupport.FIXTURE_SCHEMA_NAME, "sqlfile_view"),
                "the sqlFile body must create the view over that table");

        assertEquals(List.of(expectedRowTexts.split(", ")),
                TrinoTestSupport.queryFirstColumn("SELECT txt FROM "
                        + TrinoTestSupport.SQLFILE_TABLE + " ORDER BY id"),
                "the rows must come from sqlfile-probe-body.sql, resolved relative to the changelog,"
                        + " plus whatever the structured changes added or removed");
        assertEquals(expectedRows, Integer.parseInt(TrinoTestSupport.count(TrinoTestSupport.SQLFILE_TABLE)),
                "the table must hold as many rows as the row list above");

        // The XML fixture also has the data changes; the other two stop at the sqlFile body.
        assertEquals(changelog.equals(TrinoTestSupport.CHANGELOG_SQLFILE_XML)
                        ? List.of("xml-sqlfile-setup", "xml-sqlfile-table-and-rows",
                        "xml-sqlfile-insert", "xml-sqlfile-delete")
                        : List.of(format + "-sqlfile-setup", format + "-sqlfile-table-and-rows"),
                recordedIds(format),
                "each format must record its own " + expectedChangesets
                        + " changesets in execution order");
    }

    /**
     * The rollback blocks of the XML fixture: the {@code <delete>} changeset's {@code <insert>}
     * and the {@code <sqlFile>} changeset's {@code DROP}. Both are here because the structured
     * data changes have their own, separate rollback path from raw SQL.
     */
    @Test
    void xmlSqlFileChangelogRollsBack() throws Exception {
        resetProbeState();
        TrinoTestSupport.update(TrinoTestSupport.CHANGELOG_SQLFILE_XML, db);

        TrinoTestSupport.rollback(TrinoTestSupport.CHANGELOG_SQLFILE_XML, db, 1);

        assertEquals(List.of("из sqlFile", "ещё из sqlFile", "из insert", "ещё из insert"),
                TrinoTestSupport.queryFirstColumn("SELECT txt FROM "
                        + TrinoTestSupport.SQLFILE_TABLE + " ORDER BY id"),
                "rolling back the <delete> changeset must put its row back through its <insert>");

        TrinoTestSupport.rollback(TrinoTestSupport.CHANGELOG_SQLFILE_XML, db, 2);

        assertEquals("0", TrinoTestSupport.countObjects(
                TrinoTestSupport.FIXTURE_SCHEMA_NAME, "sqlfile_table"),
                "rolling back the sqlFile changeset must drop the table it created");
        assertEquals("0", TrinoTestSupport.countObjects(
                TrinoTestSupport.FIXTURE_SCHEMA_NAME, "sqlfile_view"),
                "rolling back the sqlFile changeset must drop the view it created");
        assertEquals(List.of("xml-sqlfile-setup"), recordedIds("xml"),
                "only the rolled-back changesets' records must disappear");
    }

    /**
     * Returns the stand to a state where the next update applies every changeset from scratch:
     * the probe objects and the tracking tables both go.
     * <p>
     * The tracking schema has to go too. Leaving it behind would mean Liquibase saw the changesets
     * as already run and skipped them, while the objects they create are gone — so the case would
     * run against an empty schema and fail on the first assertion instead of on the thing it means
     * to test. Recreating it empty is safe: Liquibase writes DATABASECHANGELOG on the next update.
     */
    private static void resetProbeState() throws Exception {
        TrinoTestSupport.execute("DROP SCHEMA IF EXISTS " + TrinoTestSupport.SQLFILE_CHANGELOG_SCHEMA + " CASCADE");
        TrinoTestSupport.execute("CREATE SCHEMA " + TrinoTestSupport.SQLFILE_CHANGELOG_SCHEMA);
        TrinoTestSupport.execute("DROP VIEW IF EXISTS " + TrinoTestSupport.SQLFILE_VIEW);
        TrinoTestSupport.execute("DROP TABLE IF EXISTS " + TrinoTestSupport.SQLFILE_TABLE);
    }

    /** The ids this format recorded, in execution order; the other two formats' are ignored. */
    private static List<String> recordedIds(String format) throws Exception {
        return TrinoTestSupport.queryFirstColumn("SELECT id FROM "
                + TrinoTestSupport.SQLFILE_CHANGELOG_SCHEMA + ".databasechangelog"
                + " WHERE id LIKE '" + format + "-sqlfile-%' ORDER BY orderexecuted");
    }

    private static String parserFor(String changelog) {
        ChangeLogParser parser;
        try {
            parser = ChangeLogParserFactory.getInstance()
                    .getParser(changelog, new ClassLoaderResourceAccessor());
        } catch (Exception e) {
            throw new AssertionError("no parser could be created for " + changelog, e);
        }
        assertNotNull(parser, "no parser found for " + changelog);
        return parser.getClass().getSimpleName();
    }
}