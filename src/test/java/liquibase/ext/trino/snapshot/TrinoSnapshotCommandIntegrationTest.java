package liquibase.ext.trino.snapshot;

import liquibase.command.CommandScope;
import liquibase.command.core.SnapshotCommandStep;
import liquibase.ext.trino.TrinoTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code snapshot} command end to end, as a user runs it.
 * <p>
 * The plugin registers its own snapshot generators plus an overridden schema generator, and a
 * bug in any of them shows up as a snapshot that silently contains nothing: no exception, just
 * a short document. That is exactly the failure this class exists to catch, so it drives the
 * command through {@link CommandScope} rather than calling generators directly as the other
 * snapshot tests do.
 */
@EnabledIf("liquibase.ext.trino.TrinoTestSupport#isReachable")
class TrinoSnapshotCommandIntegrationTest {

    @BeforeAll
    static void setup() throws Exception {
        TrinoTestSupport.applyIfNeeded();
    }

    /**
     * The whole point: a snapshot must come back with the fixture's objects, not just a catalog.
     * The table and view carry the verbatim DDL, comments included, which only
     * {@code SHOW CREATE} can supply — {@code information_schema} has no column for either.
     */
    @Test
    void snapshotsFixtureObjectsWithVerbatimDdl() throws Exception {
        String json = snapshotJson();

        assertTrue(json.contains("\"test_table\""), "the table must be in the snapshot:\n" + json);
        assertTrue(json.contains("\"test_view\""), "the view must be in the snapshot:\n" + json);
        assertTrue(json.contains("\"dev_test_schema\""), "the schema must be in the snapshot:\n" + json);

        assertTrue(json.contains("trino.ddl"), "the DDL attribute must reach the output:\n" + json);
        assertTrue(json.contains("WITH ("), "the table DDL must keep its connector properties:\n" + json);
        assertTrue(json.contains("location = 's3://"), "the table DDL must keep its storage location:\n" + json);
        assertTrue(json.contains("SECURITY DEFINER"), "the view DDL must keep its security mode:\n" + json);
        assertTrue(json.contains("Тестовая вью"), "the view DDL must keep its comment:\n" + json);
    }

    /**
     * The command is read-only, so running it twice must produce the same document. A generator
     * that leaks state — caching a lookup, appending to a shared buffer — would break this.
     * <p>
     * Two fields are compared away because they differ between any two runs of any database:
     * {@code created} is the wall-clock time, and {@code snapshotId} is a fresh random key per
     * run that every object reference is keyed on. Neither is produced by this plugin.
     */
    @Test
    void repeatedRunsAreIdentical() throws Exception {
        assertEquals(stripVolatileFields(snapshotJson()), stripVolatileFields(snapshotJson()),
                "two snapshots of an unchanged stand must be identical");
    }

    /** Drops {@code created} and {@code snapshotId}, and the object ids that embed them. */
    private static String stripVolatileFields(String json) {
        String withoutTimestamp = json.replaceAll("\"created\"\\s*:\\s*\"[^\"]*\"", "\"created\":\"<stamp>\"");
        String withoutIds = withoutTimestamp
                .replaceAll("\"snapshotId\"\\s*:\\s*\"[^\"]*\"", "\"snapshotId\":\"<id>\"")
                .replaceAll("(liquibase\\.structure\\.core\\.[A-Za-z]+)#\\w+", "$1#<id>");
        return withoutIds;
    }

    /**
     * Regression guard for the disabled constraint types. The base table generator used to call
     * {@code getIndexInfo} for every table; Trino has no such view and the query threw. The
     * failure mode is a hard error, so this only has to prove the walk still completes.
     */
    @Test
    void doesNotFailOnUnsupportedConstraintMetadata() throws Exception {
        String json = snapshotJson();

        assertFalse(json.contains("\"indexes\""), "unsupported constraint types must stay out of the snapshot:\n" + json);
        assertFalse(json.contains("\"primaryKeys\""), "unsupported constraint types must stay out of the snapshot:\n" + json);
    }

    private static String snapshotJson() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CommandScope(SnapshotCommandStep.COMMAND_NAME[0])
                .addArgumentValue("url", TrinoTestSupport.url())
                .addArgumentValue("username", TrinoTestSupport.user())
                // The schema has to be named. With it unset, Trino reports no session schema for a
                // catalog-only URL, so database.getDefaultSchema() carries a null schema, renders as
                // "iceberg_catalog.DEFAULT", matches no real schema, and the snapshot comes back
                // holding only the catalog — with no error. Putting the schema in the URL
                // (jdbc:trino://host:8081/iceberg_catalog/<schema>) resolves the same way.
                // See README, "Snapshot and generate-changelog".
                .addArgumentValue(SnapshotCommandStep.SCHEMAS_ARG, TrinoTestSupport.FIXTURE_SCHEMA_NAME)
                .addArgumentValue("snapshotFormat", "json")
                .setOutput(out)
                .execute();
        return out.toString(StandardCharsets.UTF_8);
    }
}