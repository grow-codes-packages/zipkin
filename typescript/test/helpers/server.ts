import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { type AddressInfo, connect, createServer as createNetServer, type Socket } from "node:net";

/** One request seen by the mock server. */
export interface RecordedRequest {
  method: string;
  url: string;
  headers: IncomingMessage["headers"];
  body: string;
}

/** A canned response. */
export interface MockResponse {
  status?: number;
  headers?: Record<string, string>;
  body?: string | object;
  /** Delay before answering, in ms. */
  delayMs?: number;
  /** Destroy the socket instead of answering. */
  destroy?: boolean;
}

export type MockHandler = (
  req: RecordedRequest,
  index: number,
) => MockResponse | Promise<MockResponse>;

export interface MockServer {
  url: string;
  port: number;
  requests: RecordedRequest[];
  close(): Promise<void>;
}

function send(res: ServerResponse, r: MockResponse): void {
  const body =
    r.body === undefined ? "" : typeof r.body === "string" ? r.body : JSON.stringify(r.body);
  const headers: Record<string, string> = {
    ...(typeof r.body === "object" ? { "content-type": "application/json" } : {}),
    ...r.headers,
  };
  res.writeHead(r.status ?? 200, headers);
  res.end(body);
}

/** Starts a local HTTP server; `handler` may be a function or a list of responses (the last repeats). */
export async function startServer(handler: MockHandler | MockResponse[]): Promise<MockServer> {
  const requests: RecordedRequest[] = [];
  const sockets = new Set<Socket>();
  const pick: MockHandler = Array.isArray(handler)
    ? (_req, i) => handler[Math.min(i, handler.length - 1)] ?? {}
    : handler;
  const server: Server = createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on("data", (c: Buffer) => chunks.push(c));
    req.on("end", () => {
      const recorded: RecordedRequest = {
        method: req.method ?? "",
        url: req.url ?? "",
        headers: req.headers,
        body: Buffer.concat(chunks).toString("utf8"),
      };
      const index = requests.length;
      requests.push(recorded);
      void Promise.resolve(pick(recorded, index)).then((r) => {
        const finish = (): void => {
          if (r.destroy === true) req.socket.destroy();
          else send(res, r);
        };
        if (r.delayMs !== undefined) setTimeout(finish, r.delayMs);
        else finish();
      });
    });
  });
  server.on("connection", (s) => {
    sockets.add(s);
    s.on("close", () => sockets.delete(s));
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = (server.address() as AddressInfo).port;
  return {
    url: `http://127.0.0.1:${port}`,
    port,
    requests,
    close: () =>
      new Promise<void>((resolve) => {
        for (const s of sockets) s.destroy();
        server.close(() => resolve());
      }),
  };
}

/** A port with nothing listening (bound then released). */
export async function closedPort(): Promise<number> {
  const s = createNetServer();
  await new Promise<void>((resolve) => s.listen(0, "127.0.0.1", resolve));
  const port = (s.address() as AddressInfo).port;
  await new Promise<void>((resolve) => s.close(() => resolve()));
  return port;
}

/** Minimal HTTP CONNECT proxy; counts tunnels. */
export async function startConnectProxy(): Promise<{
  url: string;
  tunnels: () => number;
  close(): Promise<void>;
}> {
  let tunnels = 0;
  const sockets = new Set<Socket>();
  const server = createServer();
  server.on("connect", (req: IncomingMessage, client: Socket, head: Buffer) => {
    tunnels++;
    const [host, port] = (req.url ?? "").split(":");
    const upstream = connect(Number(port), host ?? "127.0.0.1", () => {
      client.write("HTTP/1.1 200 Connection Established\r\n\r\n");
      upstream.write(head);
      upstream.pipe(client);
      client.pipe(upstream);
    });
    sockets.add(upstream).add(client);
    upstream.on("error", () => client.destroy());
    client.on("error", () => upstream.destroy());
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = (server.address() as AddressInfo).port;
  return {
    url: `http://127.0.0.1:${port}`,
    tunnels: () => tunnels,
    close: () =>
      new Promise<void>((resolve) => {
        for (const s of sockets) s.destroy();
        server.close(() => resolve());
      }),
  };
}
