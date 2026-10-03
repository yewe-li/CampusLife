#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
MODE=${1:-run}
case "$MODE" in
  run|test)
    if [ -f .env ]; then
      set -a
      . ./.env
      set +a
    elif [ -z "${DB_PASSWORD+x}" ]; then
      echo 'Configure .env from .env.example or set DB_PASSWORD in your environment. See docs/setup.md.' >&2
      exit 1
    fi ;;
  unit|package) ;; # These goals never need local database credentials.
  *) echo 'Usage: scripts/dev.sh run|test|unit|package' >&2; exit 2 ;;
esac
if command -v mvn >/dev/null 2>&1; then
  MAVEN_COMMAND=mvn
else
  MAVEN_COMMAND="$ROOT/mvnw"
fi
case "$MODE" in
  run) exec "$MAVEN_COMMAND" -B -ntp spring-boot:run -Dspring-boot.run.profiles=dev ;;
  test) exec "$MAVEN_COMMAND" -B -ntp -Pintegration verify ;;
  unit) exec "$MAVEN_COMMAND" -B -ntp test ;;
  package) exec "$MAVEN_COMMAND" -B -ntp package ;;
esac
