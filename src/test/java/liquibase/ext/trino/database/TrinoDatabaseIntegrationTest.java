package liquibase.ext.trino.database;

import liquibase.database.Database;
import liquibase.database.OfflineConnection;
import liquibase.ext.trino.TrinoTestSupport;
import liquibase.structure.core.Table;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks that a real connection selects {@link TrinoDatabase} by product name.
 * <p>
 * That is the only thing here needing a live Trino: the remaining dialect properties
 * (identifier case, port, driver, priority) need no stand and live in
 * {@link TrinoDatabaseUnitTest} and {@code TrinoMetadataSnapshotIntegrationTest}
 * (in the {@code snapshot} package).
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
    void liveConnectionIsClaimedByTheDialect() throws Exception {
        // The decision isCorrectDatabaseImplementation makes, asserted on the connection Liquibase
        // actually opened: it must accept this Trino connection and reject anything else.
        TrinoDatabase dialect = (TrinoDatabase) db;
        assertTrue(dialect.isCorrectDatabaseImplementation(db.getConnection()));
        assertFalse(dialect.isCorrectDatabaseImplementation(new OfflineConnection()));
    }

    @Test
    void selectedDialectLowersIdentifiers() {
        // Regression: the H2 dialect upper-cased unquoted identifiers while Trino stores them in
        // lower case. Asserted on the dialect picked from a real connection, not on a
        // hand-made new TrinoDatabase().
        assertEquals("databasechangelog", db.correctObjectName("DATABASECHANGELOG", Table.class));
    }
}