### `TrinoDatabase` overrides that change behaviour

- **`getConnectionCatalogName()` / `getConnectionSchemaName()`** read the session's
  `current_catalog` / `current_schema` instead of asking the connection. `H2Database` answers the
  schema question from a field initialized to the literal `"PUBLIC"`, and since this class bypasses
  `H2Database.setConnection` nothing ever overwrote it — every snapshot looked for
  `iceberg_catalog.public`, found nothing, and quietly returned just the catalog and the schema.
  A Trino URL may also carry no catalog at all, in which case the session's catalog is the only
  sensible fallback.
- **`supports()`** returns `false` for `Index`, `PrimaryKey`, `ForeignKey` and `UniqueConstraint`.
  Trino enforces none of them at DDL time, and querying their metadata fails against the driver.
- **`showCreate(Table|View)`** builds the object name itself instead of using `escapeObjectName`.
  The connection's quoting strategy says nothing about how the object was created: against a
  database that quotes everything, the base generator returns `iceberg_catalog."dev"."v"`, while a
  fresh `SHOW CREATE` returns it unquoted, and a generated changelog would then not match the
  server it came from. Names are quoted here only when they need it, so the text is the same
  whether or not the connection quotes.
| `sqlgenerator/TrinoSelectFromDatabaseChangeLogGenerator.java` | A copy of the base `SelectFromDatabaseChangeLogGenerator` **without** the `.toUpperCase()` on the column list. Even after lower-case escaping the base generator emits `SELECT ID, AUTHOR, ...`, while Trino stores column names in lower case. Priority 510, applies only to `TrinoDatabase`. |
| `sqlgenerator/TrinoCreateDatabaseChangeLogLockTableGenerator.java` | Trino rejects `CONSTRAINT ... PRIMARY KEY` in `CREATE TABLE`, so the lock table is created without one and `LOCKGRANTED` is a `TIMESTAMP` rather than H2's `datetime`. Without it the very first `update` fails. |
| `sqlgenerator/TrinoAddColumnGenerator.java` | Emits `ALTER TABLE ... ADD COLUMN <name> <type>`. The base generator emits a bare `ADD`, which Trino rejects with `mismatched input '<name>'. Expecting: '.', 'ADD'`. Only the keyword is added; the type, default, nullability and auto-increment clauses are still the base generator's. |
| `sqlgenerator/TrinoRenameColumnGenerator.java` | Emits `ALTER TABLE ... RENAME COLUMN old TO new`. This override exists only because `TrinoDatabase` extends `H2Database`: the base generator branches on the database type and picks the H2 form `ALTER COLUMN old RENAME TO new`, which Trino rejects with `mismatched input 'RENAME'. Expecting: '.', 'DROP', 'SET'`. |
| `snapshot/TrinoSchemaSnapshotGenerator.java` | Schema snapshot via JDBC `getSchemas()` with case-insensitive matching on `catalog + schema`, raising `InvalidExampleException` on ambiguity. The base `SchemaSnapshotGenerator` fails on Trino with `Found multiple catalog/schemas matching iceberg_catalog.dev_migrations`. The `replaces()` method is mandatory: `SnapshotGeneratorChain` calls **every** generator by priority. Registered with `PRIORITY_ADDITIONAL` so it runs before the base `SchemaSnapshotGenerator` at `PRIORITY_DEFAULT`. |
| `snapshot/TrinoTableSnapshotGenerator.java`, `snapshot/TrinoViewSnapshotGenerator.java` | Put the server's own DDL on the object as the `trino.ddl` attribute, so that `snapshot` shows what `SHOW CREATE` said rather than a reconstruction. Same priority requirement as the schema generator. |
| `snapshot/TrinoDdlFetcher.java` | The `SHOW CREATE TABLE` / `SHOW CREATE VIEW` calls, the trailing-semicolon strip, and the check that the object is a `TrinoDatabase`. |
| `diff/TrinoDdlChangeGenerator.java` | Turns the `trino.ddl` attribute into a `RawSQLChange`, which is what makes `generate-changelog` emit the server's text instead of a lossy `<createTable>`. Registered at `TRINO_PRIORITY_DATABASE`. |


### Why the DDL is carried verbatim

`generate-changelog` writes `SHOW CREATE TABLE` / `SHOW CREATE VIEW` output into the changelog
as raw SQL rather than as `<createTable>` / `<createView>`. A structured changelog cannot
express what Trino returns and needs:

