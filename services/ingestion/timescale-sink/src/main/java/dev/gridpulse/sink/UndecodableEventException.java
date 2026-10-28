package dev.gridpulse.sink;

import java.io.Serial;

/** A Kafka record that isn't a valid GridPulse event. Retrying can't help; it is dead-lettered. */
public class UndecodableEventException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public UndecodableEventException(String message, Throwable cause) {
    super(message, cause);
  }
}
