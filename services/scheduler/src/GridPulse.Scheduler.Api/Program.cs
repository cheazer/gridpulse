using GridPulse.Scheduler.Api;
using GridPulse.Scheduler.Data;
using GridPulse.Scheduler.Telemetry;
using Microsoft.AspNetCore.Diagnostics.HealthChecks;
using Microsoft.Extensions.Diagnostics.HealthChecks;
using Scalar.AspNetCore;

var builder = WebApplication.CreateBuilder(args);

builder.AddTelemetry("scheduler-api", SchedulerMetrics.MeterName);
builder.Services.AddSingleton<SchedulerMetrics>();
builder.Services.AddSchedulerData(builder.Configuration);
builder.Services.AddProblemDetails();
builder.Services.ConfigureHttpJsonOptions(options => options.SerializerOptions.Converters.Add(new ZonedDateTimeOffsetConverter()));
builder.Services.AddExceptionHandler<BadRequestHandler>();
builder.Services.AddExceptionHandler<ForecastsUnavailableHandler>();
builder.Services.AddOpenApi(options => options.AddDocumentTransformer((document, _, _) =>
{
    document.Info.Title = "GridPulse scheduler";
    document.Info.Description = "Finds the lowest-carbon window for flexible electricity use in Great Britain.";
    return Task.CompletedTask;
}));

// A plain client for the readiness probe: no retries, a short timeout. A probe should answer quickly, not hide trouble.
builder.Services.AddHttpClient(ForecastingApiCheck.ClientName, client =>
{
    client.BaseAddress = new Uri((builder.Configuration["FORECASTING_API_URL"] ?? "http://localhost:8000").TrimEnd('/') + "/");
    client.Timeout = TimeSpan.FromSeconds(2);
});
builder.Services.AddHealthChecks()
    .AddCheck<DatabaseCheck>("database", HealthStatus.Unhealthy, ["ready"])
    .AddCheck<ForecastingApiCheck>("forecasting-api", HealthStatus.Degraded, ["ready"]);

var app = builder.Build();

app.UseExceptionHandler();
app.UseStatusCodePages();
app.MapOpenApi();
app.MapScalarApiReference();

// Liveness: the process is up. It checks nothing else, so a dependency outage never gets the service restarted.
app.MapGet("/health", () => TypedResults.Ok(new { status = "ok" })).WithTags("ops");
// Readiness: the database answers (else 503); the forecasting API being down only degrades it (still 200).
app.MapHealthChecks("/ready", new HealthCheckOptions
{
    Predicate = check => check.Tags.Contains("ready"),
    ResponseWriter = HealthResponse.Write,
}).WithTags("ops");

app.MapSchedulerEndpoints();

app.Run();
