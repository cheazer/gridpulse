package dev.gridpulse.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

class TelemetryEnvironmentPostProcessorTest {

  private final TelemetryEnvironmentPostProcessor processor =
      new TelemetryEnvironmentPostProcessor();

  @Test
  void anEndpointSendsTracesAndMetricsToItsOtlpHttpPaths() {
    var environment =
        new MockEnvironment().withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel:4318/");

    processor.postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
        .isEqualTo("http://otel:4318/v1/traces");
    assertThat(environment.getProperty("management.otlp.metrics.export.url"))
        .isEqualTo("http://otel:4318/v1/metrics");
    assertThat(environment.getProperty("management.otlp.metrics.export.enabled")).isNull();
    assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("1.0");
    assertThat(environment.getProperty("management.otlp.metrics.export.base-time-unit"))
        .isEqualTo("seconds");
  }

  @Test
  void noEndpointTurnsExportOff() {
    var environment = new MockEnvironment();

    processor.postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty("management.otlp.metrics.export.enabled"))
        .isEqualTo("false");
    assertThat(environment.getProperty("management.tracing.export.otlp.enabled"))
        .isEqualTo("false");
    assertThat(environment.getProperty("management.opentelemetry.tracing.export.otlp.endpoint"))
        .isNull();
  }

  @Test
  void explicitSettingsStillWin() {
    var environment =
        new MockEnvironment()
            .withProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel:4318")
            .withProperty("management.tracing.sampling.probability", "0.5");

    processor.postProcessEnvironment(environment, new SpringApplication());

    assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("0.5");
  }
}
