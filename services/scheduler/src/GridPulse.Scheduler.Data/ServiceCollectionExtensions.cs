using GridPulse.Scheduler.Core;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Http.Resilience;
using Npgsql;

namespace GridPulse.Scheduler.Data;

public static class ServiceCollectionExtensions
{
    /// <summary>
    /// Registers <see cref="ICarbonForecasts"/> and what it needs, configured from the same environment variables as
    /// the rest of the stack (.env.example): POSTGRES_HOST, TIMESCALE_PORT, POSTGRES_DB, SCHEDULER_DB_USER,
    /// SCHEDULER_DB_PASSWORD and FORECASTING_API_URL.
    /// </summary>
    /// <param name="services">The service collection to add to.</param>
    /// <param name="configuration">Configuration with those keys; environment variables in production.</param>
    /// <param name="configureResilience">Adjusts the HTTP resilience settings; tests use it to shorten retry delays.</param>
    public static IServiceCollection AddSchedulerData(
        this IServiceCollection services,
        IConfiguration configuration,
        Action<HttpStandardResilienceOptions>? configureResilience = null)
    {
        ArgumentNullException.ThrowIfNull(configuration);

        var connection = new NpgsqlConnectionStringBuilder
        {
            Host = configuration["POSTGRES_HOST"] ?? "localhost",
            Port = int.Parse(configuration["TIMESCALE_PORT"] ?? "5432", System.Globalization.CultureInfo.InvariantCulture),
            Database = configuration["POSTGRES_DB"] ?? "gridpulse",
            Username = configuration["SCHEDULER_DB_USER"] ?? "scheduler",
            Password = configuration["SCHEDULER_DB_PASSWORD"]
                ?? throw new InvalidOperationException("set SCHEDULER_DB_PASSWORD (see .env.example)"),
            SearchPath = "grid",
            Timezone = "UTC",
            ApplicationName = "gridpulse-scheduler",
            // Read-only twice over: the login can only SELECT carbon_intensity (V8), and every session is read-only.
            Options = "-c default_transaction_read_only=on",
        };
        services.AddSingleton(_ => NpgsqlDataSource.Create(connection.ConnectionString));
        services.AddSingleton<NesoForecastRepository>();
        services.AddSingleton(TimeProvider.System);
        services.AddSingleton<ICarbonForecasts, CarbonForecasts>();

        var baseUrl = configuration["FORECASTING_API_URL"] ?? "http://localhost:8000";
        services
            .AddHttpClient<ForecastingApiClient>(client => client.BaseAddress = new Uri(baseUrl.TrimEnd('/') + "/"))
            // Retries with exponential backoff and jitter on 5xx, 408, 429 and timeouts (never on other 4xx), a timeout
            // per attempt and overall, and a circuit breaker so a dead forecasting service is skipped quickly instead
            // of every request waiting for it.
            .AddStandardResilienceHandler(options =>
            {
                options.AttemptTimeout.Timeout = TimeSpan.FromSeconds(3);
                options.TotalRequestTimeout.Timeout = TimeSpan.FromSeconds(12);
                options.Retry.MaxRetryAttempts = 3;
                options.CircuitBreaker.SamplingDuration = TimeSpan.FromSeconds(30);
                configureResilience?.Invoke(options);
            });
        return services;
    }
}
