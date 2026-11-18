# Python examples

Install the package, point it at a running Zipkin and run an example:

```bash
pip install grow-zipkin
export GROW_ZIPKIN_URL=http://127.0.0.1:9411
export GROW_ZIPKIN_TOKEN=...   # when the service needs one
python examples/python/basic.py
```

| File | Shows |
| - | - |
| `basic.py` | sync client: health, ping, server version, error handling |
| `async_basic.py` | async client, cancellation by task |
| `hooks_and_logging.py` | request hooks, the `grow_zipkin` logger, retries and timeouts |
