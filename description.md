## Why a separate dialect

Liquibase selects a `Database` implementation purely from the JDBC product name
(`DatabaseMetaData.getDatabaseProductName()`; the Trino driver reports `Trino`), and
`liquibase.databaseClass` does not override that choice. The plugin therefore supplies a
class that claims a Trino connection and is close enough to Trino's dialect for the tracking
tables and the queries against them to be valid.

PostgreSQL is the nearest dialect by meaning and cannot be used: Liquibase runs
`SHOW SEARCH_PATH` / `SET SEARCH_PATH` on connect and Trino has neither. `H2Database` has no
init SQL and its type mappings are all valid in Trino (`datetime`→`TIMESTAMP`,
`boolean`→`BOOLEAN`, `int`→`INT`, `varchar`→`VARCHAR`), and it already ships inside
`liquibase-core`, so the plugin only adds the H2 JDBC driver on top.

## `TrinoDatabase` overrides that change behaviour

| Method | What it does | Why |
| --- | --- | --- |
| `getConnectionCatalogName()` | URL catalog, else `SELECT current_catalog` | the URL may carry no catalog, and then the session's own catalog is the only sensible fallback; Liquibase caches the result in `defaultCatalogName` itself |
| `getConnectionSchemaName()` | `SELECT current_schema` | `H2Database` answers this from a field initialized to the literal `"PUBLIC"`, and because this class bypasses `H2Database.setConnection` nothing overwrote it — every snapshot looked for `iceberg_catalog.public`, found nothing, and quietly returned just the catalog. `super` is unusable too: it runs `CALL current_schema`, which Trino has no such statement for |
| `supports()` | `false` for `Index`, `PrimaryKey`, `ForeignKey`, `UniqueConstraint` | Trino enforces none of them at DDL time, and querying their metadata fails against the driver |
| `showCreate(Table\|View)` / `qualify()` | builds the object name itself instead of via `escapeObjectName`, quoting only names that need it | the connection's quoting strategy says nothing about how the object was created: under `QUOTE_ALL_OBJECTS` — which the changelog writer's reference database uses — `escapeObjectName` returns `iceberg_catalog."dev"."v"` while a fresh `SHOW CREATE` returns it unquoted, so a generated changelog would not match the server it came from |
| `getTableDefinition()` / `getViewDdl()` | `SHOW CREATE TABLE` / `SHOW CREATE VIEW` | the verbatim statement; see "Why the DDL is carried verbatim" |
| `getViewDefinition()` | reads `information_schema.views` directly | the base implementation resolves the definition through a SQL generator that every dialect spells differently, and the H2 form calls `definition.startsWith("SELECT")` on a possibly null value |
| `getReservedWords()` | returns `V2_RESERVED_WORDS` | see "Why `getReservedWords()` is overridden" |
| `setConnection()` | calls `AbstractJdbcDatabase.setConnection` through a `MethodHandle` | see "Bypassing `H2Database.setConnection`" |
| `setAutoCommit()` | no-op | the Trino JDBC driver rejects `setAutoCommit(false)` |

## Registered generators and snapshot generators

Every class is registered through a `META-INF/services` file, so dropping the jar into
`internal/lib` or `internal/extensions` is all the registration Liquibase needs.

