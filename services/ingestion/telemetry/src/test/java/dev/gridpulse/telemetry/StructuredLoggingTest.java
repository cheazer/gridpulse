package dev.gridpulse.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** A real (non-web) Spring Boot app with this module on the classpath, as the two apps use it. */
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTest {

  private static final Logger log = LoggerFactory.getLogger(StructuredLoggingTest.class);

  @Test
  void aLineLoggedInsideASpanIsEcsJsonCarryingThatSpansIds(CapturedOutput output) {
    String[] ids = new String[2];
    try (var context =
        new SpringApplicationBuilder(App.class)
            .web(WebApplicationType.NONE)
            .properties("spring.application.name=logging-test")
            .run()) {
      var tracer = context.getBean(Tracer.class);
      Observation.createNotStarted("test", context.getBean(ObservationRegistry.class))
          .observe(
              () -> {
                log.info("inside a span");
                ids[0] = tracer.currentSpan().context().traceId();
                ids[1] = tracer.currentSpan().context().spanId();
              });
    }

    String line = lineContaining(output, "inside a span");
    assertThat(line).startsWith("{").endsWith("}");
    assertThat(line)
        .contains("\"log\":{\"level\":\"INFO\"")
        .contains("\"service\":{\"name\":\"logging-test\"")
        .contains("\"trace.id\":\"" + ids[0] + "\"")
        .contains("\"span.id\":\"" + ids[1] + "\"")
        .doesNotContain("traceId")
        .doesNotContain("spanId");
  }

  private static String lineContaining(CapturedOutput output, String text) {
    return output
        .getOut()
        .lines()
        .filter(line -> line.contains(text))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no line containing " + text + " in\n" + output));
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class App {}
}
