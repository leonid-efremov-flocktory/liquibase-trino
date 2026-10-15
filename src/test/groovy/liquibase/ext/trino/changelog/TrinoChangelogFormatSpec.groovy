package liquibase.ext.trino.changelog

import liquibase.database.Database
import liquibase.ext.trino.TrinoIntegrationSpec
import liquibase.ext.trino.TrinoTestSupport
import liquibase.parser.ChangeLogParser
import liquibase.parser.ChangeLogParserFactory
import liquibase.resource.ClassLoaderResourceAccessor
import spock.lang.Shared

/**
 * Changelog formats other than the main XML fixture: {@code <sqlFile>} in XML and the same
 * fixture in YAML and JSON.
 *
 * <p>Milestone 1 of the "add a database" guide asks that xml/yaml/json changelogs built only from
 * {@code <sql>} and {@code <sqlFile>} behave the same. Nothing in the plugin is
 * format-specific, so what these check is the seam the guide cares about: property expansion
 * and relative resource paths must resolve identically when the SQL lives in a separate file
 * rather than in a formatted-SQL include.
 *
 * <p>These fixtures record their changesets in {@link TrinoTestSupport#SQLFILE_CHANGELOG_SCHEMA},
 * their own tracking schema, so applying them neither reads nor destroys the main fixture's
 * tracking table. Without that separation this class would have to drop
 * {@code liquibase_changelog} between cases, and the classes reading the main fixture would
 * then have to re-apply it — which is why they used to run the same update three times over.
 */
class TrinoChangelogFormatSpec extends TrinoIntegrationSpec {

    @Shared
    Database db

    def setupSpec() {
        TrinoTestSupport.execute("CREATE SCHEMA IF NOT EXISTS " + TrinoTestSupport.SQLFILE_CHANGELOG_SCHEMA)
        db = TrinoTestSupport.openChangelogDatabase(TrinoTestSupport.SQLFILE_CHANGELOG_SCHEMA)
    }

    def cleanupSpec() {
        TrinoTestSupport.execute("DROP SCHEMA IF EXISTS " + TrinoTestSupport.SQLFILE_CHANGELOG_SCHEMA + " CASCADE")
        TrinoTestSupport.execute("DROP VIEW IF EXISTS " + TrinoTestSupport.SQLFILE_VIEW)
        TrinoTestSupport.execute("DROP TABLE IF EXISTS " + TrinoTestSupport.SQLFILE_TABLE)
    }

    /**
     * Each format must reach its own parser, apply its objects from the external body file, and
     * record its changesets — and the XML variant additionally runs the structured
     * {@code <insert>} / {@code <delete>} changes.
     *
     * <p>The parser is pinned because it is what makes the runs meaningfully different: the JSON
     * parser extends the YAML one, so only its name differs — the files do not.
     *
     * <p>The stand is reset before each case: the three fixtures build the same table in the same
     * schema, so without a reset the second case would find the first one's rows still there and
     * its own counts would be wrong.
     */
    def "the #format sqlFile changelog applies its objects"() {
        expect: "each format reaches its own parser"
        parserFor(changelog) == expectedParser

        when: "the fixture is applied from scratch"
        resetProbeState()
        // A missing body file would fail here as a LiquibaseException before any SQL runs.
        TrinoTestSupport.update(changelog, db)

        then: "the sqlFile body created the table and the view over it"
        TrinoTestSupport.countObjects(TrinoTestSupport.FIXTURE_SCHEMA_NAME, "sqlfile_table") == "1"
        TrinoTestSupport.countObjects(TrinoTestSupport.FIXTURE_SCHEMA_NAME, "sqlfile_view") == "1"

        and: "the rows come from body.sql, resolved relative to the changelog, plus the structured changes"
        TrinoTestSupport.queryFirstColumn("SELECT txt FROM " + TrinoTestSupport.SQLFILE_TABLE + " ORDER BY id")
                == expectedRowTexts
        Integer.parseInt(TrinoTestSupport.count(TrinoTestSupport.SQLFILE_TABLE)) == expectedRows

        and: "each format records its own changesets in execution order"
        recordedIds(format) == expectedIds

        where:
        format | changelog                              | expectedParser          | expectedIds                        | expectedRows | expectedRowTexts
        // sqlFile body inserts 10 and 11; <insert> adds 20 and 21; <delete> removes 11.
        "xml"  | TrinoTestSupport.CHANGELOG_SQLFILE_XML | "XMLChangeLogSAXParser" | ["xml-sqlfile-setup", "xml-sqlfile-table-and-rows", "xml-sqlfile-insert", "xml-sqlfile-delete"] | 3 | ["из sqlFile", "из insert", "ещё из insert"]
        "yaml" | TrinoTestSupport.CHANGELOG_SQLFILE_YAML | "YamlChangeLogParser"  | ["yaml-sqlfile-setup", "yaml-sqlfile-table-and-rows"] | 2 | ["из sqlFile", "ещё из sqlFile"]
        "json" | TrinoTestSupport.CHANGELOG_SQLFILE_JSON | "JsonChangeLogParser"  | ["json-sqlfile-setup", "json-sqlfile-table-and-rows"] | 2 | ["из sqlFile", "ещё из sqlFile"]
    }

