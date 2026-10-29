package dev.gridpulse.ingestion.elexon;

import dev.gridpulse.ingestion.testing.FixtureServer;

/** Public access to the Elexon fixture server for tests in other packages. */
public final class ElexonTestServer {

  private ElexonTestServer() {}

  public static FixtureServer start() {
    return ElexonFixtures.server();
  }
}
