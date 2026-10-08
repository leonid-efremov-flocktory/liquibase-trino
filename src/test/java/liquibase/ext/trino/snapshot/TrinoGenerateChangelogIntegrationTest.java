package liquibase.ext.trino.snapshot;

import liquibase.CatalogAndSchema;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.ext.trino.TrinoTestSupport;
import liquibase.ext.trino.TrinoTestSupport.Generated;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code generate-changelog} command end to end, plus the round trip that proves the output
 * is usable rather than merely printable.
 * <p>
 * The generator emits verbatim {@code SHOW CREATE} text as raw SQL instead of the structured
 * {@code <createTable>} the base generator produces. That choice is only justified if the text
 * is exactly what the server said and can be replayed, and those are the two things asserted here:
 * the changelog body is compared character for character against {@code SHOW CREATE}, and then
 * dropped, re-applied from the generated file, and read back.
 */
@Tag("integration")
@EnabledIf("liquibase.ext.trino.TrinoTestSupport#isReachable")
class TrinoGenerateChangelogIntegrationTest {

    private static final String SCHEMA = "generate_changelog_rt";
    private static final String TABLE = "rt_table";
    private static final String VIEW = "rt_view";

    /** Tracking schema for the generated changelog, kept out of the generated schema itself. */
    private static final String CHANGELOG_SCHEMA = "generate_changelog_rt_meta";

    private static String catalog;
    private static String qualified;
    private static Database db;

    /** Both schemas are recreated by {@link #setup()} on every run, but not left behind on failure. */
    @AfterAll
    static void dropProbeSchemas() throws Exception {
        TrinoTestSupport.dropSchemas(qualified, TrinoTestSupport.qualified(catalog, CHANGELOG_SCHEMA));
    }

    @BeforeAll
    static void setup() throws Exception {
        TrinoTestSupport.applyIfNeeded();
        catalog = TrinoTestSupport.catalog();
        qualified = TrinoTestSupport.qualified(catalog, SCHEMA);
        db = TrinoTestSupport.openChangelogDatabase();

        TrinoTestSupport.resetSchemas(qualified, TrinoTestSupport.qualified(catalog, CHANGELOG_SCHEMA));
        TrinoTestSupport.execute("CREATE TABLE " + qualified + "." + TABLE + " ("
                + "id integer, tags array(varchar), payload row(a integer, b varchar), ts timestamp(6)) "
                + "WITH (format = 'PARQUET', partitioning = ARRAY['day(ts)'])");
        TrinoTestSupport.execute("COMMENT ON TABLE " + qualified + "." + TABLE + " IS 'Таблица для round-trip'");
        TrinoTestSupport.execute("COMMENT ON COLUMN " + qualified + "." + TABLE + ".id IS 'Ключ'");
        TrinoTestSupport.execute("CREATE VIEW " + qualified + "." + VIEW
                + " COMMENT 'Вью для round-trip' SECURITY DEFINER AS SELECT id FROM " + qualified + "." + TABLE);
    }

    /**
     * The generated file must carry the server's DDL unchanged. A structured
     * {@code <createTable>} cannot do this: it has nowhere to put {@code partitioning}, the table
     * and column comments, or {@code format_version}, all of which {@code SHOW CREATE} returns
     * and all of which Trino needs to recreate the object.
     */
    @Test
    void generatedChangelogContainsVerbatimDdl() throws Exception {
        String changelog = generate().text();

        assertTrue(changelog.contains("CREATE TABLE " + qualified + "." + TABLE),
                "the table DDL must be in the changelog:\n" + changelog);
        assertTrue(changelog.contains("CREATE VIEW"), "the view DDL must be in the changelog:\n" + changelog);
        assertTrue(changelog.contains("partitioning = ARRAY['day(ts)']"),
                "connector properties must survive:\n" + changelog);
        assertTrue(changelog.contains("format = 'PARQUET'"), "the format must survive:\n" + changelog);
        assertTrue(changelog.contains("Таблица для round-trip"), "the table comment must survive:\n" + changelog);
        assertTrue(changelog.contains("Ключ"), "the column comment must survive:\n" + changelog);
        assertTrue(changelog.contains("SECURITY DEFINER"), "the view security mode must survive:\n" + changelog);

        // The point of the change generator: verbatim text, not the structural form.
        assertFalse(changelog.contains("<createTable"), "the structural form must not be used:\n" + changelog);
        assertFalse(changelog.contains("<createView"), "the structural form must not be used:\n" + changelog);
    }

    /**
     * The generated body has to be the server's text verbatim, not a reconstruction of it.
     * Compared against a fresh {@code SHOW CREATE} so that the test breaks if the generator ever
     * starts assembling DDL itself instead of passing the server's output through.
     */
    @Test
    void generatedSqlMatchesShowCreateCharacterForCharacter() throws Exception {
        String changelog = generate().text();
        String tableDdl = showCreateTable();
        String viewDdl = showCreateView();

        assertTrue(changelog.contains(tableDdl),
                "the table DDL must appear in the changelog unchanged.\n--- from SHOW CREATE ---\n"
                        + tableDdl + "\n--- from changelog ---\n" + changelog);
        assertTrue(changelog.contains(viewDdl),
                "the view DDL must appear in the changelog unchanged.\n--- from SHOW CREATE ---\n"
                        + viewDdl + "\n--- from changelog ---\n" + changelog);
    }

    /**
     * The only check that the DDL is executable: generate, drop, apply, read back.
     * <p>
     * The replay lands in the same schema on purpose. The DDL carries the S3 {@code location} of
     * the table it was taken from, and Trino rejects a {@code location} that already holds a
     * table — so applying this changelog anywhere else would fail. That is a known consequence of
     * emitting the DDL verbatim, documented in the README, not something this test can fix.
     */
    @Test
    void generatedChangelogReplaysToIdenticalDdl() throws Exception {
        File file = generate().file();
        String tableBefore = showCreateTable();
        String viewBefore = showCreateView();

        TrinoTestSupport.execute("DROP VIEW " + qualified + "." + VIEW);
        TrinoTestSupport.execute("DROP TABLE " + qualified + "." + TABLE);
        assertEquals("0", TrinoTestSupport.countObjects(SCHEMA, TABLE),
                "the table must really be gone before the replay");

        try (Database replayDb = TrinoTestSupport.openChangelogDatabase(CHANGELOG_SCHEMA)) {
            new Liquibase(file.getName(), new DirectoryResourceAccessor(file.getParentFile()), replayDb)
                    .update(new Contexts(), new LabelExpression());
        }

        assertEquals(tableBefore, showCreateTable(),
                "the replayed table must have exactly the DDL it had before");
        assertEquals(viewBefore, showCreateView(),
                "the replayed view must have exactly the DDL it had before");
    }

    /** Reads the table's DDL straight from the server. */
    private static String showCreateTable() throws Exception {
        return trinoDatabase().getTableDefinition(new CatalogAndSchema(catalog, SCHEMA), TABLE);
    }

    /** Reads the view's DDL straight from the server. */
    private static String showCreateView() throws Exception {
        return trinoDatabase().getViewDdl(new CatalogAndSchema(catalog, SCHEMA), VIEW);
    }

    private static liquibase.ext.trino.database.TrinoDatabase trinoDatabase() {
        return (liquibase.ext.trino.database.TrinoDatabase) db;
    }

    private static Generated generate() throws Exception {
        return TrinoTestSupport.generateChangelog(SCHEMA, "generate-changelog-");
    }
}