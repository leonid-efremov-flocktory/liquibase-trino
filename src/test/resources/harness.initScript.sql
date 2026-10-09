-- Stage 2 of the test rewrite (.opencode/plan.md).
-- The harness itself NEVER executes this file: grep over the v1.0.12 sources finds it only
-- in README.extensions. TrinoHarnessConnectionSpec runs it in setupSpec(), before the
-- harness opens its connection: harness init() creates DATABASECHANGELOGLOCK in the session
-- schema, so the schema must already exist. The catalog is the stand's default — the URL
-- seam always carries /iceberg_catalog (run-tests.sh, TrinoTestSupport.url()).
-- No trailing semicolon: TrinoTestSupport.execute() hands the whole file to one JDBC
-- execute(), and the Trino server parses it as a single statement — a stray ';' is a
-- syntax error there ("mismatched input ';'"), unlike on a CLI that strips it first.
CREATE SCHEMA IF NOT EXISTS iceberg_catalog.trino_harness
