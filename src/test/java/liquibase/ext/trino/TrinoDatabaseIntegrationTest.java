package liquibase.ext.trino;

import liquibase.database.Database;
import liquibase.ext.trino.database.TrinoDatabase;
import liquibase.structure.core.Table;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks that a real connection selects {@link TrinoDatabase} by product name.
 * <p>
 * That is the only thing here needing a live Trino: the remaining dialect properties
 * (identifier case, port, driver, priority) need no stand and live in
 * {@link TrinoDatabaseUnitTest} and {@link TrinoMetadataSnapshotTest}.
 */
class TrinoDatabaseIntegrationTest {

    private static Database db;

    @BeforeAll
    static void connect() throws Exception {
        assumeTrue(TrinoTestSupport.isReachable(), "Trino недоступен: " + TrinoTestSupport.url());
        db = TrinoTestSupport.openDatabase();
    }

    @Test
    void trinoDatabaseIsSelectedByProductName() {
        assertInstanceOf(TrinoDatabase.class, db);
    }

    @Test
    void selectedDialectLowersIdentifiers() {
        // Regression: the H2 dialect upper-cased unquoted identifiers while Trino stores them in
        // lower case. Asserted on the dialect picked from a real connection, not on a
        // hand-made new TrinoDatabase().
        assertEquals("databasechangelog", db.correctObjectName("DATABASECHANGELOG", Table.class));
    }
}