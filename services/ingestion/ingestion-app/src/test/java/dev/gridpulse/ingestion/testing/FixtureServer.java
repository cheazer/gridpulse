package dev.gridpulse.ingestion.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * A stand-in for an upstream API that serves recorded responses. Requests are matched on their raw
 * path and query, as sent, so encoding mistakes are caught; anything unmatched gets a 400, as the
 * real APIs do for malformed requests. Failures can be queued to exercise retries. Uses the JDK's
 * built-in HTTP server, so no extra test dependency.
 */
public final class FixtureServer implements AutoCloseable {

  private final HttpServer server;
  private final String basePath;
  private final Map<Pattern, String> routes = new LinkedHashMap<>();
  private final Deque<Integer> queuedFailures = new ArrayDeque<>();
  private final AtomicInteger requests = new AtomicInteger();
  private final List<String> requested = new CopyOnWriteArrayList<>();

  private FixtureServer(HttpServer server, String basePath) {
    this.server = server;
    this.basePath = basePath;
  }

  /**
   * @param basePath path prefix of the API's base URL, e.g. {@code /bmrs/api/v1}, or empty
   * @param routes regex over path-and-query (after the base path) mapped to a classpath resource
   */
  public static FixtureServer start(String basePath, Map<String, String> routes) {
    try {
      var server =
          new FixtureServer(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0), basePath);
      routes.forEach((regex, resource) -> server.routes.put(Pattern.compile(regex), resource));
      server.server.createContext("/", server::handle);
      server.server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public URI baseUrl() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + basePath);
  }

  /** The next requests fail with these statuses, in order, before fixtures are served again. */
  public synchronized void failNext(int... statuses) {
    for (int status : statuses) {
      queuedFailures.add(status);
    }
  }

  public int requestCount() {
    return requests.get();
  }

  /** Path and query of every request, relative to the base path, as sent on the wire. */
  public List<String> requestedPaths() {
    return List.copyOf(requested);
  }

  public static byte[] resource(String name) {
    try (InputStream in = FixtureServer.class.getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalArgumentException("no test resource " + name);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    requests.incrementAndGet();
    URI uri = exchange.getRequestURI();
    String raw = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
    String relative = raw.startsWith(basePath) ? raw.substring(basePath.length()) : raw;
    requested.add(relative);

    Integer failure;
    synchronized (this) {
      failure = queuedFailures.poll();
    }
    String resource =
        failure != null || !raw.startsWith(basePath)
            ? null
            : routes.entrySet().stream()
                .filter(route -> route.getKey().matcher(relative).matches())
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    if (failure != null) {
      exchange.sendResponseHeaders(failure, -1);
    } else if (resource == null) {
      exchange.sendResponseHeaders(400, -1);
    } else {
      byte[] body = resource(resource);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
    }
    exchange.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
