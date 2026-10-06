#!/usr/bin/env bash
#
# Test stand (docker-compose: Trino + Iceberg REST catalog + S3 storage) and test run
# for the liquibase-trino shim.
#
# Usage:
#   ./run-tests.sh            start the stand, run the tests, stop the stand
#   ./run-tests.sh up         only start the stand
#   ./run-tests.sh down       stop the stand
#   ./run-tests.sh test       run the tests (the stand must already be running)
#   ./run-tests.sh ps         show the objects created by the migrations
#
# Any extra arguments after the command are passed through to Maven, e.g.
#   ./run-tests.sh test -Dtest=TrinoRollbackIntegrationTest
#
# Migrated objects stay in the catalog, so a run can be inspected afterwards:
#   ./run-tests.sh up && ./run-tests.sh test && ./run-tests.sh ps
#
# The Trino URL and user can be overridden via the TRINO_TEST_URL / TRINO_TEST_USER
# environment variables (see TrinoTestSupport).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$SCRIPT_DIR/src/test/trino"
COMPOSE_FILE="$COMPOSE_DIR/docker-compose.yml"
TRINO_USER="${TRINO_TEST_USER:-smoke}"
# Extra arguments after the command are passed through to Maven (see usage).
MAVEN_EXTRA_ARGS=()
# Fully qualified fixture schema (catalog.schema), used to reach its objects.
FIXTURE_SCHEMA="iceberg_catalog.dev_test_schema"
# In information_schema the schema is stored without the catalog, so the table_schema
# filter uses its bare name — with the qualified name the result was always empty.
FIXTURE_SCHEMA_NAME="dev_test_schema"

compose() {
    docker compose -f "$COMPOSE_FILE" "$@"
}

# `up --wait` blocks until every service is healthy, and the Trino healthcheck runs a real
# query, so readiness is settled here — no polling loop, and no waiting inside the tests.
up() {
    compose up -d --wait
}

# Show the objects created by the fixture changesets.
show_objects() {
    docker exec liquibase-trino-test trino --user "$TRINO_USER" --execute "
        SELECT table_name, table_type
        FROM iceberg_catalog.information_schema.tables
        WHERE table_schema = '${FIXTURE_SCHEMA_NAME}'
        ORDER BY table_type, table_name;
        SELECT format('test_table rows: %s', count(*)) FROM ${FIXTURE_SCHEMA}.test_table;
        SELECT format('test_view rows: %s', count(*)) FROM ${FIXTURE_SCHEMA}.test_view;
        SELECT id, txt, ts FROM ${FIXTURE_SCHEMA}.test_view ORDER BY id;
    " 2>&1 | grep -vE "org.jline|WARNING: Unable to create a system terminal"
}

run_tests() {
    # Extra arguments are passed through to Maven (see usage).
    #
    # A host Maven is used when there is one; otherwise the build runs in a container, where
    # Trino is reachable via host.docker.internal. On Docker Desktop (macOS/Windows) that name
    # resolves via the built-in DNS and needs no --add-host; on Linux it does need the flag,
    # since host.docker.internal there points at an unreachable gateway.
    if command -v mvn >/dev/null 2>&1; then
        mvn test \
            -Dtrino.test.url="jdbc:trino://localhost:8081/iceberg_catalog" \
            -Dtrino.test.user="$TRINO_USER" \
            "${MAVEN_EXTRA_ARGS[@]+"${MAVEN_EXTRA_ARGS[@]}"}"
        return
    fi

    local add_host=()
    if [[ "$(uname -s)" != "Darwin" ]]; then
        add_host=(--add-host=host.docker.internal:host-gateway)
    fi
    docker run --rm \
        "${add_host[@]+"${add_host[@]}"}" \
        -v "$SCRIPT_DIR":/build \
        -v liquibase-trino-m2:/root/.m2 \
        -w /build \
        maven:3.9-eclipse-temurin-17 \
        mvn test \
            -Dtrino.test.url="jdbc:trino://host.docker.internal:8081/iceberg_catalog" \
            -Dtrino.test.user="$TRINO_USER" \
            "${MAVEN_EXTRA_ARGS[@]+"${MAVEN_EXTRA_ARGS[@]}"}"
}

case "${1:-}" in
    up)
        up
        ;;
    down)
        compose down -v
        ;;
    ps)
        show_objects
        ;;
    test)
        MAVEN_EXTRA_ARGS=("${@:2}")
        run_tests
        ;;
    "")
        MAVEN_EXTRA_ARGS=("${@:1}")
        up
        run_tests
        compose down -v
        ;;
    *)
        echo "Неизвестная команда: ${1:-}" >&2
        echo "Использование: $0 [up|down|test|ps]" >&2
        exit 1
        ;;
esac