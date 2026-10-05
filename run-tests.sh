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
CONTAINER_NAME="liquibase-trino-test"
SILO_CONTAINER="liquibase-trino-silo"
REST_CONTAINER="liquibase-trino-iceberg-rest"
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

wait_for_trino() {
    # Wait for a real query through the trino CLI inside the container, not just for an
    # HTTP response on /v1/info (which appears before the catalogs are loaded). Trino does
    # not start until its catalogs load, so this same poll also covers Iceberg REST catalog
    # readiness: if the fixture does not answer, iceberg_catalog never initializes and
    # Trino never starts at all.
    echo "Waiting for Trino to become ready (SELECT 1) ..."
    for _ in $(seq 1 60); do
        if docker exec "$CONTAINER_NAME" trino --user "$TRINO_USER" --execute "SELECT 1" >/dev/null 2>&1; then
            echo "Trino готов."
            return 0
        fi
        sleep 2
    done
    echo "Trino не поднялся за отведённое время." >&2
    diagnose
    return 1
}

# Diagnostics for the case where Trino never came up: dump the logs of the services the
# catalog depends on instead of guessing.
diagnose() {
    local container
    for container in "$CONTAINER_NAME" "$REST_CONTAINER" "$SILO_CONTAINER"; do
        echo "----- logs of $container (last 30 lines) -----" >&2
        docker logs --tail 30 "$container" >&2 2>&1 || true
    done
}

# Show the objects created by the fixture changesets.
show_objects() {
    docker exec "$CONTAINER_NAME" trino --user "$TRINO_USER" --execute "
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
    # Maven runs in a container, where Trino is reachable via host.docker.internal. On
    # Docker Desktop (macOS/Windows) that name resolves via the built-in DNS and needs no
    # --add-host; on Linux it does need the flag, since host.docker.internal there points
    # at an unreachable gateway — hence the flag only for Linux.
    local docker_url="jdbc:trino://host.docker.internal:8081/iceberg_catalog"
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
            -Dtrino.test.url="$docker_url" \
            -Dtrino.test.user="$TRINO_USER" \
            "${MAVEN_EXTRA_ARGS[@]+"${MAVEN_EXTRA_ARGS[@]}"}"
}

case "${1:-}" in
    up)
        compose up -d
        ;;
    down)
        compose down -v
        ;;
    ps)
        show_objects
        ;;
    test)
        MAVEN_EXTRA_ARGS=("${@:2}")
        wait_for_trino
        run_tests
        ;;
    "")
        MAVEN_EXTRA_ARGS=("${@:1}")
        compose up -d
        wait_for_trino
        run_tests
        compose down -v
        ;;
    *)
        echo "Неизвестная команда: ${1:-}" >&2
        echo "Использование: $0 [up|down|test|ps]" >&2
        exit 1
        ;;
esac