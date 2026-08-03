import http from "node:http";
import net from "node:net";
import { resolvePublicHttpUrl, UnsafeTargetError } from "./url-security.js";

const CONNECT_TIMEOUT_MS = 10_000;

/**
 * Local forward proxy which resolves and validates each destination itself,
 * then connects to the selected numeric address. Chromium never performs the
 * destination DNS lookup, closing the validation-to-connect rebinding gap.
 */
export async function startDnsPinningProxy({ lookup, upstreamProxy } = {}) {
  const server = http.createServer((request, response) => {
    void forwardHttpRequest(request, response, { lookup, upstreamProxy });
  });
  server.on("connect", (request, socket, head) => {
    void forwardConnect(request, socket, head, { lookup, upstreamProxy });
  });
  server.on("clientError", (_error, socket) => {
    socket.end("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n");
  });

  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      server.off("error", reject);
      resolve();
    });
  });
  const address = server.address();
  if (!address || typeof address === "string") {
    await closeServer(server);
    throw new Error("DNS pinning proxy failed to bind");
  }
  return {
    server: `http://127.0.0.1:${address.port}`,
    close: () => closeServer(server),
  };
}

export async function resolvePinnedTarget(rawUrl, { lookup } = {}) {
  const resolved = await resolvePublicHttpUrl(rawUrl, { lookup });
  const selected = resolved.addresses[0];
  if (!selected) {
    throw new UnsafeTargetError("resolution_failed");
  }
  const port = normalizedPort(resolved.url);
  return {
    url: resolved.url,
    address: selected.address,
    family: selected.family,
    port,
    authority: authority(selected.address, port),
  };
}

async function forwardConnect(request, clientSocket, head, options) {
  try {
    const target = await resolvePinnedTarget(`https://${request.url}/`, options);
    const upstreamSocket = options.upstreamProxy
      ? await connectThroughUpstream(target, options.upstreamProxy)
      : await connectSocket(target.address, target.port, target.family);

    clientSocket.write("HTTP/1.1 200 Connection Established\r\n\r\n");
    if (head?.length) {
      upstreamSocket.write(head);
    }
    upstreamSocket.on("error", () => clientSocket.destroy());
    clientSocket.on("error", () => upstreamSocket.destroy());
    clientSocket.pipe(upstreamSocket);
    upstreamSocket.pipe(clientSocket);
  } catch {
    clientSocket.end("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n");
  }
}

async function forwardHttpRequest(request, response, options) {
  try {
    const target = await resolvePinnedTarget(request.url, options);
    if (target.url.protocol !== "http:") {
      throw new UnsafeTargetError("connect_required");
    }
    const headers = sanitizedHeaders(request.headers, target.url.host, options.upstreamProxy);
    const usingUpstream = Boolean(options.upstreamProxy);
    const requestOptions = usingUpstream
      ? {
          hostname: options.upstreamProxy.host,
          port: options.upstreamProxy.port,
          method: request.method,
          path: `http://${target.authority}${target.url.pathname}${target.url.search}`,
          headers,
        }
      : {
          hostname: target.address,
          family: target.family,
          port: target.port,
          method: request.method,
          path: `${target.url.pathname}${target.url.search}`,
          headers,
        };
    const upstreamRequest = http.request(requestOptions, (upstreamResponse) => {
      response.writeHead(upstreamResponse.statusCode || 502, upstreamResponse.headers);
      upstreamResponse.pipe(response);
    });
    upstreamRequest.setTimeout(CONNECT_TIMEOUT_MS, () => upstreamRequest.destroy());
    upstreamRequest.on("error", () => {
      if (!response.headersSent) {
        response.writeHead(502);
      }
      response.end();
    });
    request.pipe(upstreamRequest);
  } catch {
    response.writeHead(502, { Connection: "close" });
    response.end();
  }
}

function sanitizedHeaders(headers, originalHost, upstreamProxy) {
  const sanitized = { ...headers, host: originalHost };
  delete sanitized["proxy-authorization"];
  delete sanitized["proxy-connection"];
  if (upstreamProxy?.username) {
    sanitized["proxy-authorization"] = basicProxyAuthorization(upstreamProxy);
  }
  return sanitized;
}

async function connectThroughUpstream(target, upstreamProxy) {
  const socket = await connectSocket(upstreamProxy.host, upstreamProxy.port);
  const headers = [
    `CONNECT ${target.authority} HTTP/1.1`,
    `Host: ${target.authority}`,
    "Proxy-Connection: keep-alive",
  ];
  if (upstreamProxy.username) {
    headers.push(`Proxy-Authorization: ${basicProxyAuthorization(upstreamProxy)}`);
  }
  socket.write(`${headers.join("\r\n")}\r\n\r\n`);
  const responseHead = await readResponseHead(socket);
  if (!/^HTTP\/1\.[01] 2\d\d\b/u.test(responseHead)) {
    socket.destroy();
    throw new Error("Upstream proxy refused pinned CONNECT");
  }
  return socket;
}

function readResponseHead(socket) {
  return new Promise((resolve, reject) => {
    let buffer = Buffer.alloc(0);
    const timer = setTimeout(() => finish(new Error("Upstream proxy timed out")), CONNECT_TIMEOUT_MS);
    timer.unref();
    const onData = (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      const boundary = buffer.indexOf("\r\n\r\n");
      if (boundary < 0) {
        if (buffer.length > 16_384) {
          finish(new Error("Upstream proxy response is too large"));
        }
        return;
      }
      const extra = buffer.subarray(boundary + 4);
      if (extra.length) {
        socket.unshift(extra);
      }
      finish(null, buffer.subarray(0, boundary).toString("latin1"));
    };
    const onError = (error) => finish(error);
    const onClose = () => finish(new Error("Upstream proxy closed connection"));
    const finish = (error, value) => {
      clearTimeout(timer);
      socket.off("data", onData);
      socket.off("error", onError);
      socket.off("close", onClose);
      error ? reject(error) : resolve(value);
    };
    socket.on("data", onData);
    socket.once("error", onError);
    socket.once("close", onClose);
  });
}

function connectSocket(host, port, family) {
  return new Promise((resolve, reject) => {
    const socket = net.connect({ host, port, family });
    const timer = setTimeout(() => socket.destroy(new Error("Connection timed out")), CONNECT_TIMEOUT_MS);
    timer.unref();
    socket.once("connect", () => {
      clearTimeout(timer);
      resolve(socket);
    });
    socket.once("error", (error) => {
      clearTimeout(timer);
      reject(error);
    });
  });
}

function normalizedPort(url) {
  const port = url.port ? Number(url.port) : url.protocol === "https:" ? 443 : 80;
  if (!Number.isInteger(port) || port < 1 || port > 65_535) {
    throw new UnsafeTargetError("invalid_port");
  }
  return port;
}

function authority(address, port) {
  return `${net.isIP(address) === 6 ? `[${address}]` : address}:${port}`;
}

function basicProxyAuthorization(proxy) {
  return `Basic ${Buffer.from(`${proxy.username}:${proxy.password || ""}`, "utf8").toString("base64")}`;
}

function closeServer(server) {
  return new Promise((resolve) => server.close(() => resolve()));
}
