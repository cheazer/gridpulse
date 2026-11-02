using System.Text.Json;
using Microsoft.Extensions.Diagnostics.HealthChecks;
using Npgsql;

namespace GridPulse.Scheduler.Api;

/// <summary>The database answers. Without it nothing can be scheduled, so failing this makes the service unready.</summary>
internal sealed class DatabaseCheck(NpgsqlDataSource dataSource) : IHealthCheck
{
    public async Task<HealthCheckResult> CheckHealthAsync(HealthCheckContext context, CancellationToken cancellationToken = default)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(2));
        try
        {
            await using var connection = await dataSource.OpenConnectionAsync(timeout.Token).ConfigureAwait(false);
            await using var command = new NpgsqlCommand("SELECT 1", connection);
            await command.ExecuteScalarAsync(timeout.Token).ConfigureAwait(false);
            return HealthCheckResult.Healthy();
        }
        catch (Exception error) when (error is NpgsqlException or OperationCanceledException or TimeoutException)
        {
            return new HealthCheckResult(context.Registration.FailureStatus, error.GetType().Name);
        }
    }
}

/// <summary>
/// The forecasting API is ready. Registered as Degraded on failure, not Unhealthy: NESO-based scheduling still works
/// without it, so an orchestrator shouldn't take the scheduler out of service, only report it.
/// </summary>
internal sealed class ForecastingApiCheck(IHttpClientFactory clients) : IHealthCheck
{
    public const string ClientName = "forecasting-health";

    public async Task<HealthCheckResult> CheckHealthAsync(HealthCheckContext context, CancellationToken cancellationToken = default)
    {
        try
        {
            using var response = await clients.CreateClient(ClientName)
                .GetAsync(new Uri("ready", UriKind.Relative), cancellationToken).ConfigureAwait(false);
            return response.IsSuccessStatusCode
                ? HealthCheckResult.Healthy()
                : new HealthCheckResult(context.Registration.FailureStatus, $"HTTP {(int)response.StatusCode}");
        }
        catch (Exception error) when (error is HttpRequestException or TaskCanceledException)
        {
            return new HealthCheckResult(context.Registration.FailureStatus, error.GetType().Name);
        }
    }
}

internal static class HealthResponse
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    /// <summary>{"status": "Healthy", "checks": {"database": {"status": ..., "description": ...}, ...}}</summary>
    public static Task Write(HttpContext context, HealthReport report)
    {
        context.Response.ContentType = "application/json";
        return context.Response.WriteAsync(JsonSerializer.Serialize(
            new
            {
                status = report.Status.ToString(),
                checks = report.Entries.ToDictionary(
                    e => e.Key, e => new { status = e.Value.Status.ToString(), description = e.Value.Description }),
            },
            Json));
    }
}
