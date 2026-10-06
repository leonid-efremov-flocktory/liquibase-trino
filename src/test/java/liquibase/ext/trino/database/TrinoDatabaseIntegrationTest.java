package liquibase.ext.trino.database;

import liquibase.database.Database;
import liquibase.database.OfflineConnection;
import liquibase.ext.trino.TrinoTestSupport;
import liquibase.structure.core.Table;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that a real connection selects {@link TrinoDatabase} by product name.
 * <p>
 * That is the only thing here needing a live Trino: the remaining dialect properties
 * (identifier case, port, driver, priority) need no stand and live in
 * {@link TrinoDatabaseUnitTest} and {@code TrinoMetadataSnapshotIntegrationTest}
 * (in the {@code snapshot} package).
 */
@EnabledIf("liquibase.ext.trino.TrinoTestSupport#isReachable")
class TrinoDatabaseIntegrationTest {

    private static Database db;

    @BeforeAll
    static void connect() throws Exception {
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
        // lower case. TrinoDatabaseUnitTest asserts the same on a hand-made dialect; here it is
        // asserted on the one the DatabaseFactory picked from a real connection, which is what
        // the changelog commands actually get.
        assertEquals("databasechangelog", db.correctObjectName("DATABASECHANGELOG", Table.class));
    }
}