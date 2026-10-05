# Liquibase Trino Plugin

A Liquibase extension that lets Liquibase manage DDL migrations against a data warehouse
built on [Trino](https://trino.io/) and [Apache Iceberg](https://iceberg.apache.org/).

Published to GitHub Packages as `io.github.leonid-efremov-flocktory:liquibase-trino` and
consumed from Maven. 
All dependencies are declared in `pom.xml`.

## Why this exists

Liquibase picks a `Database` implementation based solely on the JDBC connection's
product name (`DatabaseMetaData.getDatabaseProductName()` — the Trino driver reports
`Trino`), and the `liquibase.databaseClass` setting does not force that choice. There is
no built-in Trino support, so we need a class that:

1. is recognized as the correct implementation for a Trino connection
   (`isCorrectDatabaseImplementation()`);
2. is as close to Trino's dialect as possible, so that the `DATABASECHANGELOG` /
   `DATABASECHANGELOGLOCK` tracking tables and the queries against them are valid.

PostgreSQL is the nearest dialect, but it cannot be used: Liquibase runs
`SHOW SEARCH_PATH` / `SET SEARCH_PATH` on connect, and Trino has no such thing. 
**H2** lacks that init SQL and provides type mappings that are all valid in Trino:
`datetime`→`TIMESTAMP`, `boolean`→`BOOLEAN`, `int`→`INT`, `varchar`→`VARCHAR`.

`H2Database` ships inside `liquibase-core`; all this plugin adds on top is the H2 JDBC
driver.

## Components

| File | Purpose |
|---|---|
| `database/TrinoDatabase.java` | `extends H2Database`. Overrides: `isCorrectDatabaseImplementation()` (product name `Trino`), `getShortName()` → `trino` (so `dbms="trino"` keeps working in changelogs), `getDefaultDriver()` → `io.trino.jdbc.TrinoDriver`, `getPriority()` = 510, `isCaseSensitive()` → `false` (Trino JDBC returns metadata in lower case), `unquotedObjectsAreUppercased = false` (Trino stores unquoted identifiers in lower case), `getCurrentDateTimeFunction()` → `CURRENT_TIMESTAMP`, `supportsSequences()` → `false`, `supportsDDLInTransaction()` → `false` plus a no-op `setAutoCommit()` (Trino runs DDL outside transactions and its driver does not support `setAutoCommit(false)`). |
| `sqlgenerator/TrinoSelectFromDatabaseChangeLogGenerator.java` | A copy of the base `SelectFromDatabaseChangeLogGenerator` **without** the `.toUpperCase()` on the column list. Even after lower-case escaping the base generator emits `SELECT ID, AUTHOR, ...`, while Trino stores column names in lower case. Priority 510, applies only to `TrinoDatabase`. |
| `snapshot/TrinoSchemaSnapshotGenerator.java` | Schema snapshot via JDBC `getSchemas()` with case-insensitive matching on `catalog + schema`, raising `InvalidExampleException` on ambiguity. The base `SchemaSnapshotGenerator` fails on Trino with `Found multiple catalog/schemas matching iceberg_catalog.dev_migrations`. The `replaces()` method is mandatory: `SnapshotGeneratorChain` calls **every** generator by priority. |

## Dependencies

All dependencies live in `pom.xml` — the single source of truth:

| Dependency | Scope | Why |
|---|---|---|
| `io.trino:trino-jdbc:464` | compile | Trino JDBC driver; reports product name `Trino` |
| `com.h2database:h2:2.4.240` | compile | H2 driver for `H2Database`, which `TrinoDatabase` extends |
| `org.liquibase:liquibase-core:5.0.3` | provided | Supplied by Liquibase itself, not bundled |
| `org.junit.jupiter:junit-jupiter:5.10.2` | test | Tests |

> **Note on h2.** The Liquibase 5.0.3 distribution already ships `internal/lib/h2.jar` at
> the same version (2.4.240). When building an image, h2 must be excluded from the
> copied dependencies — otherwise two H2 drivers end up on the classpath and Liquibase
> prints `*** Duplicate JAR files ***` on every start.

## Installation

Add to the consumer's `pom.xml`:

```xml
<dependency>
    <groupId>io.github.leonid-efremov-flocktory</groupId>
    <artifactId>liquibase-trino</artifactId>
    <version>0.1.0</version>
</dependency>
```

Or with a single Maven invocation:

```bash
mvn dependency:copy-dependencies \
    -DincludeScope=runtime \
    -DexcludeArtifactIds=h2 \
    -Dartifact=io.github.leonid-efremov-flocktory:liquibase-trino:0.1.0
```

Liquibase loads every jar from `internal/lib` and `internal/extensions`, so no separate
plugin registration is required.

## Build

```bash
mvn package
```

Requires JDK 17.

## Release

Releases are driven by tags. Pushing `v*` runs
[`.github/workflows/publish.yml`](.github/workflows/publish.yml), which:

1. verifies the tag matches the `<version>` in `pom.xml`;
2. starts the test stand from `src/test/trino/docker-compose.yml` and waits until Trino
   answers `SELECT 1`;
3. runs `mvn test`;
4. deploys to GitHub Packages — only if the tests pass.

```bash
git tag v0.1.0 && git push origin main && git push origin v0.1.0
```

The deploy step uses the workflow's built-in `GITHUB_TOKEN`. No additional secrets are
needed for publishing from CI.

## Tests

Tests live in `src/test/java` (JUnit 5). Integration tests need the stand from
`src/test/trino/docker-compose.yml` (catalog `iceberg_catalog`, port 8081):

```bash
./run-tests.sh          # start the stand, run the suite, stop it
./run-tests.sh up       # only start it (e.g. for manual debugging)
./run-tests.sh down     # stop it
./run-tests.sh test     # run the tests against an already running stand
./run-tests.sh ps       # show the objects created by the migrations
```

Extra arguments after the command are passed through to Maven, e.g.
`./run-tests.sh test -Dtest=TrinoRollbackIntegrationTest`.

If Maven is not installed on the host, the script runs it inside a
`maven:3.9-eclipse-temurin-17` container (Trino is reachable via `host.docker.internal`).
The Trino URL and user can be overridden with `TRINO_TEST_URL` / `TRINO_TEST_USER`.

The suite does not depend on the migrations repository: all test classes share a single
fixture, `src/test/resources/liquibase/ext/trino/test-changelog.xml`. It is fully
self-contained — its two changesets are formatted SQL files that create their own
`test_table` (with `INT`/`VARCHAR`/`TIMESTAMP` columns and seeded rows) and `test_view`
in `dev_test_schema` of the `iceberg_catalog` catalog, so nothing is read from external
storage.

`TrinoChangeLogUpdateTest` applies the fixture from scratch and
`TrinoRollbackIntegrationTest` reuses that state through `applyIfNeeded()`, so the fixture
is applied once per run. Any class also passes on its own (`-Dtest=...`), which is what
CI does for debugging.

The migrations are left applied after a run, so the result can be inspected:

```bash
./run-tests.sh up && ./run-tests.sh test && ./run-tests.sh ps
```

### Test stand

Three services, all pinned to an explicit version tag:

| Service | Image | Role |
| --- | --- | --- |
| `silo` | `pgsty/silo` | S3-compatible object storage (a maintained MinIO fork) |
| `iceberg-rest` | `apache/iceberg-rest-fixture` | Iceberg REST catalog over a SQLite-backed `JdbcCatalog` |
| `trino` | `trinodb/trino:464` | the Trino cluster under test |

Two properties of this stand are load-bearing:

- **Iceberg, not the memory connector.** The memory connector rejects `DELETE`/`UPDATE`,
  and Liquibase needs both for `DATABASECHANGELOGLOCK`, so neither `update` nor `rollback`
  could run against it. Iceberg supports row-level modification.
- **`iceberg.format-version=2`.** Row-level `DELETE`/`UPDATE` exist only in format
  version 2, so without this the tracking tables themselves would be unwritable.
  Trino applies the setting to every table it creates, including Liquibase's.

Trino 464 cannot write to a local filesystem (there is no `file://` scheme), which is why
object storage is part of the stand at all.

Notes on individual tests:

- `TrinoDatabaseUnitTest` — dialect properties that need no connection: short name,
  priority, default port, driver, identifier case. 12 tests.
- `TrinoDatabaseIntegrationTest` — that `DatabaseFactory` picks `TrinoDatabase` from a
  real connection, and that the picked dialect lower-cases identifiers.
- `TrinoMetadataSnapshotTest` — a regression test for locating existing tables
  (`SnapshotGeneratorFactory.has(...)`). The H2 dialect upper-cased the schema while
  Trino stores unquoted identifiers in lower case, which made Liquibase try to recreate
  tables that already existed. The metadata test drives the schema in upper case on
  purpose, so a regression to `UPPER_CASE` fails rather than passes.
- `TrinoChangeLogUpdateTest` — a full `update` run. Asserts both tracking tables exist,
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
  changelog row that outlives its changeset fails the run. `@AfterAll` leaves the fixture
  applied so the objects stay visible.
- `TrinoChangelogRollbackUnitTest` — that an empty XML `<rollback/>` is parsed into an
  `EmptyChange`. Needs no running Trino. The *contents* of the rollback blocks are checked
  by `TrinoRollbackIntegrationTest`, which parses the same fixture and then executes the
  generated SQL on Trino, so it covers strictly more.

Every rollback test performs its own `rollback` call and asserts the outcome in the same
method. The order annotation is load-bearing: each changeset can be rolled back exactly
once, so an intermediate failing assertion would otherwise leave the stand in a state the
next test does not expect.

Integration tests skip when Trino is unreachable. The probe runs a real `SELECT 1`, not
`DriverManager.getConnection` — the Trino JDBC driver connects lazily and returns a
connection object even against a dead port, so a connection-based probe always reported
reachable and every test failed inside `@BeforeAll` instead of skipping.

## Known limitations

- `liquibase dropAll` does not work against Trino: it reads foreign keys through the JDBC
  metadata API, and Trino has no `information_schema.table_constraints` view. The tests
  reset the stand with `DROP SCHEMA ... CASCADE` instead.
- `TrinoSelectFromDatabaseChangeLogGenerator` duplicates logic from liquibase-core's
  `SelectFromDatabaseChangeLogGenerator`, which is licensed FSL-1.1-ALv2. The base class
  exposes no protected seam (only `generateSql` and `validate`), so the override could
  not be reduced to a single method without an upstream change. All LPM community
  extensions currently use FSL-1.1-ALv2 rather than MIT; if this plugin is contributed
  upstream, expect the license to be relicensed to match.
- **`update` after `rollback` in the same JVM can silently do nothing.** Liquibase 5 added a
  fast path to `update`: before taking the lock, `AbstractUpdateCommandStep` asks
  `FastCheckService` whether there is anything to run. That service caches its answer per
  JVM under a key of `contexts/labels/schema/catalog/URL/changelog path`, and `rollback`
  never invalidates it (`UpdateCommandStep.cleanUp()` resets the history service only, and
  `RollbackCommandStep` does not touch fast-check at all). The result is a sequence like
  *update → no-op update → rollback → update*, where the final `update` prints
  `Database is up to date, no changesets to execute`, never acquires the lock, and applies
  nothing — while the summary printed by that same command reports `Run: 2`. A missing
  `Successfully acquired change log lock` line in the log is the tell, and it holds in
  Liquibase 5.0.4 and on `master` as well.

  `TrinoTestSupport` works around it by calling `FastCheckService.clearCache()` before each
  `update`/`rollback`, and `applyIfNeeded()` verifies the resulting `DATABASECHANGELOG` row
  count through plain JDBC so a no-op update fails loudly instead of leaving the stand rolled
  back. This is a Liquibase bug rather than a plugin one and reproduces without this plugin.
  The sequence `update → rollback → update` is therefore **not** covered by a test: it is
  Liquibase's bug, and the workaround is verified indirectly, by `applyIfNeeded()`.

  Do not read `Run: N` as "N changesets executed". Without a `DefaultChangeExecListener`,
  Liquibase fills it from `statusVisitor.getChangeSetsToRun().size()` — the changesets it
  *expects* to run. Assert on the database, not on the summary.
