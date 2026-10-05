package liquibase.ext.trino.snapshot;

import liquibase.database.Database;
import liquibase.ext.trino.TrinoTestSupport;
import liquibase.snapshot.EmptyDatabaseSnapshot;
import liquibase.snapshot.SnapshotControl;
import liquibase.snapshot.SnapshotGeneratorFactory;
import liquibase.structure.core.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Schema lookup through {@link TrinoSchemaSnapshotGenerator}.
 * <p>
 * Milestone 2 of the "add a database" guide makes {@code snapshot} a requirement, and this is
 * the plugin's only registered snapshot generator. The last two cases are why the class exists
 * at all: the base generator builds candidate names through {@code getSchemaFromJdbcInfo},
 * which on the H2 dialect stamps the default catalog onto every schema, so it answered
 * {@code system.runtime} as {@code iceberg_catalog.runtime} and raised
 * {@code InvalidExampleException: Found multiple catalog/schemas matching} for a schema name
 * that exists in more than one catalog. Reading {@code TABLE_CATALOG} off {@code getSchemas()}
 * avoids both.
 * <p>
 * {@link TrinoMetadataSnapshotIntegrationTest} covers table lookup, which goes through a
 * different generator.
 */
class TrinoSchemaSnapshotIntegrationTest {

    private static final String SYSTEM_CATALOG = "system";
    private static final String SYSTEM_SCHEMA = "runtime";
    private static final String SHARED_SCHEMA_NAME = "information_schema";

    private static Database db;
    private static String catalog;

    @BeforeAll
    static void setup() throws Exception {
        assumeTrue(TrinoTestSupport.isReachable(), "Trino is unreachable: " + TrinoTestSupport.url());
        TrinoTestSupport.applyIfNeeded();
        db = TrinoTestSupport.openDatabase();
        catalog = db.getDefaultCatalogName();
    }

    @Test
    void findsExistingSchemaInDefaultCatalog() throws Exception {
        Schema found = snapshot(TrinoTestSupport.FIXTURE_SCHEMA_NAME);

        assertEquals(TrinoTestSupport.FIXTURE_SCHEMA_NAME, found.getName(),
                "the fixture schema must be found");
        assertEquals(catalog, found.getCatalogName(),
                "the found schema must carry the catalog it was looked up in");
    }

    @Test
    void missingSchemaIsNotFound() throws Exception {
        assertNull(snapshot("no_such_schema_xyz"),
                "a schema that does not exist must not be reported as found");
    }

    /**
     * Trino stores unquoted identifiers in lower case, so an upper-case lookup has to match.
     * The dialect is what makes this work; with H2's UPPER_CASE it would not.
     */
    @Test
    void lookupIsCaseInsensitive() throws Exception {
        Schema found = snapshot(TrinoTestSupport.FIXTURE_SCHEMA_NAME.toUpperCase());

        assertEquals(TrinoTestSupport.FIXTURE_SCHEMA_NAME, found.getName(),
                "an upper-case schema name must resolve to the lower-case stored name");
    }

    /** The base generator would answer {@code iceberg_catalog.runtime} here. */
    @Test
    void schemaInAnotherCatalogKeepsItsOwnCatalog() throws Exception {
        Schema found = snapshotIn(SYSTEM_CATALOG, SYSTEM_SCHEMA);

        assertEquals(SYSTEM_SCHEMA, found.getName());
        assertEquals(SYSTEM_CATALOG, found.getCatalogName(),
                "a schema must not be reported under the connection's default catalog");
    }

    /**
     * Trino repeats schema names across catalogs, so matching on the name alone makes the
     * lookup ambiguous — the case the base generator threw on.
     * <p>
     * Called on the generator directly rather than through the factory: a schema named
     * {@code information_schema} is a system object, and {@code DatabaseSnapshot.include()}
     * drops those before any generator runs. That is an upstream rule inherited from H2, not
     * something this plugin decides.
     */
    @Test
    void schemaNameSharedAcrossCatalogsResolvesToTheRequestedOne() throws Exception {
        Schema inDefault = directLookup(catalog, SHARED_SCHEMA_NAME);
        assertEquals(catalog + "." + SHARED_SCHEMA_NAME,
                inDefault.getCatalogName() + "." + inDefault.getName(),
                "the schema of the requested catalog must be found, not the same name elsewhere");

        Schema inSystem = directLookup(SYSTEM_CATALOG, SHARED_SCHEMA_NAME);
        assertEquals(SYSTEM_CATALOG + "." + SHARED_SCHEMA_NAME,
                inSystem.getCatalogName() + "." + inSystem.getName(),
                "the same schema name under another catalog must resolve to that catalog");
    }

    private static Schema snapshot(String schemaName) throws Exception {
        return snapshotIn(catalog, schemaName);
    }

    private static Schema snapshotIn(String catalogName, String schemaName) throws Exception {
        return SnapshotGeneratorFactory.getInstance()
                .createSnapshot(new Schema(catalogName, schemaName), db);
    }

    /**
     * Calls the plugin's generator directly, skipping {@code DatabaseSnapshot}'s
     * {@code isSystemObject} gate, so that system-object schemas can be looked up at all.
     */
    private static Schema directLookup(String catalogName, String schemaName) throws Exception {
        return (Schema) new TrinoSchemaSnapshotGenerator().snapshot(
                new Schema(catalogName, schemaName),
                new EmptyDatabaseSnapshot(db, new SnapshotControl(db)),
                null);
    }
}