| In `SHOW CREATE` | Why a structured change cannot hold it |
| --- | --- |
| `WITH (format, format_version, partitioning, location, sorted_by)` | no Liquibase change type carries table properties; partitioning is not expressible at all |
| `COMMENT ON TABLE` / `COMMENT ON COLUMN` | comments are DDL in Trino, not an attribute of `<createTable>` |
| `SECURITY DEFINER` | no attribute for it |
| `ARRAY` / `ROW` / `map` column types | no Liquibase data type maps onto them |

The cost of that choice is in [Known limitations](#known-limitations): the generated changelog is
only replayable against the same catalog and schema.


### Why `getReservedWords()` is overridden

The Trino JDBC driver implements `DatabaseMetaData.getDatabaseProductVersion()` by actually
running `SELECT version()`, and derives `getDatabaseMajorVersion()` from the same call. Liquibase
caches nothing on that path.

`H2Database.getReservedWords()` asks for the major version to pick between its V1 and V2 reserved
word lists, and `isReservedWord()` asks for the list — once per escaped identifier. Inherited as-is,
that is **one `SELECT version()` per table, column, schema and index** during SQL generation and
snapshotting. Returning `V2_RESERVED_WORDS` directly costs nothing and is what H2 would have
returned anyway: the choice depends only on whether the major version is `>= 2`, and Trino reports
`464`.

Covered by `reservedWordLookupNeedsNoVersionQuery` and
`escapingIdentifiersSendsNoVersionQueries` in `TrinoDatabaseUnitTest`.

Not addressed here: `AddColumnGenerator` and `RenameColumnGenerator` also call
`getDatabaseMajorVersion()` once per change, so a changelog with many `addColumn` changes still
sends one query each. Caching the version getters would remove that too.


### `snapshot` and `generate-changelog` need an explicit schema
    
Neither command has a working way to say "snapshot the schema this connection defaults to", and
both must be given the schema by name:

```bash
liquibase --url="jdbc:trino://host:8081/iceberg_catalog" \
          --schemas=dev_migrations snapshot
liquibase --url="jdbc:trino://host:8081/iceberg_catalog" \
          --reference-schemas=dev_migrations generate-changelog out.trino.sql
```

`--catalog-name` / `--schema-name` do not reach the snapshot in Liquibase 5.0.4. Left unset, the
target resolves through `CatalogAndSchema.toString()`, which turns a null schema into the literal
string `DEFAULT` — and `iceberg_catalog.DEFAULT` is not a schema. The result of that is a snapshot
containing the catalog and the schema and nothing else, with no error.


### Not supported

| Change or feature | Why |
| --- | --- |
| `<createIndex>`, `Index` in a snapshot | `TrinoDatabase.supports(Index)` → `false` |
| `<addPrimaryKey>`, `<addForeignKeyConstraint>`, `<addUniqueConstraint>` | the same, for `PrimaryKey`, `ForeignKey`, `UniqueConstraint`; Trino enforces nothing at DDL time |
| `<modifyDataType>` | not supported by Trino itself |
| `<addNotNullConstraint>` | not supported by Trino itself |
| Sequences | `supportsSequences()` → `false`; Trino has no sequences |

Because the constraint types are unsupported, `SHOW CREATE TABLE` output for an Iceberg table is
the only record of them; a generated changelog will not recreate them as constraints.


### Test stand

Three services to provide Trino + Iceberg working setup:

| Service | Image | Role |
| --- | --- | --- |
| `silo` | `pgsty/silo` | S3-compatible object storage (a maintained MinIO fork) |
| `iceberg-rest` | `apache/iceberg-rest-fixture` | Iceberg REST catalog over a SQLite-backed `JdbcCatalog` |
| `trino` | `trinodb/trino:464` | the Trino cluster under test |

Every service has a healthcheck, and Trino's runs a real query rather than a hit on
`/v1/info` (which answers before the catalogs are loaded). So `docker compose up -d --wait`,
which `run-tests.sh` uses, blocks until migrations can actually run, and neither the script nor
CI polls for readiness. CI runs `./run-tests.sh` itself, so there is one implementation of that.

Notes on individual tests:

- `TrinoDatabaseUnitTest` — dialect properties that need no connection: short name,
  priority, default port, driver, identifier case, product name, plus the two that the shim
  exists for: lower-casing of escaped column names, and that `setAutoCommit` is a no-op because
  the Trino driver rejects it. Also pins that reserved-word lookup and identifier escaping send
  no version queries, which is the `SELECT version()` regression described above. 18 tests.
- `TrinoSqlGeneratorsUnitTest` — the SQL both plugin generators emit, as strings: the lock
  table is created without a primary key and with `TIMESTAMP` rather than H2's `datetime`, and
  the changelog `SELECT` keeps its column list lower-case (the base generator upper-cases it),
  applies `WHERE`/`ORDER BY` and a plain `LIMIT`. Also pins that every generator applies to
  `TrinoDatabase` only, and that the two ALTER overrides emit Trino's spelling
  (`ADD COLUMN <name>`, `RENAME COLUMN old TO new`) rather than the H2 form the base generator
  picks — including that `NOT NULL` and `DEFAULT` survive the `ADD COLUMN` override, since it only
  adds a keyword. 16 tests.
- `TrinoDatabaseIntegrationTest` — that `DatabaseFactory` picks `TrinoDatabase` from a
  real connection, that the picked dialect lower-cases identifiers, and that the dialect claims
  the live connection while declining one whose product name is absent (an offline connection,
  which reports `null` and used to make `isCorrectDatabaseImplementation` throw).
- `TrinoMetadataSnapshotIntegrationTest` — a regression test for locating existing tables
  (`SnapshotGeneratorFactory.has(...)`). The H2 dialect upper-cased the schema while
  Trino stores unquoted identifiers in lower case, which made Liquibase try to recreate
  tables that already existed. The metadata test drives the schema in upper case on
  purpose, so a regression to `UPPER_CASE` fails rather than passes.
- `TrinoChangeLogUpdateIntegrationTest` — a full `update` run. Asserts both tracking tables exist,
  that all three changesets are recorded **in execution order with their `ORDEREXECUTED`,
  `EXECTYPE` and `FILENAME`**, that `DATABASECHANGELOGLOCK` is left with `LOCKED = false`
  and an empty `LOCKEDBY`, and that the fixture produced a five-row `test_table` with
  data and a four-row `test_view` over it. Also checks that a repeated `update` duplicates
  neither data nor changelog rows.
- `TrinoRollbackIntegrationTest` — the fixture is applied once in `@BeforeAll`, then the
  tests roll it back one changeset at a time: the writer variant
  (`rollback(count, contexts, writer)`) must emit the `DROP`/`DELETE` statements without
  executing them, `rollback(1)` on v2 must restore the view and remove the rows v2
  inserted, and `rollback(1)` on v1 must drop the table and the view. Each step checks
  the resulting `DATABASECHANGELOG` contents and that the lock is released, so a
  changelog row that outlives its changeset fails the run. The class leaves the fixture rolled
  back: the classes that read it get it back through `applyIfNeeded()`, and restoring it here
  too would just apply the same changesets twice per run.
- `TrinoReadCommandsIntegrationTest` — the commands that read the tracking table back rather
  than write to it: `status` (including `getChangeSetStatuses`, which must read back a stored
  checksum per changeset, and an empty unrun list), `history` (changesets in execution order
  with their `orderexecuted` and timestamps), and `tag`/`tagExists`. All of them go through
  `TrinoSelectFromDatabaseChangeLogGenerator`, so they are the tests that would catch a defect
  in it — which surfaces as wrong output, not as an exception. The two `tag` tests are ordered
  because the second asserts a tag written by the first; `tagExists` also goes through the
  `ByTag` where-clause, so the tag must be readable by a tagged `SELECT`, not merely stored.
- `TrinoSchemaSnapshotIntegrationTest` — schema lookup through
  `TrinoSchemaSnapshotGenerator`. Existing schema found, missing one not, lookup
  case-insensitive, a schema in another catalog keeps its own catalog, and a schema name that
  exists in several catalogs resolves to the requested one. That last case is what the
  generator is for: the base generator builds candidate names through
  `getSchemaFromJdbcInfo`, which stamps the connection's default catalog onto every schema, so
  it answered `system.runtime` as `iceberg_catalog.runtime` and threw
  `InvalidExampleException: Found multiple catalog/schemas matching` for a name shared across
  catalogs. The class creates and drops a schema of its own rather than calling
  `applyIfNeeded()` for the shared fixture: schema lookup reads nothing out of a schema, so
  any one will do, and depending on another class's fixture made this one fail when it ran
  after a class that had rolled that fixture back.
- `TrinoChangelogFormatIntegrationTest` — the same objects expressed through `<sqlFile>`
  instead of formatted-SQL includes, in XML, YAML and JSON. One parameterized test confirms each
  format reaches its own parser, that `relativeToChangelogFile` finds the body file sitting next
  to the changelog, and that each format records its own changeset ids; a second test rolls the
  XML one back. The row text is asserted on purpose: it is the only evidence the body file was
  read rather than skipped. The XML fixture additionally carries structured `<insert>` and
  `<delete>` changesets, and the case asserts the rows they add and remove, so the data path
  is covered by change types rather than only by the raw SQL in the body file. Rollback runs
  for XML only — all three fixtures hold the same rollback SQL, so the other two would be the
  same run twice. These fixtures record their changesets in their own tracking schema,
  `liquibase_changelog_sqlfile`, so applying them neither reads nor destroys the main
  fixture's tracking table. The stand is reset before each case, tracking tables included:
  left behind, Liquibase would consider the changesets already run and the case would assert
  against an empty schema.
- `TrinoChangelogRollbackUnitTest` — that an empty XML `<rollback/>` is parsed into an
  `EmptyChange`. Needs no running Trino. The *contents* of the rollback blocks are checked
  by `TrinoRollbackIntegrationTest`, which parses the same fixture and then executes the
  generated SQL on Trino, so it covers strictly more.
- `TrinoSnapshotCommandIntegrationTest` — the `snapshot` command end to end: the fixture's
  table, view and schema come back, the `trino.ddl` attribute holds the server's text
  including `WITH (` and `location = 's3://`, and the output carries no `indexes` or
  `primaryKeys` (the regression that `getIndexInfo` used to cause). Two runs are compared
  after normalising the fields that cannot be equal — `created`, the snapshot id and object
  ids — so the comparison fails on a real difference instead of on a timestamp. 3 tests.
- `TrinoGenerateChangelogIntegrationTest` — `generate-changelog` and the round trip. The
  generated body is compared character for character with `SHOW CREATE`, then the objects
  are dropped, the generated file is applied, and `SHOW CREATE` is read again and compared
  to what it said before. The second comparison is the one that matters: it fails if the
  DDL is merely printable rather than replayable. 3 tests.
- `TrinoGoldenSqlTest` — the golden layer. 17 change types, each with a changelog under
  `src/test/resources/liquibase/ext/trino/golden/changelogs/` and the SQL it must produce
  under `golden/expected-sql/`. Each case runs twice: the writer output is compared with
  the golden file verbatim, and then the changelog is really applied, checked through
  `information_schema`, rolled back, and its schema dropped. The two halves cover each
  other — a verbatim comparison alone would pass SQL Trino rejects, and an apply alone
  would let the SQL change silently. 18 tests.

  The comparison strips what `update-sql` prints around the run rather than around the
  changelog: statements against `DATABASECHANGELOG`/`DATABASECHANGELOGLOCK`, Liquibase's own
  headings, and the `-- Ran at:` / `-- Against:` header lines. Those carry the wall-clock
  time, the JDBC URL, the hostname and IP of whoever took the lock, and a freshly generated
  deployment id, so a golden file containing any of them would fail on the next run for
  reasons unrelated to the generator. What remains is compared character for character,
  and comments inside a changeset are kept rather than filtered as noise.

  Each case owns its schema, so the cases share one stand and can run in any order. Two
  things are deliberately not asserted from the stand: the partitioning case checks its
  columns rather than its partitioning, because Iceberg's `t$partitions` metadata table is
  not reachable over the test connector; and column comments are read from
  `information_schema.columns` because `system.metadata.column_comments` resolves the table
  first and gets `ConnectorMetadata getTableHandle() is not implemented`. In both cases the
  golden file is what pins the detail.

Every rollback test performs its own `rollback` call and asserts the outcome in the same
method. The order annotation is load-bearing: each changeset can be rolled back exactly
once, so an intermediate failing assertion would otherwise leave the stand in a state the
next test does not expect.

Integration tests skip when Trino is unreachable: each class is annotated
`@EnabledIf("liquibase.ext.trino.TrinoTestSupport#isReachable")`. An execution condition rather
than an `assumeTrue` in `@BeforeAll`, so the class is disabled before any of its callbacks run
and the `@AfterAll` cleanup does not have to repeat the probe. The probe runs a real
`SELECT 1`, not `DriverManager.getConnection` — the Trino JDBC driver connects lazily and
returns a connection object even against a dead port, so a connection-based probe always
reported reachable and every test failed inside `@BeforeAll` instead of skipping. The answer is
cached per JVM, since it cannot change mid-run.
