#!/bin/sh
# One-shot bootstrap for the Zipkin fixture (POSIX sh, idempotent).
# Zipkin (in-memory storage) needs no init, users or tokens. This script only waits until the
# HTTP API is really serving (health UP + query API answers), checks the server version and writes
# the connection facts to /bootstrap/env for the test-<lang> containers. Nothing is ingested, so
# listServices() starts empty and every test lane owns its own service names.
set -eu

ZIPKIN_URL="${ZIPKIN_URL:-http://zipkin:9411}"
OUT=/bootstrap/env
TMP=/bootstrap/env.tmp

log() { echo "bootstrap: $*"; }

i=0
until body="$(curl -fsS "$ZIPKIN_URL/health" 2>/dev/null)" && printf '%s' "$body" | grep -q '"status" *: *"UP"'; do
  i=$((i + 1))
  [ "$i" -ge 120 ] && { log "zipkin /health not UP after 120 tries"; exit 1; }
  sleep 1
done
log "health UP"

# The query API is served by the same Armeria server; make sure it answers before tests start.
curl -fsS "$ZIPKIN_URL/api/v2/services" >/dev/null
log "query API ready"

info="$(curl -fsS "$ZIPKIN_URL/info")"
version="$(printf '%s' "$info" | sed -n 's/.*"version" *: *"\([^"]*\)".*/\1/p')"
[ -n "$version" ] || { log "could not read zipkin.version from /info"; exit 1; }
if [ -n "${EXPECTED_ZIPKIN_VERSION:-}" ] && [ "$version" != "$EXPECTED_ZIPKIN_VERSION" ]; then
  log "version mismatch: got $version, want $EXPECTED_ZIPKIN_VERSION"; exit 1
fi
log "version $version"

# Write atomically; rerunning produces the same file.
{
  echo "GROW_ZIPKIN_URL=$ZIPKIN_URL"
  echo "ZIPKIN_URL=$ZIPKIN_URL"
  echo "OTEL_EXPORTER_ZIPKIN_ENDPOINT=$ZIPKIN_URL/api/v2/spans"
  echo "ZIPKIN_VERSION=$version"
  echo "ZIPKIN_AUTOCOMPLETE_KEYS=${AUTOCOMPLETE_KEYS:-}"
  echo "ZIPKIN_STORAGE_TYPE=mem"
} > "$TMP"
chmod 0644 "$TMP"
mv "$TMP" "$OUT"
log "wrote $OUT ($(wc -l < "$OUT") lines, no secrets: Zipkin has no auth)"
