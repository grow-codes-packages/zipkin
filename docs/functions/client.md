# Client and status

`ZipkinClient` talks to one Zipkin server (port 9411). It owns the HTTP connections; the query
client, the reporter and the tracing handles created through it share them. Stock Zipkin has
**no authentication** — when a gateway sits in front of it, put the credentials in `headers`
(they are sent on every call, including span ingest, and redacted everywhere).

## Create and close

=== "TypeScript"

    ```ts
    import { ZipkinClient } from "@grow-codes/zipkin";

    const client = ZipkinClient.fromEnv();            // GROW_ZIPKIN_URL, ZIPKIN_URL, …
    const gw = new ZipkinClient({
      url: "https://gw.example/zipkin",
      headers: { authorization: `Bearer ${process.env.GW_TOKEN}` },
    });
    await client.close(); // idempotent; also shuts down tracing handles from client.initTracing
    ```

=== "Rust"

    ```rust
    use grow_zipkin::{ZipkinClient, ZipkinConfig};

    let client = ZipkinClient::from_env()?;
    let gw = ZipkinClient::new(
        ZipkinConfig::builder()
            .url("https://gw.example/zipkin")
            .header("authorization", format!("Bearer {token}"))
            .build()?,
    )?;
    client.close().await; // idempotent
    ```

=== "Go"

    ```go
    client, err := zipkin.NewClientFromEnv()
    if err != nil {
        return err
    }
    defer client.Close() // idempotent; later calls fail with CANCELLED

    gw, err := zipkin.NewClient(zipkin.Config{CommonConfig: zipkin.CommonConfig{
        URL:     "https://gw.example/zipkin",
        Headers: map[string]string{"Authorization": "Bearer " + token},
    }})
    ```

=== "Python"

    ```python
    from grow_zipkin import AsyncZipkinClient, ZipkinClient, ZipkinConfig

    with ZipkinClient.from_env() as client:
        ...
    gw = ZipkinClient(
        ZipkinConfig(url="https://gw.example/zipkin", headers={"Authorization": f"Bearer {token}"})
    )
    async with AsyncZipkinClient.from_env() as aclient:
        ...
    ```

=== "Java"

    ```java
    try (ZipkinClient client = ZipkinClient.fromEnv()) {
        // ...
    }
    ZipkinClient gw = ZipkinClient.create(ZipkinConfig.builder()
        .url("https://gw.example/zipkin")
        .header("Authorization", "Bearer " + token)
        .build());
    ```

Any call on a closed client fails with `CANCELLED` ("client is closed"); `initTracing` on a
closed client fails with `CONFIG`.

## health

`GET /health` → `{"status":"UP"}`. A 503 (`DOWN`, storage unavailable) gives `healthy: false`
without retries; an unreachable server gives `status: "unreachable"`. Never fails except for
`CANCELLED` and `CONFIG`.

=== "TypeScript"

    ```ts
    const h = await client.health();
    if (!h.healthy) console.warn(h.status, h.details);
    ```

=== "Rust"

    ```rust
    let h = client.health().await?;
    println!("{} {}", h.healthy, h.status);
    ```

=== "Go"

    ```go
    h, err := client.Health(ctx)
    fmt.Println(h.Healthy, h.Status, h.Details["httpStatus"])
    ```

=== "Python"

    ```python
    h = client.health()
    print(h.healthy, h.status)
    ```

=== "Java"

    ```java
    HealthStatus h = client.health();
    System.out.println(h.healthy() + " " + h.status());
    ```

## ping

A round trip through the query API and the storage: `GET /api/v2/services`. Behind a gateway it
also proves the credentials (`AUTHENTICATION` on 401). Returns the latency.

=== "TypeScript"

    ```ts
    const ms = await client.ping();
    ```

=== "Rust"

    ```rust
    let latency: std::time::Duration = client.ping().await?;
    ```

=== "Go"

    ```go
    latency, err := client.Ping(ctx)
    ```

