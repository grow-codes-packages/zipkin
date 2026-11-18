# TypeScript example

A tour of `@grow-codes/zipkin`: health and version, OpenTelemetry tracing through the native
Zipkin exporter, the direct span reporter and the query API.

```sh
(cd ../../typescript && npm install && npm run build)
npm install
GROW_ZIPKIN_URL=http://127.0.0.1:9411 npm start
```

Start a Zipkin first, e.g. `docker run --rm -p 9411:9411 openzipkin/zipkin:3.6.1`.
Replace the `file:` dependency in `package.json` with a version (`npm install @grow-codes/zipkin`)
to run it against the published package.
