# grow-zipkin (Python)

Python SDK for [Zipkin](https://grow-codes-packages.github.io/zipkin/) by Grow Codes.
Unofficial and community maintained; "Zipkin" is a trademark of The OpenZipkin authors.

```bash
pip install grow-zipkin
```

```python
from grow_zipkin import ZipkinClient, ZipkinConfig

# Reads GROW_ZIPKIN_URL, GROW_ZIPKIN_TOKEN, ... from the environment.
with ZipkinClient.from_env() as client:
    print(client.health())
    print(client.server_version())
```

The async client has the same methods:

```python
import asyncio
from grow_zipkin import AsyncZipkinClient

async def main() -> None:
    async with AsyncZipkinClient.from_env() as client:
        print(await client.health())

asyncio.run(main())
```

- Python 3.10 or newer, typed (`py.typed`), `httpx` based, environment proxies ignored unless
  configured.
- Retries with full-jitter backoff and `Retry-After`, connect/request/total timeouts,
  cancellation, hooks and a silent-by-default `logging` logger named `grow_zipkin`.
- Credentials are wrapped in `Secret` and never appear in `repr()`, logs or error messages.
- `grow_zipkin.testing` ships in-memory fakes and the conformance runner.

Documentation: https://grow-codes-packages.github.io/zipkin/ ·
Source: https://github.com/grow-codes-packages/zipkin · Licence: Apache-2.0