=== "Python"

    ```python
    ms = client.ping()
    ```

=== "Java"

    ```java
    Duration latency = client.ping();
    ```

## serverInfo / serverVersion

`GET /info` → `{"zipkin":{"version":"3.6.1","commit":"b2b0c98"}}`. `serverInfo()` returns both
fields (empty when the server or a gateway answers 404); `serverVersion()` returns the version
and fails with `UNSUPPORTED` when there is none.

=== "TypeScript"

    ```ts
    const { version, commit } = await client.serverInfo();
    const v = await client.serverVersion(); // "3.6.1"
    ```

=== "Rust"

    ```rust
    let info = client.server_info().await?; // info.version, info.commit: Option<String>
    let v = client.server_version().await?;
    ```

=== "Go"

    ```go
    info, err := client.ServerInfo(ctx) // info.Version, info.Commit
    v, err := client.ServerVersion(ctx) // errors.Is(err, zipkin.ErrUnsupported) without one
    ```

=== "Python"

    ```python
    info = client.server_info()  # info.version, info.commit
    v = client.server_version()
    ```

=== "Java"

    ```java
    ZipkinServerInfo info = client.serverInfo(); // info.version(), info.commit()
    String v = client.serverVersion();
    ```

## query, reporter, createReporter

`query` is the [`ZipkinQueryClient`](query.md) and `reporter` the [`ZipkinReporter`](reporter.md)
of this client (no compression, 512 spans per POST, the config's `endpoint`); both share the
client's connections. `createReporter(options)` makes a reporter with other options
(`compression`, `maxBatchSize`, `endpoint`) on the client's settings; `close()` closes it too.

=== "TypeScript"

    ```ts
    const names = await client.query.listServices();
    const gzip = client.createReporter({ compression: "gzip", maxBatchSize: 100 });
    await gzip.report(spans);
    ```

=== "Rust"

    ```rust
    use grow_zipkin::{tracing::Compression, ZipkinReporterOptions};

    let names = client.query().list_services().await?;
    let gzip = client.create_reporter(
        ZipkinReporterOptions::default().compression(Compression::Gzip).max_batch_size(100),
    )?;
    gzip.report(&spans).await?;
    ```

=== "Go"

    ```go
    names, err := client.Query().ListServices(ctx)
    gzip, err := client.CreateReporter(zipkin.ReporterOptions{Compression: zipkin.CompressionGzip, MaxBatchSize: 100})
    err = gzip.Report(ctx, spans)
    ```

=== "Python"

    ```python
    names = client.query.list_services()
    gzip = client.create_reporter(compression="gzip", max_batch_size=100)
    gzip.report(spans)
    ```

=== "Java"

    ```java
    List<String> names = client.query().listServices();
    ZipkinReporter gzip = client.createReporter(ZipkinReporter.Options.defaults()
        .compression(TracingConfig.Compression.GZIP)
        .maxBatchSize(100));
    gzip.report(spans);
    ```

## initTracing

Starts OpenTelemetry tracing to this Zipkin (see [Tracing setup](tracing-setup.md)). With the
native protocol and no explicit `endpoint`, spans go to the client's `endpoint` with the client's
headers, TLS, proxy, retries and hooks. `close()` shuts the handle down and flushes it.

=== "TypeScript"

    ```ts
    const tracing = client.initTracing({ serviceName: "checkout" });
    ```

=== "Rust"

    ```rust
    let tracing = client.init_tracing(TracingConfig::new("checkout"))?;
    ```

=== "Go"

    ```go
    h, err := client.InitTracing(ctx, zipkin.TracingConfig{ServiceName: "checkout"})
    ```

=== "Python"

    ```python
    handle = client.init_tracing(service_name="checkout")
    ```

=== "Java"

    ```java
    ZipkinTracingHandle tracing = client.initTracing(TracingConfig.builder("checkout").build());
    ```