| Class | What it does |
| --- | --- |
| `sqlgenerator/TrinoSelectFromDatabaseChangeLogGenerator.java` | A copy of the base `SelectFromDatabaseChangeLogGenerator` **without** the `.toUpperCase()` on the column list. Even after lower-case escaping the base generator emits `SELECT ID, AUTHOR, ...`, while Trino stores column names in lower case. Priority 510, applies only to `TrinoDatabase`. |
| `sqlgenerator/TrinoCreateDatabaseChangeLogLockTableGenerator.java` | Trino rejects `CONSTRAINT ... PRIMARY KEY` in `CREATE TABLE`, so the lock table is created without one and `LOCKGRANTED` is a `TIMESTAMP` rather than H2's `datetime`. Without it the very first `update` fails. |
| `sqlgenerator/TrinoAddColumnGenerator.java` | Emits `ALTER TABLE ... ADD COLUMN <name> <type>`. The base generator emits a bare `ADD`, which Trino rejects with `mismatched input '<name>'. Expecting: '.', 'ADD'`. Only the keyword is added; the type, default, nullability and auto-increment clauses are still the base generator's. If the base clause ever stops starting with `" ADD "`, the override throws rather than silently emitting SQL Trino rejects. |
| `sqlgenerator/TrinoRenameColumnGenerator.java` | Emits `ALTER TABLE ... RENAME COLUMN old TO new`. This override exists only because `TrinoDatabase` extends `H2Database`: the base generator branches on the database type and picks the H2 form `ALTER COLUMN old RENAME TO new`, which Trino rejects with `mismatched input 'RENAME'. Expecting: '.', 'DROP', 'SET'`. |
| `snapshot/TrinoSchemaSnapshotGenerator.java` | Schema snapshot via JDBC `getSchemas()` with case-insensitive matching on `catalog + schema`, raising `InvalidExampleException` on ambiguity. The base `SchemaSnapshotGenerator` fails on Trino with `Found multiple catalog/schemas matching iceberg_catalog.dev_migrations`. The `replaces()` method is mandatory: `SnapshotGeneratorChain` calls **every** generator by priority. It must stay at `PRIORITY_DEFAULT` — the slot `SchemaSnapshotGenerator` itself occupies — and **not** be raised; see the `addTo` recursion note below. |
| `snapshot/TrinoTableSnapshotGenerator.java`, `snapshot/TrinoViewSnapshotGenerator.java` | Put the server's own DDL on the object as the `trino.ddl` attribute, so that `snapshot` shows what `SHOW CREATE` said rather than a reconstruction. `PRIORITY_DEFAULT` for their own type, inherited priority for every other type — same reason as the schema generator. |
| `snapshot/TrinoDdlFetcher.java` | The `SHOW CREATE TABLE` / `SHOW CREATE VIEW` calls, the trailing-semicolon strip, and the check that the object is a `TrinoDatabase`. Returns null rather than throwing when the relation cannot be read: a missing relation costs the DDL attribute, not the whole snapshot. |
| `diff/TrinoDdlChangeGenerator.java` | Turns the `trino.ddl` attribute into a `RawSQLChange`, which is what makes `generate-changelog` emit the server's text instead of a lossy `<createTable>`. Registered at `TRINO_PRIORITY_DATABASE`. |

### Why every snapshot generator sits at `PRIORITY_DEFAULT`

`JdbcSnapshotGenerator.addTo` works by recursion: a `PRIORITY_ADDITIONAL` generator (Table, View,
Column, priority 50) calls `chain.snapshot(...)` to let the *rest* of the chain find the object,
then attaches itself to the result. The generator that produces the `Schema` therefore has to
come **last** in the chain, after the `addTo` generators.

Each Trino generator replaces a core one and returns `PRIORITY_DEFAULT` — the slot its
replaced counterpart occupies — and delegates to `super.getPriority` for every type it does not
handle. Returning anything above 50 puts it ahead of the `addTo` generators, the recursion hits
an exhausted iterator, `chainResponse` comes back null, and `addTo` is skipped entirely. The
symptom is distinctive and was the original bug: **every snapshot found the schema but none of
its tables or views.**

The two object snapshot generators subclass `TableSnapshotGenerator` / `ViewSnapshotGenerator`
and override only `snapshotObject`, calling `super` first, so relations are still found through
JDBC metadata and columns are still read one by one. They return `PRIORITY_DEFAULT` for their
own type — the slot the base generator occupies — and delegate to `super.getPriority` for every
other type, because `addTo` works by recursion: a `PRIORITY_ADDITIONAL` generator (Table, View,
Column at 50) calls `chain.snapshot(...)` and attaches to the result, so the generator that
produces the `Schema` has to come **last** in the chain. Returning a higher priority for an
unrelated type would demote the generator below those and starve their `addTo` pass. This is
why every snapshot used to find the schema but none of its tables.

### `RawSQLChange` and the writer's two hard rules

