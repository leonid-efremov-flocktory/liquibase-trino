package liquibase.ext.trino.snapshot;

import liquibase.CatalogAndSchema;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.command.CommandScope;
import liquibase.command.core.GenerateChangelogCommandStep;
import liquibase.database.Database;
import liquibase.ext.trino.TrinoTestSupport;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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
@EnabledIf("liquibase.ext.trino.TrinoTestSupport#isReachable")
class TrinoGenerateChangelogIntegrationTest {

    private static final String CATALOG = "iceberg_catalog";
    private static final String SCHEMA = "generate_changelog_rt";
    private static final String TABLE = "rt_table";
    private static final String VIEW = "rt_view";
    private static final String QUALIFIED = CATALOG + "." + SCHEMA;

    /** Tracking schema for the generated changelog, kept out of the generated schema itself. */
    private static final String CHANGELOG_SCHEMA = "generate_changelog_rt_meta";

    private static Database db;

    @BeforeAll
    static void setup() throws Exception {
        TrinoTestSupport.applyIfNeeded();
        db = TrinoTestSupport.openChangelogDatabase();

        TrinoTestSupport.execute("DROP SCHEMA IF EXISTS " + QUALIFIED + " CASCADE");
        TrinoTestSupport.execute("DROP SCHEMA IF EXISTS " + CATALOG + "." + CHANGELOG_SCHEMA + " CASCADE");
        TrinoTestSupport.execute("CREATE SCHEMA " + QUALIFIED);
        TrinoTestSupport.execute("CREATE SCHEMA " + CATALOG + "." + CHANGELOG_SCHEMA);
        TrinoTestSupport.execute("CREATE TABLE " + QUALIFIED + "." + TABLE + " ("
                + "id integer, tags array(varchar), payload row(a integer, b varchar), ts timestamp(6)) "
                + "WITH (format = 'PARQUET', partitioning = ARRAY['day(ts)'])");
        TrinoTestSupport.execute("COMMENT ON TABLE " + QUALIFIED + "." + TABLE + " IS 'Таблица для round-trip'");
        TrinoTestSupport.execute("COMMENT ON COLUMN " + QUALIFIED + "." + TABLE + ".id IS 'Ключ'");
        TrinoTestSupport.execute("CREATE VIEW " + QUALIFIED + "." + VIEW
                + " COMMENT 'Вью для round-trip' SECURITY DEFINER AS SELECT id FROM " + QUALIFIED + "." + TABLE);
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

        assertTrue(changelog.contains("CREATE TABLE " + QUALIFIED + "." + TABLE),
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

        TrinoTestSupport.execute("DROP VIEW " + QUALIFIED + "." + VIEW);
        TrinoTestSupport.execute("DROP TABLE " + QUALIFIED + "." + TABLE);
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
        return trinoDatabase().getTableDefinition(new CatalogAndSchema(CATALOG, SCHEMA), TABLE);
    }

    /** Reads the view's DDL straight from the server. */
    private static String showCreateView() throws Exception {
        return trinoDatabase().getViewDdl(new CatalogAndSchema(CATALOG, SCHEMA), VIEW);
    }

    private static liquibase.ext.trino.database.TrinoDatabase trinoDatabase() {
        return (liquibase.ext.trino.database.TrinoDatabase) db;
    }

    /**
     * Runs the command into a file under {@code target/}. Not {@code java.io.tmpdir}: the test
     * JVM runs inside a container, and the file has to be readable by the Liquibase run that
     * follows in the same JVM.
     * <p>
     * The {@code .trino.sql} suffix is required, not cosmetic: Liquibase picks the SQL serializer
     * from the extension and refuses a bare {@code .sql} name because it cannot tell which
     * dialect's SQL formatting to apply.
     */
    private static Generated generate() throws Exception {
        File file = new File("target", "generate-changelog-" + System.nanoTime() + ".trino.sql");
        new CommandScope(GenerateChangelogCommandStep.COMMAND_NAME[0])
                .addArgumentValue("url", TrinoTestSupport.url())
                .addArgumentValue("username", TrinoTestSupport.user())
                .addArgumentValue(GenerateChangelogCommandStep.REFERENCE_SCHEMAS_ARG, SCHEMA)
                .addArgumentValue(GenerateChangelogCommandStep.CHANGELOG_FILE_ARG, file.getAbsolutePath())
                .execute();
        return new Generated(file, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    /** A generated changelog: where it landed and what is in it. */
    private record Generated(File file, String text) {
    }
}