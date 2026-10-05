package liquibase.ext.trino.changelog;

import liquibase.database.Database;
import liquibase.ext.trino.TrinoTestSupport;
import liquibase.parser.ChangeLogParser;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
 * All three fixtures share the {@code dev_test_schema} of the main one and their own
 * {@code sqlfile_table}/{@code sqlfile_view}, so each starts by dropping whatever is there.
 */
class TrinoChangelogFormatIntegrationTest {

    private static Database db;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(TrinoTestSupport.isReachable(), "Trino is unreachable: " + TrinoTestSupport.url());
        TrinoTestSupport.execute("CREATE SCHEMA IF NOT EXISTS " + TrinoTestSupport.CHANGELOG_SCHEMA);
        db = TrinoTestSupport.openChangelogDatabase();
    }

    @AfterEach
    void tearDown() throws Exception {
        // The guard repeats the assumeTrue in setUp(): a failed assumption skips @AfterEach in
        // JUnit, so without it cleanup would fail instead of skipping.
        assumeTrue(TrinoTestSupport.isReachable(), "Trino is unreachable: " + TrinoTestSupport.url());
        // Drop the tracking tables so the next parameterised case starts from zero. These
        // fixtures are applied from scratch rather than through applyIfNeeded(): each format
        // records changesets of its own ids, and a shared tracking table would make the
        // "only my changesets are recorded" assertion depend on the order cases ran in.
        TrinoTestSupport.execute("DROP SCHEMA IF EXISTS " + TrinoTestSupport.CHANGELOG_SCHEMA + " CASCADE");
        TrinoTestSupport.execute("CREATE SCHEMA IF NOT EXISTS " + TrinoTestSupport.CHANGELOG_SCHEMA);
    }

    /**
     * Each format must reach its own parser. The row text asserted below can only come from the
     * body file, so that check also proves the file was read rather than skipped; this one pins
     * the parser choice, which is what makes the runs meaningfully different. Note that the JSON
     * parser extends the YAML one, so only its name differs — the files do not.
     */
    @ParameterizedTest(name = "{0} is parsed by {1}")
    @CsvSource({
            "liquibase/ext/trino/sqlfile-probe.xml,  XMLChangeLogSAXParser",
            "liquibase/ext/trino/sqlfile-probe.yaml, YamlChangeLogParser",
            "liquibase/ext/trino/sqlfile-probe.json, JsonChangeLogParser",
    })
    void eachFormatIsParsedByItsOwnParser(String changelog, String expectedParser) {
        assertEquals(expectedParser, parserFor(changelog));
    }

    /**
     * The interesting part of {@code relativeToChangelogFile}: the body file sits next to the
     * changelog rather than at the root of the classpath, so its path only resolves if Liquibase
     * resolves it against the changelog's own location.
     */
    @ParameterizedTest(name = "{0} applies its sqlFile changesets")
    @ValueSource(strings = {"xml", "yaml", "json"})
    void sqlFileChangelogCreatesItsObjects(String format) throws Exception {
        // A missing body file would fail here as a LiquibaseException before any SQL runs.
        TrinoTestSupport.update(changelogFor(format), db);

        assertEquals("2", TrinoTestSupport.count(TrinoTestSupport.SQLFILE_TABLE),
                "the sqlFile body must create the table and insert its rows");
        assertEquals("2", TrinoTestSupport.count(TrinoTestSupport.SQLFILE_VIEW),
                "the sqlFile body must create the view over that table");
        assertEquals(List.of("из sqlFile", "ещё из sqlFile"),
                TrinoTestSupport.queryFirstColumn("SELECT txt FROM "
                        + TrinoTestSupport.SQLFILE_TABLE + " ORDER BY id"),
                "the rows must come from sqlfile-probe-body.sql, resolved relative to the changelog");
        // Filtered by the probe's own id prefix: the tracking table is shared with the main
        // fixture, so asserting on all of its rows would depend on which classes ran before this
        // one. What matters here is that this format recorded its changesets under its own ids.
        assertEquals(List.of(format + "-sqlfile-setup", format + "-sqlfile-table-and-rows"),
                TrinoTestSupport.queryFirstColumn("SELECT id FROM "
                        + TrinoTestSupport.CHANGELOG_SCHEMA + ".databasechangelog"
                        + " WHERE id LIKE '%-sqlfile-%' ORDER BY orderexecuted"),
                "each format must record its own two changesets in execution order");
    }

    @ParameterizedTest(name = "{0} rolls its sqlFile changesets back")
    @ValueSource(strings = {"xml", "yaml", "json"})
    void sqlFileChangelogRollsBack(String format) throws Exception {
        TrinoTestSupport.update(changelogFor(format), db);

        TrinoTestSupport.rollback(changelogFor(format), db, 1);

        assertEquals("0", TrinoTestSupport.countObjects(
                TrinoTestSupport.FIXTURE_SCHEMA_NAME, "sqlfile_table"),
                "rolling back the sqlFile changeset must drop the table it created");
        assertEquals("0", TrinoTestSupport.countObjects(
                TrinoTestSupport.FIXTURE_SCHEMA_NAME, "sqlfile_view"),
                "rolling back the sqlFile changeset must drop the view it created");
        assertEquals(List.of(format + "-sqlfile-setup"),
                TrinoTestSupport.queryFirstColumn("SELECT id FROM "
                        + TrinoTestSupport.CHANGELOG_SCHEMA + ".databasechangelog"
                        + " WHERE id LIKE '%-sqlfile-%' ORDER BY orderexecuted"),
                "only the rolled-back changeset's record must disappear");
    }

    private static String changelogFor(String format) {
        return switch (format) {
            case "xml" -> TrinoTestSupport.CHANGELOG_SQLFILE_XML;
            case "yaml" -> TrinoTestSupport.CHANGELOG_SQLFILE_YAML;
            case "json" -> TrinoTestSupport.CHANGELOG_SQLFILE_JSON;
            default -> throw new AssertionError("unknown changelog format: " + format);
        };
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