    /**
     * The rollback blocks of the XML fixture: the {@code <delete>} changeset's {@code <insert>}
     * and the {@code <sqlFile>} changeset's {@code DROP}. Both are here because the structured
     * data changes have their own, separate rollback path from raw SQL.
     */
    def "the xml sqlFile changelog rolls back"() {
        when: "the fixture is applied and the <delete> changeset is rolled back"
        resetProbeState()
        TrinoTestSupport.update(TrinoTestSupport.CHANGELOG_SQLFILE_XML, db)
        TrinoTestSupport.rollback(TrinoTestSupport.CHANGELOG_SQLFILE_XML, db, 1)

        then: "rolling back <delete> puts its row back through its <insert>"
        TrinoTestSupport.queryFirstColumn("SELECT txt FROM " + TrinoTestSupport.SQLFILE_TABLE + " ORDER BY id")
                == ["из sqlFile", "ещё из sqlFile", "из insert", "ещё из insert"]

        when: "the two remaining changesets are rolled back"
        TrinoTestSupport.rollback(TrinoTestSupport.CHANGELOG_SQLFILE_XML, db, 2)

        then: "the sqlFile changeset drops the table and view it created"
        TrinoTestSupport.countObjects(TrinoTestSupport.FIXTURE_SCHEMA_NAME, "sqlfile_table") == "0"
        TrinoTestSupport.countObjects(TrinoTestSupport.FIXTURE_SCHEMA_NAME, "sqlfile_view") == "0"
        recordedIds("xml") == ["xml-sqlfile-setup"]
    }

    /**
     * Returns the stand to a state where the next update applies every changeset from scratch:
     * the probe objects and the tracking tables both go.
     *
     * <p>The tracking schema has to go too. Leaving it behind would mean Liquibase saw the changesets
     * as already run and skipped them, while the objects they create are gone — so the case would
     * run against an empty schema and fail on the first assertion instead of on the thing it means
     * to test. Recreating it empty is safe: Liquibase writes DATABASECHANGELOG on the next update.
     */
    private static void resetProbeState() {
        TrinoTestSupport.resetSchemas(TrinoTestSupport.SQLFILE_CHANGELOG_SCHEMA)
        TrinoTestSupport.execute("DROP VIEW IF EXISTS " + TrinoTestSupport.SQLFILE_VIEW)
        TrinoTestSupport.execute("DROP TABLE IF EXISTS " + TrinoTestSupport.SQLFILE_TABLE)
    }

    /** The ids this format recorded, in execution order; the other two formats' are ignored. */
    private static List<String> recordedIds(String format) {
        return TrinoTestSupport.queryFirstColumn("SELECT id FROM "
                + TrinoTestSupport.SQLFILE_CHANGELOG_SCHEMA + ".databasechangelog"
                + " WHERE id LIKE '" + format + "-sqlfile-%' ORDER BY orderexecuted")
    }

    private static String parserFor(String changelog) {
        ChangeLogParser parser
        try {
            parser = ChangeLogParserFactory.getInstance()
                    .getParser(changelog, new ClassLoaderResourceAccessor())
        } catch (Exception e) {
            throw new AssertionError("no parser could be created for " + changelog, e)
        }
        assert parser != null: "no parser found for " + changelog
        return parser.getClass().getSimpleName()
    }
}