`TrinoDdlChangeGenerator.fixMissing` builds a `RawSQLChange` with `splitStatements(false)` and
`setEndDelimiter("")`. Neither is cosmetic:

- the formatted-SQL writer always prints `splitStatements:false` and only rewrites it to `true`
  when a single change expands into several statements;
- it always terminates the changeset body with the end delimiter, and Trino rejects a statement
  that still ends in `;`. With splitting off there is nothing left to strip it, so the body must
  carry no trailing `;` — hence `TrinoDdlFetcher.stripTrailingSemicolon`.

The generator also calls `control.setAlreadyHandledMissing(column)` for every column. Without
it the diff keeps reporting the columns as missing and a later generator appends a separate
`addColumn` change for each one, on top of a `CREATE TABLE` that already declares them.

If the `trino.ddl` attribute is absent the generator defers to the chain, so core produces its
lossy-but-valid change instead of the object being dropped.


## Snapshot and generate-changelog

Both commands read a live Trino and emit what is already there. The schema has to be named, and
getting it wrong is silent rather than loud — so name it:

```bash
liquibase --url="jdbc:trino://trino:8081/iceberg_catalog" \
          --username=trino \
          --schemas=warehouse \
          generate-changelog --changelog-file=my_database_structure.trino.sql
```

With a URL that names only a catalog (`.../iceberg_catalog` and no schema), Trino reports no
session schema at all. Liquibase then carries a null schema, renders it as
`iceberg_catalog.DEFAULT`, matches no real schema, and the command returns the catalog alone —
no error, just an empty result. The same fix applies to `snapshot`, and putting the schema in
the URL (`jdbc:trino://trino:8081/iceberg_catalog/warehouse`) works too.

Two details of the output are not obvious:

- **the `.trino.sql` suffix is required.** Liquibase picks the SQL serializer from the file
  extension, and refuses a bare `.sql` name because it cannot tell which dialect's SQL
  formatting to apply. `.trino.sql` selects Trino's.
- **objects come out as verbatim `SHOW CREATE` text**, not as `<createTable>`. Nothing is lost
  this way: a structured create has nowhere to put `partitioning`, `location`, `format`, or a
  table comment, and column types read from `information_schema` are lossy. The same reasoning
  applies to the `trino.ddl` attribute on a `snapshot`.


### Splitting the generated file into one file per object

Liquibase writes the whole snapshot to one file. Splitting it is left to the tooling around the
command rather than built into the plugin: the changesets are already delimited in the output,
one per object, so the file can be cut on that marker.

```bash
liquibase ... generate-changelog --changelog-file=my_database_structure.trino.sql

# one changeset per object already; split the file on the marker
awk '
  /^-- changeset / { if (out) close(out); n++; out = sprintf("%03d.sql", n) }
  out { print > out }
' my_database_structure.trino.sql
```

Each resulting file holds one changeset — the verbatim `CREATE TABLE` or `CREATE VIEW` plus its
header — and can be included from a changelog with `<sqlFile>`, or left as a standalone script.
If you prefer one directory per object, extend the same loop with `mkdir` on the `changeset`
line; the id in the marker is unique, so it works as a directory name.

