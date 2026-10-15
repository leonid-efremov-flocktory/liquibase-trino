INVALID TEST -- loadUpdateData generates "MERGE INTO ... (cols) KEY(id) VALUES (...)",
which is Liquibase's MySQL/H2-style upsert; Trino parses MERGE only as
"MERGE INTO ... USING ... ON ... WHEN MATCHED/ NOT MATCHED" and rejects this form
before it reaches the connector. Not a Trino-extension gap: the SQL is emitted by
Liquibase core (UpdateDataChange), so no dialect override can reshape it.