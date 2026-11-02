using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using OpenTelemetry;
using OpenTelemetry.Metrics;
using OpenTelemetry.Resources;
using OpenTelemetry.Trace;

namespace GridPulse.Scheduler.Telemetry;

public static class TelemetryExtensions
{
    /// <summary>
    /// Built-in meters worth exporting: request durations and counts (ASP.NET Core, Kestrel, HttpClient), the runtime
    /// (GC, thread pool, memory), database calls (Npgsql), and retries and circuit-breaker state (Polly, under the HTTP
    /// resilience pipelines). .NET emits all of these itself; naming them here is what turns them on for export.
    /// </summary>
    private static readonly string[] BuiltInMeters =
    [
        "Microsoft.AspNetCore.Hosting",
        "Microsoft.AspNetCore.Server.Kestrel",
        "System.Net.Http",
        "System.Runtime",
        "Npgsql",
        "Polly",
    ];

    /// <summary>
    /// Traces and metrics for one service, sent over OTLP to the collector named by OTEL_EXPORTER_OTLP_ENDPOINT. The
    /// standard OTEL_* environment variables configure the rest (protocol, headers, extra resource attributes), so
    /// nothing here is specific to where the service runs. With no endpoint set nothing is exported: tests and a bare
    /// <c>dotnet run</c> don't log connection errors to a collector that isn't there.
    /// </summary>
    /// <param name="builder">The application builder.</param>
    /// <param name="serviceName">The service.name every span and metric carries, e.g. "scheduler-api".</param>
    /// <param name="meters">The service's own meters, on top of the built-in ones.</param>
    public static WebApplicationBuilder AddTelemetry(this WebApplicationBuilder builder, string serviceName, params string[] meters)
    {
        ArgumentNullException.ThrowIfNull(builder);

        var otel = builder.Services.AddOpenTelemetry()
            .ConfigureResource(resource => resource.AddService(serviceName))
            .WithTracing(tracing => tracing
                .AddAspNetCoreInstrumentation(options => options.Filter = context => !IsProbe(context.Request.Path))
                .AddHttpClientInstrumentation()
                .AddSource("Npgsql"))
            .WithMetrics(metrics => metrics.AddMeter([.. BuiltInMeters, .. meters]));

        if (!string.IsNullOrWhiteSpace(builder.Configuration["OTEL_EXPORTER_OTLP_ENDPOINT"]))
        {
            otel.UseOtlpExporter();
        }

        return builder;
    }

    /// <summary>
    /// Health probes aren't traced: Kubernetes calls them every few seconds, and they'd bury the requests that matter.
    /// Their latency still shows in the request-duration metric.
    /// </summary>
    internal static bool IsProbe(PathString path) =>
        path.StartsWithSegments("/health", StringComparison.OrdinalIgnoreCase)
        || path.StartsWithSegments("/ready", StringComparison.OrdinalIgnoreCase);
}