Note that the `location = 's3://…'` in a generated `CREATE TABLE` points at the storage the
table was read from, so a replayed file recreates the table in the same place rather than a new
one. See [Known limitations](#known-limitations).


## Why the DDL is carried verbatim

`snapshot` records `SHOW CREATE TABLE` / `SHOW CREATE VIEW` output on each object as the
`trino.ddl` attribute, and `generate-changelog` replays that text as a raw SQL change rather
than rebuilding a `<createTable>` / `<createView>`. A structured change cannot hold what
Trino actually needs:

| In `SHOW CREATE` | Why a structured change cannot hold it |
| --- | --- |
| `WITH (format, format_version, partitioning, location, sorted_by)` | no change type carries table properties; partitioning is not expressible at all |
| `COMMENT ON TABLE` / `COMMENT ON COLUMN` | comments are DDL in Trino, not an attribute of `<createTable>` |
| `SECURITY DEFINER` | no attribute for it |
| `ARRAY` / `ROW` / `map` column types | no Liquibase data type maps onto them |

Column types read from `information_schema` are lossy as well: a `varchar` declared without
a length comes back as `character_maximum_length = 2147483647`, which would recreate a
different table.

The cost of that choice is in [Known limitations](#known-limitations).


## Bypassing `H2Database.setConnection`

`H2Database.setConnection()` runs `SELECT SCHEMA()` before calling its parent, to cache the
current schema name. Trino has no `schema` function (it has `current_schema`), so the query
throws and every connection logs `Could not read current schema name: Function 'schema' not
registered`.

The usual `super` does not reach a grandparent in Java, and `Method.invoke` would not help: it
is a virtual call and would dispatch back into `H2Database`. The plugin uses
`MethodHandles.findSpecial` to invoke `AbstractJdbcDatabase.setConnection` directly.

## Why `getReservedWords()` is overridden

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

## `snapshot` and `generate-changelog` need the schema named

Both commands must be told which schema to work on. Two ways, both verified against the stand:

```bash
liquibase --url="jdbc:trino://host:8081/iceberg_catalog" \
          --schemas=dev_migrations snapshot

liquibase --url="jdbc:trino://host:8081/iceberg_catalog/dev_migrations" \
          snapshot
```

Omitting it does not fail, and that is the whole trap. The chain, as measured on the stand:

| URL | session `current_schema` | `getDefaultSchemaName()` | `getDefaultSchema()` | snapshot result |
| --- | --- | --- | --- | --- |
| `…/iceberg_catalog` | `NULL` | `null` | `iceberg_catalog.DEFAULT` | the catalog object and nothing else |
| `…/iceberg_catalog/dev_migrations` | `dev_migrations` | `dev_migrations` | `iceberg_catalog.dev_migrations` | the schema and its objects |

A URL naming only a catalog leaves Trino with no session schema, and it reports that as SQL
`NULL` rather than as a name. `getConnectionSchemaName()` returns `null`, `getDefaultSchema()`
carries a null schema, and `CatalogAndSchema` renders the missing part as the literal
`DEFAULT` — so `SnapshotCommandStep` goes looking for `iceberg_catalog.DEFAULT`, which does not
exist, matches nothing, and the command exits successfully with a document containing only the
catalog. Liquibase logs `Set default schema name to <name>` when it resolves one; that line
missing from the log is the tell.

`--schemas` takes a comma-separated list and all of it comes back in one snapshot:
`--schemas=probe_a,probe_b` returns both schemas and both tables. Every name in the list,
though, is resolved in the connection's default catalog, because Liquibase builds each entry as
`new CatalogAndSchema(null, name)` and lets the database fill the catalog in —
`--schemas=system.runtime` looks for `iceberg_catalog.runtime` and finds nothing. There is no
API for "every schema this connection can see", so the schemas have to be enumerated, and a
schema in another catalog is unreachable through this argument.

Two routes that look equivalent and are not:

- putting the schema in the URL sets the Trino session schema, which is what
  `getConnectionSchemaName()` reads;
- `--schema-name` is read by `AbstractDatabaseConnectionCommandStep` from
  `GlobalConfiguration.LIQUIBASE_SCHEMA_NAME` (`liquibase.liquibaseSchemaName`) and applied
  with `Database.setDefaultSchemaName(...)`. This is the CLI's flag, but the value has to
  arrive through the `Configuration`, which a programmatic `CommandScope` does not populate, so
  it was not exercised by the tests. It is not a substitute for `--schemas` in anything this
  project verifies.

## Not supported

| Change or feature | Why |
| --- | --- |
| `<createIndex>`, `Index` in a snapshot | `TrinoDatabase.supports(Index)` → `false` |
| `<addPrimaryKey>`, `<addForeignKeyConstraint>`, `<addUniqueConstraint>` | the same, for `PrimaryKey`, `ForeignKey`, `UniqueConstraint`; Trino enforces nothing at DDL time |
| `<modifyDataType>` | not supported by Trino itself |
| `<addNotNullConstraint>` | not supported by Trino itself |
| Sequences | `supportsSequences()` → `false`; Trino has no sequences |
| `dropAll` | reads foreign keys through the JDBC metadata API; Trino has no `information_schema.table_constraints`. The tests reset the stand with `DROP SCHEMA ... CASCADE` |
| `diff` | the comparison is structural and does not consider the `trino.ddl` attribute; the command itself is not covered by a test |

Because the constraint types are unsupported, `SHOW CREATE TABLE` output for an Iceberg table is
the only record of them; a generated changelog will not recreate them as constraints.

## Test stand

Three services to provide Trino + Iceberg working setup, plus one one-shot init job:

| Service | Image | Role |
| --- | --- | --- |
| `silo` | `pgsty/silo` | S3-compatible object storage (a maintained MinIO fork) |
| `silo-init` | `pgsty/silo` | creates the `warehouse` bucket via `mcli`, then exits |
| `iceberg-rest` | `apache/iceberg-rest-fixture` | Iceberg REST catalog over a SQLite-backed `JdbcCatalog` |
| `trino` | `trinodb/trino:464` | the Trino cluster under test |

Iceberg rather than the memory connector is load-bearing, not incidental: memory does not
support `DELETE`/`UPDATE`, which the tracking tables need, and the stand sets
`iceberg.format-version=2` because row-level `DELETE`/`UPDATE` exist only in format 2 — Trino
applies that to every table it creates, Liquibase's included. Trino 464 cannot write to the
local filesystem, hence the object storage.

Every service has a healthcheck, and Trino's runs a real query rather than a hit on
`/v1/info` (which answers before the catalogs are loaded). So `docker compose up -d --wait`,
which `run-tests.sh` uses, blocks until migrations can actually run, and neither the script nor
CI polls for readiness. CI runs `./run-tests.sh` itself, so there is one implementation of that.

Notes on individual tests — **89 tests, 54 needing the stand, 35 without**:

- `TrinoDatabaseUnitTest` (18) — dialect properties that need no connection: short name,
  priority, default port, driver, identifier case, product name, plus the two that the shim
  exists for: lower-casing of escaped column names, and that `setAutoCommit` is a no-op because
  the Trino driver rejects it. Also pins that reserved-word lookup and identifier escaping send
  no version queries, which is the `SELECT version()` regression described above.
- `TrinoSqlGeneratorsUnitTest` (16) — the SQL every plugin generator emits, as strings: the lock
  table is created without a primary key and with `TIMESTAMP` rather than H2's `datetime`, and
  the changelog `SELECT` keeps its column list lower-case (the base generator upper-cases it),
  applies `WHERE`/`ORDER BY` and a plain `LIMIT`. Also pins that every generator applies to
  `TrinoDatabase` only, and that the two ALTER overrides emit Trino's spelling
  (`ADD COLUMN <name>`, `RENAME COLUMN old TO new`) rather than the H2 form the base generator
  picks — including that `NOT NULL` and `DEFAULT` survive the `ADD COLUMN` override, since it only
  adds a keyword.
- `TrinoDatabaseIntegrationTest` (3) — that `DatabaseFactory` picks `TrinoDatabase` from a
  real connection, that the picked dialect lower-cases identifiers, and that the dialect claims
  the live connection while declining one whose product name is absent (an offline connection,
  which reports `null` and used to make `isCorrectDatabaseImplementation` throw).
- `TrinoMetadataSnapshotIntegrationTest` (3) — a regression test for locating existing tables
  (`SnapshotGeneratorFactory.has(...)`). The H2 dialect upper-cased the schema while
  Trino stores unquoted identifiers in lower case, which made Liquibase try to recreate
  tables that already existed. The metadata test drives the schema in upper case on
  purpose, so a regression to `UPPER_CASE` fails rather than passes.
- `TrinoChangeLogUpdateIntegrationTest` (6) — a full `update` run. Asserts both tracking tables exist,
  that all three changesets are recorded **in execution order with their `ORDEREXECUTED`,
  `EXECTYPE` and `FILENAME`**, that `DATABASECHANGELOGLOCK` is left with `LOCKED = false`
  and an empty `LOCKEDBY`, and that the fixture produced a five-row `test_table` with
  data and a four-row `test_view` over it. Also checks that a repeated `update` duplicates
  neither data nor changelog rows.
- `TrinoRollbackIntegrationTest` (3) — the fixture is applied once in `@BeforeAll`, then the
  tests roll it back one changeset at a time: the writer variant
  (`rollback(count, contexts, writer)`) must emit the `DROP`/`DELETE` statements without
  executing them, `rollback(1)` on v2 must restore the view and remove the rows v2
  inserted, and `rollback(1)` on v1 must drop the table and the view. Each step checks
  the resulting `DATABASECHANGELOG` contents and that the lock is released, so a
  changelog row that outlives its changeset fails the run. The class leaves the fixture rolled
  back: the classes that read it get it back through `applyIfNeeded()`, and restoring it here
  too would just apply the same changesets twice per run.
- `TrinoReadCommandsIntegrationTest` (6) — the commands that read the tracking table back rather
  than write to it: `status` (including `getChangeSetStatuses`, which must read back a stored
  checksum per changeset, and an empty unrun list), `history` (changesets in execution order
  with their `orderexecuted` and timestamps), and `tag`/`tagExists`. All of them go through
  `TrinoSelectFromDatabaseChangeLogGenerator`, so they are the tests that would catch a defect
  in it — which surfaces as wrong output, not as an exception. The two `tag` tests are ordered
  because the second asserts a tag written by the first; `tagExists` also goes through the
  `ByTag` where-clause, so the tag must be readable by a tagged `SELECT`, not merely stored.
- `TrinoChangelogFormatIntegrationTest` (4) — the same objects expressed through `<sqlFile>`
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
- `TrinoChangelogRollbackUnitTest` (1) — that an empty XML `<rollback/>` is parsed into an
  `EmptyChange`. Needs no running Trino. The *contents* of the rollback blocks are checked
  by `TrinoRollbackIntegrationTest`, which parses the same fixture and then executes the
  generated SQL on Trino, so it covers strictly more.
- `TrinoSnapshotCommandIntegrationTest` (3) — the `snapshot` command end to end: the fixture's
  table, view and schema come back, the `trino.ddl` attribute holds the server's text
  including `WITH (` and `location = 's3://`, and the output carries no `indexes` or
  `primaryKeys` (the regression that `getIndexInfo` used to cause). Two runs are compared
  after normalising the fields that cannot be equal — `created`, the snapshot id and object
  ids — so the comparison fails on a real difference instead of on a timestamp.
- `TrinoGenerateChangelogIntegrationTest` (3) — `generate-changelog` and the round trip. The
  generated body is compared character for character with `SHOW CREATE`, then the objects
  are dropped, the generated file is applied, and `SHOW CREATE` is read again and compared
  to what it said before. The second comparison is the one that matters: it fails if the
  DDL is merely printable rather than replayable.
- `TrinoGoldenSqlTest` (18) — the golden layer. 17 change types, each with a changelog under
  `src/test/resources/liquibase/ext/trino/golden/changelogs/` and the SQL it must produce
  under `golden/expected-sql/` (`comments.xml` is exercised twice, which is why there are 18
  cases and 17 changelogs). Each case runs twice: the writer output is compared with the
  golden file verbatim, and then the changelog is really applied, checked through
  `information_schema`, rolled back, and its schema dropped. The two halves cover each
  other — a verbatim comparison alone would pass SQL Trino rejects, and an apply alone
  would let the SQL change silently.

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

## Known limitations

The list below is duplicated from the README's "Known limitations", which is the user-facing
copy; see there for the full statements.
- **`diff` reports a difference that `diffChangelog` then does nothing about.** A table that
  differs only in its connector properties — `format`, `format_version`, `partitioning`,
  `location`, or a comment — has no structural field to differ in; the difference lives only in
  the `trino.ddl` attribute. Core's `DefaultDatabaseObjectComparator` compares the union of
  attribute names generically, so it does detect this: `diff` lists the table as *changed*. But
  `TrinoDdlChangeGenerator` implements `MissingObjectChangeGenerator` only, so nothing generates
  a change for it and `diffChangelog` writes an empty changelog. The user is told the schemas
  differ and handed nothing to do about it. Both halves are pinned by tests.

  Closing it needs a `ChangedObjectChangeGenerator` that emits a drop-and-recreate from the
  verbatim statement, which would destroy data on every `format` tweak — a design decision, not
  a bug fix.
- **A column that cannot be mapped to a JDBC type still fails the whole command.** Table and view
  reads are contained per object, but column metadata is read by core's
  `ColumnSnapshotGenerator`, whose `addTo` runs outside any generator this plugin can wrap. A
  `type not supported` there aborts `snapshot` and `generate-changelog` regardless of the guards
  below. Fixing it means registering a Trino column generator, which is a larger change than the
  per-object containment it was scoped to.
- **An object that cannot be read faithfully is left out of the snapshot, with a warning.** This
  covers both failure paths — the metadata read and the `SHOW CREATE` read — and it applies the same
  way to a table and to a view, because `TrinoDdlFetcher` is the single place that decides. The
  command succeeds; the object does not appear in the generated changelog. The warning naming it is
  the only trace, so do not run these commands with warnings filtered out.

  The alternative would be to keep the object and let core reconstruct a `CREATE TABLE` from
  `information_schema`, which is what an earlier version did for tables. That is not a degraded copy
  of the object but a different one: `SHOW CREATE TABLE` can refuse a table that Trino does not
  consider an Iceberg table at all — a catalog over one metastore routinely holds leftovers of
  another connector — and such a table is described by `external_location`, `format` and `serde`,
  none of which have a column in `information_schema`. Applying that changelog would create a new,
  empty Iceberg table where the original was. An absent object is recoverable; a wrong one is not.

  One consequence worth knowing: if *every* object in the schema is skipped, the change set is empty
  and no changelog file is written at all. The command still succeeds — check the warnings, not the
  file's existence. Verified by `TrinoSnapshotFailureIntegrationTest`,
  `TrinoTableSnapshotGeneratorUnitTest` and `TrinoDdlFetcherUnitTest`.
- The schema must be named for `snapshot` and `generate-changelog`, and omitting it is silent.
- Verbatim DDL cannot express anything Trino does not print — the constraint types above.
- A generated changelog replays only against the same catalog and schema; the DDL carries
  `location = 's3://…'`.
- Rollback for a generated changeset drops the object: it carries no rollback of its own.
- A schema named `information_schema` is never snapshotted. Verified: `--schemas=information_schema`
  returns only the catalog. `TrinoDatabase` inherits `AbstractJdbcDatabase.isSystemObject`, which
  treats any object whose *schema* is named `information_schema` as a system object, and the
  snapshot drops those before any generator runs. `TrinoSchemaSnapshotGenerator` itself does not
  consult `isSystemObject`, which is why a direct generator lookup can still find it — so the fix,
  if the behaviour is ever wanted, is an `isSystemObject` override on `TrinoDatabase`.
- `--schemas` accepts several schemas but resolves all of them in the connection's default
  catalog; a schema in another catalog is unreachable.
- `TrinoSelectFromDatabaseChangeLogGenerator` duplicates FSL-1.1-ALv2 code from liquibase-core,
  which exposes no protected seam to reduce it to a single method.
- `LIMIT` in the read generator is unreachable — nothing in liquibase-core 5.0.4 calls `setLimit`.
- `update` after `rollback` in the same JVM can silently do nothing, because Liquibase 5's
  `FastCheckService` caches its answer per JVM and `rollback` never invalidates it.
  `TrinoTestSupport` clears the cache around every `update`/`rollback`, and `applyIfNeeded()`
  verifies the resulting row count through plain JDBC. Do not read `Run: N` as "N changesets
  executed": without a `DefaultChangeExecListener` Liquibase fills it from
  `statusVisitor.getChangeSetsToRun().size()`, the changesets it *expects* to run.