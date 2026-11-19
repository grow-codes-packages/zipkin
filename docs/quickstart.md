# Quickstart

Start a local Zipkin, send one OpenTelemetry span through the package's Zipkin exporter, read the
trace back and check the server. The client reads `GROW_ZIPKIN_URL` (and the vendor variables in
[Configuration](configuration.md)) or takes the values in code. Stock Zipkin has no
authentication; gateway credentials go in `headers`.

```sh
docker run --rm -d -p 9411:9411 openzipkin/zipkin:3.6.1
export GROW_ZIPKIN_URL=http://127.0.0.1:9411
```

=== "TypeScript"

    ```ts
    import { ZipkinClient, currentTraceId, withSpan } from "@grow-codes/zipkin";

    const client = ZipkinClient.fromEnv();
    try {
      console.log("healthy:", (await client.health()).healthy, await client.serverVersion());
      const tracing = client.initTracing({ serviceName: "checkout" });
      const traceId = await withSpan("place-order", async () => currentTraceId());
      await tracing.forceFlush();
      const trace = await client.query.waitForTrace(traceId!);
      console.log(trace.spans.map((s) => `${s.serviceName}/${s.name}`));
    } finally {
      await client.close(); // also shuts the tracing handle down
    }
    ```

=== "Rust"

    ```rust
    use std::time::Duration;

    use grow_zipkin::tracing::{current_trace_id, traced, TracingConfig, WaitForTraceOptions};
    use grow_zipkin::ZipkinClient;

    #[tokio::main]
    async fn main() -> grow_zipkin::Result<()> {
        let client = ZipkinClient::from_env()?;
        println!("healthy: {} {}", client.health().await?.healthy, client.server_version().await?);
        let tracing = client.init_tracing(TracingConfig::new("checkout"))?;
        let trace_id = traced("place-order", async { Ok::<_, std::io::Error>(current_trace_id()) })
            .await
            .ok()
            .flatten()
            .unwrap_or_default();
        tracing.force_flush(Some(Duration::from_secs(5))).await?;
        let trace = client.query().wait_for_trace(&trace_id, WaitForTraceOptions::default()).await?;
        println!("{} span(s)", trace.spans.len());
        client.close().await;
        Ok(())
    }
    ```

=== "Go"

    ```go
    package main

    import (
        "context"
        "fmt"
        "log"

        zipkin "github.com/grow-codes-packages/zipkin/go"
    )

    func main() {
        ctx := context.Background()
        client, err := zipkin.NewClientFromEnv()
        if err != nil {
            log.Fatal(err)
        }
        defer client.Close() // also shuts the tracing handle down

        health, err := client.Health(ctx)
        if err != nil {
            log.Fatal(err)
        }
        fmt.Println("healthy:", health.Healthy)

        h, err := client.InitTracing(ctx, zipkin.TracingConfig{ServiceName: "checkout"})
        if err != nil {
            log.Fatal(err)
        }
        traceID, _ := zipkin.WithSpan(ctx, "place-order", func(ctx context.Context) (string, error) {
            id, _ := zipkin.CurrentTraceID(ctx)
            return id, nil
        })
        _ = h.ForceFlush(ctx)
        trace, err := client.Query().WaitForTrace(ctx, traceID, zipkin.WaitForTraceOptions{})
        if err != nil {
            log.Fatal(err)
        }
        fmt.Println(len(trace.Spans), "span(s)")
    }
    ```

=== "Python"

    ```python
    from grow_zipkin import ZipkinClient, current_trace_id, with_span

    with ZipkinClient.from_env() as client:  # close() also shuts the tracing handle down
        print("healthy:", client.health().healthy, client.server_version())
        handle = client.init_tracing(service_name="checkout")
        with with_span("place-order"):
            trace_id = current_trace_id()
        handle.force_flush()
        trace = client.query.wait_for_trace(str(trace_id))
        print([f"{s.service_name}/{s.name}" for s in trace.spans])
    ```

    Async code uses `AsyncZipkinClient` with the same methods (`async with`, `await client.health()`).

=== "Java"

    ```java
    import codes.grow.packages.zipkin.ZipkinClient;
    import codes.grow.packages.zipkin.ZipkinTracingHandle;
    import codes.grow.packages.zipkin.tracing.Spans;
    import codes.grow.packages.zipkin.tracing.TracingConfig;

    public final class Quickstart {
      public static void main(String[] args) {
        try (ZipkinClient client = ZipkinClient.fromEnv()) {
          System.out.println("healthy: " + client.health().healthy() + " " + client.serverVersion());
          ZipkinTracingHandle tracing = client.initTracing(TracingConfig.builder("checkout").build());
          String traceId = Spans.withSpan("place-order", () -> Spans.currentTraceId().orElseThrow());
          tracing.forceFlush();
          System.out.println(client.query().waitForTrace(traceId).spans());
        } // close() also shuts the tracing handle down
      }
    }
    ```

Without OpenTelemetry, send spans in the Zipkin model with the
[span reporter](functions/reporter.md) and read them back with the
[query client](functions/query.md).

## Next steps

- [Configuration](configuration.md): timeouts, retries, TLS, proxy, environment variables.
- [Functions](functions.md): the Zipkin API of this SDK.
- [Tracing setup](functions/tracing-setup.md): exporter, propagation, OTLP via a Collector.
- [Error handling](errors.md): error codes and retries.
