package dev.gridpulse.ingestion.http;

import java.io.Serial;

/** An upstream API could not be read, after any retries. */
public class SourceUnavailableException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public SourceUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
