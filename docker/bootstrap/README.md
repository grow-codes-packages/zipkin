# docker/bootstrap

The `bootstrap` service in `docker/compose.service.yml` runs `bootstrap.sh` once, after the service
under test is healthy and before any `test-<lang>` container starts. Its job: prepare the service
(init/unseal, users, tokens, streams, buckets) and hand credentials to the tests.

| File | Owner | Purpose |
| - | - | - |
| `bootstrap.sh` | this repository | the service-specific steps (skeleton from sdk-kit, edit freely) |
| `lib.sh` | sdk-kit (`sdk-kit sync`) | helpers: `bootstrap_begin`, `bootstrap_set`, `bootstrap_commit`, `bootstrap_wait_http`, `bootstrap_wait_tcp`, `bootstrap_log`, `bootstrap_fail` |

Inside the container:

| Path | What |
| - | - |
| `/scripts` | this folder (read-only) |
| `/harness` | `docker/harness/` (read-only): `wait-for-http.sh`, `wait-for-tcp.sh` |
| `/bootstrap` | the shared named volume; tests mount it read-only |
| `$SERVICE_URL` | base URL of the service, e.g. `http://service:9411` |

## `/bootstrap/env` format

```
# comment
GROW_ZIPKIN_TOKEN=s.abcdef
SOME_URL=http://service:9411/v1
```

- One `KEY=VALUE` per line; `KEY` matches `[A-Za-z_][A-Za-z0-9_]*`.
- `VALUE` is literal up to the end of the line: no quotes, no escapes, no variable expansion.
  Readers split each line at the **first** `=`. Lines starting with `#` and empty lines are ignored.
- The file is written atomically (`bootstrap_commit` renames a temporary file), mode `0644`.
- The file may be absent when the service needs no credentials; tests must accept that.
- Values are never printed by `lib.sh`; do not echo them in your own steps either.

The image is `alpine` (BusyBox `sh`, `wget`); use any other image in `compose.service.yml` when the
bootstrap needs a vendor CLI (e.g. the service image itself), as long as it has a POSIX `sh`.
Exit non-zero on any failure: the tests then do not start and `compose-run.sh` prints this log.
