package liquibase.ext.trino.snapshot;

import liquibase.CatalogAndSchema;
import liquibase.database.Database;
import liquibase.database.AbstractJdbcDatabase;
import liquibase.ext.trino.TrinoTestSupport;
import liquibase.snapshot.SnapshotGeneratorFactory;
import liquibase.structure.core.Schema;
import liquibase.structure.core.Table;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test: Liquibase must find tables that already exist in Trino.
 * <p>
 * The bug: the H2 dialect ({@code getSchemaAndCatalogCase() == UPPER_CASE}) upper-cased
 * the schema name while Trino stores unquoted identifiers in lower case, and its metadata
 * filter ({@code system.jdbc.tables}) is case-sensitive. {@code SnapshotGeneratorFactory.has(...)}
 * then failed to find an existing table and Liquibase retried the CREATE TABLE.
 */
@EnabledIf("liquibase.ext.trino.TrinoTestSupport#isReachable")
class TrinoMetadataSnapshotIntegrationTest {

    private static final String SCHEMA = "liquibase_test";
    private static final String TABLE = "snapshot_probe";

    private static Database db;
    private static String catalog;

    @BeforeAll
    static void setup() throws Exception {
        db = TrinoTestSupport.openDatabase();
        catalog = db.getDefaultCatalogName();

        try (Connection c = TrinoTestSupport.openRaw(); Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
            st.execute("DROP TABLE IF EXISTS " + SCHEMA + "." + TABLE);
            st.execute("CREATE TABLE " + SCHEMA + "." + TABLE + " (id INT)");
        }

        db.setDefaultSchemaName(SCHEMA);
        db.setLiquibaseSchemaName(SCHEMA);
        db.setLiquibaseCatalogName(catalog);
    }

    @AfterAll
    static void cleanup() throws Exception {
        try (Connection c = TrinoTestSupport.openRaw(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS " + SCHEMA + "." + TABLE);
            st.execute("DROP SCHEMA IF EXISTS " + SCHEMA);
        }
    }

    @Test
    void hasExistingTableReturnsTrue() throws Exception {
        boolean has = SnapshotGeneratorFactory.getInstance().has(
                new Table().setName(TABLE).setSchema(new Schema(catalog, SCHEMA)), db);
        assertTrue(has, "Liquibase должен находить существующую таблицу " + SCHEMA + "." + TABLE);
    }

    @Test
    void hasMissingTableReturnsFalse() throws Exception {
        boolean has = SnapshotGeneratorFactory.getInstance().has(
                new Table().setName(TABLE + "_missing").setSchema(new Schema(catalog, SCHEMA)), db);
        assertFalse(has);
    }

    @Test
    void metadataSchemaFilterIsLowercase() throws Exception {
        // The schema is passed in upper case on purpose: without lower-casing in the dialect the
        // filter would reach getTables unchanged and find nothing — the bug itself.
        CatalogAndSchema catalogAndSchema = new CatalogAndSchema(catalog, SCHEMA.toUpperCase()).customize(db);
        String schemaPattern = ((AbstractJdbcDatabase) db).getJdbcSchemaName(catalogAndSchema);
        assertEquals(SCHEMA, schemaPattern, "фильтр схемы для JDBC-метаданных должен быть в нижнем регистре");

        try (Connection c = TrinoTestSupport.openRaw();
             ResultSet rs = c.getMetaData().getTables(catalog, schemaPattern, TABLE, new String[]{"TABLE"})) {
            assertTrue(rs.next(), "metadata.getTables должен вернуть таблицу по lower-case фильтру схемы");
        }
    }
}