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
     * Each format must reach its own parser and apply the same objects from the same external
     * body file.
     * <p>
     * The parser is pinned because it is what makes the runs meaningfully different: the JSON
     * parser extends the YAML one, so only its name differs — the files do not. The row text
     * asserted below can only come from the body file, so that assertion also proves the file
     * was read rather than skipped, which is the interesting part of
     * {@code relativeToChangelogFile}: the body sits next to the changelog rather than at the
     * root of the classpath.
     * <p>
     * The cases share one tracking table, so the ids are filtered by this format's own prefix:
     * what matters is that the format recorded its changesets in order, not what the other two
     * recorded before it.
     */
    @ParameterizedTest(name = "{0} is parsed by {2}")
    @CsvSource({
            "xml,  liquibase/ext/trino/sqlfile-probe.xml,  XMLChangeLogSAXParser",
            "yaml, liquibase/ext/trino/sqlfile-probe.yaml, YamlChangeLogParser",
            "json, liquibase/ext/trino/sqlfile-probe.json, JsonChangeLogParser",
    })
    void sqlFileChangelogAppliesItsObjects(String format, String changelog, String expectedParser)
            throws Exception {

        assertEquals(expectedParser, parserFor(changelog));

        // A missing body file would fail here as a LiquibaseException before any SQL runs.
        TrinoTestSupport.update(changelog, db);

        assertEquals("2", TrinoTestSupport.count(TrinoTestSupport.SQLFILE_TABLE),
                "the sqlFile body must create the table and insert its rows");
        assertEquals("2", TrinoTestSupport.count(TrinoTestSupport.SQLFILE_VIEW),
                "the sqlFile body must create the view over that table");
        assertEquals(List.of("из sqlFile", "ещё из sqlFile"),
                TrinoTestSupport.queryFirstColumn("SELECT txt FROM "
                        + TrinoTestSupport.SQLFILE_TABLE + " ORDER BY id"),
                "the rows must come from sqlfile-probe-body.sql, resolved relative to the changelog");
        assertEquals(List.of(format + "-sqlfile-setup", format + "-sqlfile-table-and-rows"),
                recordedIds(format),
                "each format must record its own two changesets in execution order");
    }

    /**
     * The rollback block of a {@code <sqlFile>} fixture, in the XML variant only: the three
     * formats hold the same rollback SQL, so running it once is what proves the block is
     * reachable, and the other two are covered by the assertion above.
     */
    @Test
    void xmlSqlFileChangelogRollsBack() throws Exception {
        TrinoTestSupport.update(TrinoTestSupport.CHANGELOG_SQLFILE_XML, db);

        TrinoTestSupport.rollback(TrinoTestSupport.CHANGELOG_SQLFILE_XML, db, 1);

        assertEquals("0", TrinoTestSupport.countObjects(
                TrinoTestSupport.FIXTURE_SCHEMA_NAME, "sqlfile_table"),
                "rolling back the sqlFile changeset must drop the table it created");
        assertEquals("0", TrinoTestSupport.countObjects(
                TrinoTestSupport.FIXTURE_SCHEMA_NAME, "sqlfile_view"),
                "rolling back the sqlFile changeset must drop the view it created");
        assertEquals(List.of("xml-sqlfile-setup"), recordedIds("xml"),
                "only the rolled-back changeset's record must disappear");
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