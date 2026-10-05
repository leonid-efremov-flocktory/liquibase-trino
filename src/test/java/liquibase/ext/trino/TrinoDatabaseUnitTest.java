package liquibase.ext.trino;

import liquibase.CatalogAndSchema;
import liquibase.ext.trino.database.TrinoDatabase;
import liquibase.structure.core.Schema;
import liquibase.structure.core.Table;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Dialect properties of the shim that need no live Trino.
 */
class TrinoDatabaseUnitTest {

    private final TrinoDatabase db = new TrinoDatabase();

    @Test
    void shortNameIsTrino() {
        assertEquals("trino", db.getShortName());
    }

    @Test
    void priorityIsAboveDefaults() {
        assertEquals(TrinoDatabase.TRINO_PRIORITY_DATABASE, db.getPriority());
    }

    @Test
    void defaultPortIsHttps() {
        assertEquals(443, db.getDefaultPort());
    }

    @Test
    void currentDateTimeFunction() {
        assertEquals("CURRENT_TIMESTAMP", db.getCurrentDateTimeFunction());
    }

    @Test
    void schemaAndCatalogCaseIsLower() {
        assertEquals(CatalogAndSchema.CatalogAndSchemaCase.LOWER_CASE, db.getSchemaAndCatalogCase());
    }

    @Test
    void doesNotSupportSequences() {
        assertFalse(db.supportsSequences());
    }

    @Test
    void doesNotSupportDdlInTransaction() {
        assertFalse(db.supportsDDLInTransaction());
    }

    @Test
    void defaultDriverForTrinoUrl() {
        assertEquals("io.trino.jdbc.TrinoDriver", db.getDefaultDriver("jdbc:trino://host:8081/catalog"));
    }

    @Test
    void defaultDriverIsNullForOtherUrls() {
        assertNull(db.getDefaultDriver("jdbc:h2:mem:test"));
    }

    @Test
    void tableNameCorrectedToLowerCase() {
        assertEquals("databasechangelog", db.correctObjectName("DATABASECHANGELOG", Table.class));
    }

    @Test
    void schemaNameCorrectedToLowerCase() {
        assertEquals("dev_migrations", db.correctObjectName("DEV_MIGRATIONS", Schema.class));
    }

    @Test
    void caseSensitiveIsFalse() {
        assertFalse(db.isCaseSensitive());
    }
}