package dev.gridpulse.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Configures Spring Boot's OTLP export from {@code OTEL_EXPORTER_OTLP_ENDPOINT}, the variable every
 * GridPulse service reads, so the platform (Compose, Helm) names the collector once for all of
 * them, whatever their language.
 *
 * <ul>
 *   <li>Set: traces go to {@code <endpoint>/v1/traces} and metrics to {@code
 *       <endpoint>/v1/metrics}, over OTLP/HTTP like the Python and .NET services.
 *   <li>Unset: nothing is exported, so tests and local runs don't log errors about a collector that
 *       isn't there.
 * </ul>
 *
 * <p>Either way, logs are ECS JSON lines carrying the current trace and span IDs.
 *
 * <p>These are defaults: the property source is added last, so anything set in application.yaml or
 * Spring's own environment variables still wins.
 */
public class TelemetryEnvironmentPostProcessor implements EnvironmentPostProcessor {

  static final String ENDPOINT = "OTEL_EXPORTER_OTLP_ENDPOINT";

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    environment
        .getPropertySources()
        .addLast(
            new MapPropertySource("gridpulseOtlp", defaults(environment.getProperty(ENDPOINT))));
  }

  static Map<String, Object> defaults(String endpoint) {
    Map<String, Object> properties = new LinkedHashMap<>();
    // Every trace, not Spring Boot's default 10%: these services handle a few requests a minute,
    // so sampling would only lose the rare trace worth looking at.
    properties.put("management.tracing.sampling.probability", 1.0);
    // Durations in seconds, OpenTelemetry's convention and what the .NET and Python services send,
    // not Micrometer's default of milliseconds: one unit for every duration in Prometheus.
    properties.put("management.otlp.metrics.export.base-time-unit", "seconds");
    // Logs as one JSON object per line in the Elastic Common Schema, the shape the .NET and Python
    // services write too. Micrometer puts the current trace and span IDs in the MDC as traceId and
    // spanId, which Spring copies into each line; renaming them to ECS's trace.id and span.id
    // (dotted keys, which ECS allows alongside nested objects) is what lets a log line be looked up
    // as a trace in Tempo. Brackets keep the keys' case through Spring's property binding.
    properties.put("logging.structured.format.console", "ecs");
    properties.put("logging.structured.json.rename[traceId]", "trace.id");
    properties.put("logging.structured.json.rename[spanId]", "span.id");
    if (endpoint == null || endpoint.isBlank()) {
      properties.put("management.otlp.metrics.export.enabled", false);
      properties.put("management.tracing.export.otlp.enabled", false);
    } else {
      String base = endpoint.strip().replaceAll("/+$", "");
      properties.put("management.opentelemetry.tracing.export.otlp.endpoint", base + "/v1/traces");
      properties.put("management.otlp.metrics.export.url", base + "/v1/metrics");
    }
    return properties;
  }
}
