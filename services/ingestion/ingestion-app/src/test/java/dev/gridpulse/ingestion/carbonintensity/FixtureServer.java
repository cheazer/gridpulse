package dev.gridpulse.ingestion.carbonintensity;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * A stand-in for api.carbonintensity.org.uk serving the recorded fixtures. Failures can be queued
 * to exercise retries. Uses the JDK's built-in HTTP server, so no extra test dependency.
 */
final class FixtureServer implements AutoCloseable {

  private static final String API_TIME = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}Z";
  private static final Pattern NATIONAL = Pattern.compile("/intensity/" + API_TIME + "/fw48h");
  private static final Pattern REGIONAL =
      Pattern.compile("/regional/intensity/" + API_TIME + "/fw48h");

  private final HttpServer server;
  private final Deque<Integer> queuedFailures = new ArrayDeque<>();
  private final AtomicInteger requests = new AtomicInteger();
  private final List<String> paths = new CopyOnWriteArrayList<>();

  private FixtureServer(HttpServer server) {
    this.server = server;
  }

  static FixtureServer start() {
    try {
      var server = new FixtureServer(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
      server.server.createContext("/", server::handle);
      server.server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  URI baseUrl() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  /** The next requests fail with these statuses, in order, before fixtures are served again. */
  synchronized void failNext(int... statuses) {
    for (int status : statuses) {
      queuedFailures.add(status);
    }
  }

  int requestCount() {
    return requests.get();
  }

  private void handle(HttpExchange exchange) throws IOException {
    requests.incrementAndGet();
    Integer failure;
    synchronized (this) {
      failure = queuedFailures.poll();
    }
    // The raw path, as sent: the real API rejects a percent-encoded ':' in the timestamp.
    String path = exchange.getRequestURI().getRawPath();
    paths.add(path);
    if (failure != null) {
      exchange.sendResponseHeaders(failure, -1);
    } else if (NATIONAL.matcher(path).matches()) {
      respond(exchange, Fixtures.bytes("national.json"));
    } else if (REGIONAL.matcher(path).matches()) {
      respond(exchange, Fixtures.bytes("regional.json"));
    } else {
      exchange.sendResponseHeaders(400, -1); // what the real API does with a malformed datetime
    }
    exchange.close();
  }

  List<String> requestedPaths() {
    return List.copyOf(paths);
  }

  private static void respond(HttpExchange exchange, byte[] body) throws IOException {
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
