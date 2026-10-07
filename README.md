# Liquibase Trino Plugin

A Liquibase extension that lets Liquibase manage DDL migrations against a data warehouse
built on [Trino](https://trino.io/) and [Apache Iceberg](https://iceberg.apache.org/).

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

`H2Database` ships inside `liquibase-core`; all this plugin adds on top is the H2 JDBC driver.

## Supported commands

| Command | Supported | Notes |
| --- | --- | --- |
| `update` / `migrate` | yes | Applies XML, YAML and JSON changelogs, `<sql>`, `<sqlFile>`, and the DDL and data change types below. |
| `rollback` / `rollback-sql` | yes | Rollback blocks in changelogs work; `TrinoChangelogRollbackUnitTest` covers parsing, the integration tests cover execution. |
| `generate-changelog` | yes | Emits verbatim DDL as raw SQL. Requires `--reference-schemas` (see below). Output must be a `*.trino.sql` file — a plain `*.sql` name is rejected by the formatted-SQL writer. |
| `status`, `history`, `tag`, `tagExists` | yes | Go through `TrinoSelectFromDatabaseChangeLogGenerator`. |
| `update-sql` | yes | Used by the golden tests. |
| `snapshot` | yes | Emits the verbatim DDL as the `trino.ddl` attribute on tables and views. Requires an explicit `--schemas`. |
| `diff` | partially | Structured comparison only; the verbatim DDL is not diffed. |

## Dependencies

All dependencies live in `pom.xml` — the single source of truth:

| Dependency | Scope | Why |
|---|---|---|
| `io.trino:trino-jdbc:464` | compile | Trino JDBC driver; reports product name `Trino` |
| `com.h2database:h2:2.4.240` | compile | H2 driver for `H2Database`, which `TrinoDatabase` extends |
| `org.liquibase:liquibase-core:5.0.4` | provided | Supplied by Liquibase itself, not bundled |
| `org.junit.jupiter:junit-jupiter:5.10.2` | test | Tests |

> **Note on h2.** The Liquibase 5.0.4 distribution already ships `internal/lib/h2.jar` at
> the same version (2.4.240). When building an image, h2 must be excluded from the
> copied dependencies — otherwise two H2 drivers end up on the classpath and Liquibase
> prints `*** Duplicate JAR files ***` on every start.

## Installation

Add to the consumer's `pom.xml`:

```xml
<dependency>
    <groupId>io.github.leonid-efremov-flocktory</groupId>
    <artifactId>liquibase-trino</artifactId>
    <version>0.1.1</version>
</dependency>
```

Or with a single Maven invocation:

```bash
mvn dependency:copy-dependencies \
    -DincludeScope=runtime \
    -DexcludeArtifactIds=h2 \
    -Dartifact=io.github.leonid-efremov-flocktory:liquibase-trino:0.1.1
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
[`.github/workflows/test.yml`](.github/workflows/test.yml), which:

1. verifies the tag matches the `<version>` in `pom.xml`;
2. runs `./run-tests.sh` — brings the stand up, waits until Trino answers `SELECT 1`,
   runs `mvn test`, tears the stand down.

The artifact itself is not published from CI: consumers (e.g. `docker/liquibase`) pull it
from [JitPack](https://jitpack.io), which builds the repository from the tag itself.
`jitpack.yml` in the repository root tells JitPack to use JDK 17.

```bash
#git tag -f
git tag v0.1.2 && git push origin main && git push origin v0.1.2
```

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
`./run-tests.sh test -Dtest=TrinoRollbackIntegrationTest`. If Maven is installed on the host
the script uses it, otherwise it runs the build in a `maven:3.9-eclipse-temurin-17` container
(Trino is reachable there via `host.docker.internal`). The Trino URL and user can be
overridden with `TRINO_TEST_URL` / `TRINO_TEST_USER`.

The 29 tests that need no stand run in well under a second without it:

```bash
mvn test -Punit
```

All test classes share a single fixture, `src/test/resources/liquibase/ext/trino/test-changelog.xml`.

Tests are grouped by the part of the plugin they cover, in packages mirroring the main sources
(`database`, `sqlgenerator`, `snapshot`) plus `changelog` for the Liquibase commands:

```
src/test/java/liquibase/ext/trino/
├── TrinoTestSupport.java          # shared fixture plumbing: connection, reset, state readers
├── database/                      # the dialect: properties, and picking it from a connection
├── sqlgenerator/                  # the SQL the generators emit
├── changelog/                     # update, rollback, status/history/tag, changelog formats
├── snapshot/                      # finding existing objects, and the two commands end to end
└── golden/                        # the SQL every supported change type produces, pinned
```

82 tests in total, of which 29 need no stand.

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

## Known limitations

- **Verbatim DDL cannot express a difference that Trino does not report.** The snapshot is
  whatever `SHOW CREATE` said, so anything Trino does not print is not in the changelog. In
  practice this means the unsupported constraint types above: an Iceberg table's constraints are
  not recoverable from the DDL text.
- **Rollback for a generated changeset drops the object.** A generated table or view changeset
  carries no rollback of its own, so `rollback` on one drops what the DDL created rather than
  restoring a previous definition. Changelog authors who need a restorable migration have to write
  the rollback block themselves.
- `TrinoSelectFromDatabaseChangeLogGenerator` duplicates logic from liquibase-core's
  `SelectFromDatabaseChangeLogGenerator`, which is licensed FSL-1.1-ALv2. The base class
  exposes no protected seam (only `generateSql` and `validate`), so the override could
  not be reduced to a single method without an upstream change. All LPM community
  extensions currently use FSL-1.1-ALv2 rather than MIT; if this plugin is contributed
  upstream, expect the license to be relicensed to match.
- **A schema named `information_schema` is never snapshotted.** `TrinoDatabase` inherits
  `AbstractJdbcDatabase.isSystemObject`, which treats any schema by that name as a system
  object, and `DatabaseSnapshot.include()` drops those before any generator runs. This is an
  upstream rule inherited from H2, not a plugin decision. The fix, if the behaviour is ever
  wanted, is an `isSystemObject` override on `TrinoDatabase` — not a change to the generator.
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
