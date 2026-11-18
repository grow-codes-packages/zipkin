# Core unit conformance (template unit tests)

Each language template has unit tests with these ids in their names, run against a local mock HTTP server.

| Id | Behaviour |
| - | - |
| core.retry.429-retry-after | 429 with `Retry-After: 1` is retried after ≥ 1 s, then succeeds |
| core.retry.503-backoff | 503 twice then 200 → 3 attempts, full-jitter sleeps within bounds (injected random) |
| core.retry.exhausted | 4 × 503 → `UnavailableError`, retryable=true, httpStatus=503 |
| core.retry.post-not-replayed | POST + 500 → no retry; POST + 429 → retried |
| core.retry.retry-after-too-long | `Retry-After: 3600` → `RateLimitError` immediately with retryAfter set |
| core.timeout.request | slow server → `TimeoutError` after requestTimeout |
| core.timeout.total | total budget exhausted across retries → `TimeoutError` |
| core.cancel | cancellation during request and during backoff → `CancelledError` |
| core.errors.mapping | 400/401/403/404/409/412/413/422/429/500/501/502/503/504 map per SPEC 2.1 |
| core.redaction.error-text | a token/secret value never appears in error messages, hook data or logger output |
| core.redaction.config | config `toString` hides credentials |
| core.useragent | `User-Agent: grow-codes-<key>/<version> (<lang>)` |
| core.hooks | onRequest/onResponse/onError called with redacted data; throwing hook ignored |
| core.config.env-precedence | explicit > GROW_* > vendor > default; invalid value → ConfigError naming the variable only |
| core.close.idempotent | close twice is safe |
| core.health.unreachable | health() on a closed port → healthy=false, status=unreachable |
